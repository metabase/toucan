(ns toucan.hydrate
  "Functions for deserializing and hydrating fields in objects fetched from the DB."
  (:require [toucan
             [db :as db]
             [dispatch :as dispatch]
             [instance :as instance]
             [models :as models]]
            [toucan.db.impl :as db.impl]
            [toucan.connection :as connection]))

;; NOCOMMIT
#_(doseq [[symb] (ns-interns *ns*)]
  (ns-unmap *ns* symb))

(defmulti hydration-keys
  "The `hydration-keys` method can be overrode to specify the keyword field names that should be hydrated as instances
  of this model. For example, `User` might include `:creator`, which means `hydrate` will look for `:creator_id` or
  `:creator-id` in other objects to find the User ID, and fetch the `Users` corresponding to those values."
  {:arglists '([model])}
  dispatch/dispatch-value)

(defmulti automagic-hydration-key-model
  {:arglists '([k])}
  identity
  :default ::default)

(defmethod automagic-hydration-key-model ::default
  [_]
  nil)

(defn- hydrate-dispatch-value [result-or-results k]
  (if (sequential? result-or-results)
    (recur (first result-or-results) k)
    [(dispatch/dispatch-value result-or-results) k]))

(defmulti batched-hydrate
  {:arglists '([results k])}
  hydrate-dispatch-value)

(defmulti simple-hydrate
  {:arglists '([result k])}
  hydrate-dispatch-value)

(defmethod simple-hydrate :default
  [result k]
  (throw (ex-info (str (format "Don't know how to hydrate %s for model %s." k (instance/model result))
                       " Define hydration behavior by providing an implementation for `simple-hydrate`,"
                       " `hydrate-dispatch-value`, or `batched-hydrate`. ")
                  {:model (instance/model result), :result result, :k k})))

;; TODO - huh

(defmulti can-hydrate-with-strategy?
  {:arglists '([strategy results k])}
  (fn [strategy _ _] strategy))

(defmulti hydrate-with-strategy
  {:arglists '([strategy results k])}
  (fn [strategy _ _] strategy))

(def strategies
  (atom [::automagic-batched ::multimethod-batched ::multimethod-simple]))

(defn hydration-strategy [results k]
  (some
   (fn [strategy]
     (when (can-hydrate-with-strategy? strategy results k)
       (connection/debug-println "Hydrating with strategy:" strategy)
       strategy))
   @strategies))


;;;                                  Automagic Batched Hydration (via :model-keys)
;;; ==================================================================================================================

(defn- kw-append
  "Append to a keyword.

     (kw-append :user \"_id\") -> :user_id"
  [k suffix]
  (keyword
   (str (when-let [nmspc (namespace k)]
          (str nmspc "/"))
        (name k)
        suffix)))

(defmethod can-hydrate-with-strategy? ::automagic-batched
  [_ results k]
  (boolean
   (let [underscore-id-key (kw-append k "_id")
         dash-id-key       (kw-append k "-id")
         contains-k-id?    #(some % [underscore-id-key dash-id-key])]
     (and (automagic-hydration-key-model k)
          (every? contains-k-id? results)))))

(defmethod hydrate-with-strategy ::automagic-batched
  [_ results dest-key]
  (let [model       (automagic-hydration-key-model dest-key)
        source-keys #{(kw-append dest-key "_id") (kw-append dest-key "-id")}
        ids         (set (for [result results
                               :when  (not (get result dest-key))
                               :let   [k (some result source-keys)]
                               :when  k]
                           k))
        primary-key (models/primary-key model)
        objs        (if (seq ids)
                      (into {} (for [item (db/select model, primary-key [:in ids])]
                                 {(primary-key item) item}))
                      (constantly nil))]
    (for [result results
          :let [source-id (some result source-keys)]]
      (if (get result dest-key)
        result
        (assoc result dest-key (objs source-id))))))


;;;                         Method-Based Batched Hydration (using impls of `batched-hydrate`)
;;; ==================================================================================================================

(defn- batched-hydrate-method [results k]
  (some (partial get-method batched-hydrate)
        [(hydrate-dispatch-value results k) [:default k]]))

(defmethod can-hydrate-with-strategy? ::multimethod-batched
  [_ results k]
  (boolean (batched-hydrate-method results k)))

(defmethod hydrate-with-strategy ::multimethod-batched
  [_ results k]
  ((batched-hydrate-method results k) results k))


;;;                          Method-Based Simple Hydration (using impls of `simple-hydrate`)
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- simple-hydrate-method [results k]
  (some (partial get-method simple-hydrate)
        [(hydrate-dispatch-value results k) [:default k]]))

(defmethod can-hydrate-with-strategy? ::multimethod-simple
  [_ results k]
  (boolean (simple-hydrate-method results k)))

(defmethod hydrate-with-strategy ::multimethod-simple
  [_ results k]
  (let [method (simple-hydrate-method results k)]
    (for [result results]
      (if (some? (get result k))
        result
        (assoc result k (method result k))))))


;;;                                               Primary Hydration Fns
;;; ==================================================================================================================

(declare hydrate)

(defn- hydrate-key-seq
  "Hydrate a nested hydration form (vector) by recursively calling `hydrate`."
  [results [k & nested-keys :as coll]]
  (assert (seq nested-keys)
    (format (str "Replace '%s' with '%s'. Vectors are for nested hydration. "
                 "There's no need to use one when you only have a single key.")
            coll k))
  (let [results     (hydrate results k)
        vs          (map k results)
        hydrated-vs (apply hydrate vs nested-keys)]
    (map
     (fn [result v]
       (assoc result k v))
     results
     hydrated-vs)))

(defn- hydrate-key
  "Hydrate a single key."
  [results k]
  (if-let [strategy (hydration-strategy results k)]
    (do
      (connection/debug-println (format "Hydrating %s with strategy %s" k strategy))
      (hydrate-with-strategy strategy results k))
    (do
      (connection/debug-println (format "Unable to hydrate %k: no matching strategy found" k))
      results)))

(defn- hydrate-one-form
  "Hydrate a single hydration form."
  [results k]
  (cond
    (keyword? k)
    (hydrate-key results k)

    (sequential? k)
    (hydrate-key-seq results k)

    :else
    (throw (ex-info (format "Invalid hydrate form: %s. Expected keyword or sequence" k) {:k k}))))

(defn- hydrate-forms
  "Hydrate many hydration forms across a *sequence* of `results` by recursively calling `hydrate-one-form`."
  [results & forms]
  (reduce hydrate-one-form results forms))


;;;                                                 Public Interface
;;; ==================================================================================================================

;;                         hydrate <-------------+
;;                           |                   |
;;                       hydrate-forms           |
;;                           | (for each form)   |
;;                       hydrate-one-form        | (recursively)
;;                           |                   |
;;                keyword? --+-- sequence?       |
;;                   |             |             |
;;             hydrate-key   hydrate-key-seq ----+
;;                   |
;;          (for each strategy) <--------+
;;           ::automagic-batched         |
;;           ::multimethod-batched       |
;;           ::multimethod-simple        | (try next strategy)
;;                   |                   |
;;          can-hydrate-with-strategy?   |
;;                   |                   |
;;            yes ---+--- no ------------+
;;             |
;;    hydrate-with-strategy


(defn hydrate
  "Hydrate a single object or sequence of objects.


#### Automagic Batched Hydration (via hydration-keys)

  `hydrate` attempts to do a *batched hydration* where possible.
  If the key being hydrated is defined as one of some model's `hydration-keys`,
  `hydrate` will do a batched `db/select` if a corresponding key ending with `_id`
  is found in the objects being batch hydrated.

    (hydrate [{:user_id 100}, {:user_id 101}] :user)

  Since `:user` is a hydration key for `User`, a single `db/select` will used to
  fetch `Users`:

    (db/select User :id [:in #{100 101}])

  The corresponding `Users` are then added under the key `:user`.


#### Function-Based Batched Hydration (via functions marked ^:batched-hydrate)

  If the key can't be hydrated auto-magically with the appropriate `:hydration-keys`,
  `hydrate` will look for a function tagged with `:batched-hydrate` in its metadata, and
  use that instead. If a matching function is found, it is called with a collection of objects,
  e.g.

    (defn with-fields
      \"Efficiently add `:fields` to a collection of `tables`.\"
      {:batched-hydrate :fields}
      [tables]
      ...)

    (let [tables (get-some-tables)]
      (hydrate tables :fields))     ; uses with-fields

  By default, the function will be used to hydrate keys that match its name; as in the example above,
  you can specify a different key to hydrate for in the metadata instead.


#### Simple Hydration (via functions marked ^:hydrate)

  If the key is *not* eligible for batched hydration, `hydrate` will look for a function or method
  tagged with `:hydrate` in its metadata, and use that instead; if a matching function
  is found, it is called on the object being hydrated and the result is `assoc`ed:

    (defn ^:hydrate dashboard [{:keys [dashboard_id]}]
      (Dashboard dashboard_id))

    (let [dc (DashboardCard ...)]
      (hydrate dc :dashboard))    ; roughly equivalent to (assoc dc :dashboard (dashboard dc))

  As with `:batched-hydrate` functions, by default, the function will be used to hydrate keys that
  match its name; you can specify a different key to hydrate instead as the metadata value of `:hydrate`:

    (defn ^{:hydrate :pk_field} pk-field-id [obj] ...) ; hydrate :pk_field with pk-field-id

  Keep in mind that you can only define a single function/method to hydrate each key; move functions into the
  `IModel` interface as needed.


#### Hydrating Multiple Keys

  You can hydrate several keys at one time:

    (hydrate {...} :a :b)
      -> {:a 1, :b 2}

#### Nested Hydration

  You can do recursive hydration by listing keys inside a vector:

    (hydrate {...} [:a :b])
      -> {:a {:b 1}}

  The first key in a vector will be hydrated normally, and any subsequent keys
  will be hydrated *inside* the corresponding values for that key.

    (hydrate {...}
             [:a [:b :c] :e])
      -> {:a {:b {:c 1} :e 2}}"
  [results k & ks]
  (when results
    (if (sequential? results)
      (if (empty? results)
        results
        (apply hydrate-forms results k ks))
      (first (apply hydrate-forms [results] k ks)))))

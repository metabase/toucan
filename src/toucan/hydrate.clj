(ns toucan.hydrate
  "Functions for deserializing and hydrating fields in objects fetched from the DB."
  (:require [toucan
             [db :as db]
             [debug :as debug]
             [dispatch :as dispatch]
             [models :as models]]))

;; NOCOMMIT
#_(doseq [[symb] (ns-interns *ns*)]
  (ns-unmap *ns* symb))

(defmulti can-hydrate-with-strategy?
  {:arglists '([strategy results k])}
  (fn [strategy _ _] strategy))

(defmulti hydrate-with-strategy
  {:arglists '([strategy results k])}
  (fn [strategy _ _] strategy))

;;;                                  Automagic Batched Hydration (via :model-keys)
;;; ==================================================================================================================

(defmulti hydration-keys
  "The `hydration-keys` method can be overridden to specify the keyword field names that should be hydrated as instances
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
   (and (automagic-hydration-key-model k)
        (let [underscore-id-key (kw-append k "_id")
              dash-id-key       (kw-append k "-id")
              contains-k-id?    #(some % [underscore-id-key dash-id-key])]
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

(declare batched-hydrate)

(defn- batched-hydrate-dispatch-value [[first-result] k]
  (let [result-dispatch-val (dispatch/dispatch-value first-result)]
    [(if (get-method batched-hydrate [result-dispatch-val k])
        result-dispatch-val
        :default)
     k]))

(defmulti batched-hydrate
  {:arglists '([results k])}
  batched-hydrate-dispatch-value)

(defmethod can-hydrate-with-strategy? ::multimethod-batched
  [_ results k]
  (boolean (get-method batched-hydrate (batched-hydrate-dispatch-value results k))))

(defmethod hydrate-with-strategy ::multimethod-batched
  [_ results k]
  (batched-hydrate results k))


;;;                          Method-Based Simple Hydration (using impls of `simple-hydrate`)
;;; ==================================================================================================================

(declare simple-hydrate)

(defn- simple-hydrate-dispatch-value [result k]
  (let [result-dispatch-val (dispatch/dispatch-value result)]
    [(if (get-method simple-hydrate [result-dispatch-val k])
       result-dispatch-val
       :default)
     k]))

(defmulti simple-hydrate
  {:arglists '([result k])}
  simple-hydrate-dispatch-value)

(defmethod can-hydrate-with-strategy? ::multimethod-simple
  [_ results k]
  (boolean (get-method simple-hydrate (simple-hydrate-dispatch-value results k))))

(defmethod hydrate-with-strategy ::multimethod-simple
  [_ [first-result :as results] k]
  ;; TODO - explain this
  (for [[first-result :as chunk] (partition-by dispatch/dispatch-value results)
        :let                     [method (get-method simple-hydrate (simple-hydrate-dispatch-value first-result k))]
        result                   chunk]
    (when result
      (if (some? (get result k))
        result
        (assoc result k (method result k))))))


;;;                                           Hydration Using All Strategies
;;; ==================================================================================================================

(def strategies
  (atom [::automagic-batched ::multimethod-batched ::multimethod-simple]))

(defn hydration-strategy [results k]
  (some
   (fn [strategy]
     (when (can-hydrate-with-strategy? strategy results k)
       strategy))
   @strategies))

;; TODO - not sure if want
(defn the-hydration-strategy [results k]
  (or (hydration-strategy results k)
      #_(throw
       (ex-info
        (str (format "Don't know how to hydrate %s for dispatch value %s." k [(or (dispatch/dispatch-value results) :default) k])
             " Define hydration behavior by providing an implementation for `simple-hydrate`,"
             " `hydrate-dispatch-value`, or `batched-hydrate`. ")
        ;; this info provided primarily to make life easier when debugging
        {:dispatch-value   (dispatch/dispatch-value results)
         :k                k
         :existing-methods {:automagic-batched (set (keys (methods automagic-hydration-key-model)))
                            :batched           (set (keys (methods batched-hydrate)))
                            :simple-hydrate    (set (keys (methods simple-hydrate)))}}))))


;;;                                               Primary Hydration Fns
;;; ==================================================================================================================

(declare hydrate)

(defn- hydrate-key
  [results k]
  (when (seq results)
    (if (sequential? (first results))
      (hydrate-sequence-of-sequences results k)
      (if-let [strategy (the-hydration-strategy results k)]
        (do
          (debug/debug-println (format "Hydrating %s with strategy %s" k strategy))
          (hydrate-with-strategy strategy results k))
        results))))

(defn- hydrate-key-seq
  "Hydrate a nested hydration form (vector) by recursively calling `hydrate`."
  [results [k & nested-keys :as coll]]
  (when-not (seq nested-keys)
    (throw (ex-info (str (format "Invalid hydration form: replace %s with %s. Vectors are for nested hydration." coll k)
                         " There's no need to use one when you only have a single key.")
                    {:invalid-form coll})))
  (let [results                          (hydrate results k)
        values-of-k                      (map k results)
        recursively-hydrated-values-of-k (apply hydrate values-of-k nested-keys)]
    (map
     (fn [result v]
       (when result
         (assoc result k v)))
     results
     recursively-hydrated-values-of-k)))

(declare hydrate-one-form)

(defn- hydrate-sequence-of-sequences [groups k]
  (let [indexed-flattened-results (for [[i group] (map-indexed vector groups)
                                        result    group]
                                    [i result])
        indecies                  (map first indexed-flattened-results)
        flattened                 (map second indexed-flattened-results)
        hydrated                  (hydrate-one-form flattened k)
        groups                    (partition-by first (map vector indecies hydrated))]
    (for [group groups]
      (map second group))))

(defn- hydrate-one-form
  "Hydrate a single hydration form."
  [results k]
  (if (sequential? (first results))
    (hydrate-sequence-of-sequences results k)
    (cond
      (keyword? k)
      (hydrate-key results k)

      (sequential? k)
      (hydrate-key-seq results k)

      :else
      (throw (ex-info (format "Invalid hydration form: %s. Expected keyword or sequence." k) {:invalid-form k})))))

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

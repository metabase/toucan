(ns toucan.hydrate
  "Functions for deserializing and hydrating fields in objects fetched from the DB."
  (:require [toucan.dispatch :as dispatch]))

;; NOCOMMIT
(doseq [[symb] (ns-interns *ns*)]
  (ns-unmap *ns* symb))

(defmulti hydration-keys
  {:arglists '([model])}
  "The `hydration-keys` method can be overrode to specify the keyword field names that should be hydrated
     as instances of this model. For example, `User` might include `:creator`, which means `hydrate` will
     look for `:creator_id` or `:creator-id` in other objects to find the User ID, and fetch the `Users`
     corresponding to those values."
  dispatch/dispatch-value
  )

(defmulti simple-hydrate
  {:arglists '([model results k])}
  (fn [model _ k]
    [(dispatch/dispatch-value model) k]))

(defmulti batched-hydrate
  {:arglists '([model results k])}
  (fn [model _ k]
    [(dispatch/dispatch-value model) k]))

(defmulti automagic-hydration-key-model
  {:arglists '([k])}
  identity
  :default   ::default)

(defmethod automagic-hydration-key-model ::default
  [_]
  nil)


;;;                                                     Util Fns
;;; ==================================================================================================================

(defn- valid-hydration-form?
  "Is this a valid argument to `hydrate`?"
  [k]
  (or (keyword? k)
      (and (sequential? k)
           (keyword? (first k))
           (every? valid-hydration-form? (rest k)))))

(defn- kw-append
  "Append to a keyword.

     (kw-append :user \"_id\") -> :user_id"
  [k suffix]
  (keyword
   (str (when-let [nmspc (namespace k)]
          (str nmspc "/"))
        (name k)
        suffix)))


;;;                                  Automagic Batched Hydration (via :model-keys)
;;; ==================================================================================================================

(defn- can-automagically-batched-hydrate?
  "Can we do a batched hydration of `results` with key `k`?"
  [results k]
  (let [underscore-id-key (kw-append k "_id")
        dash-id-key       (kw-append k "-id")
        contains-k-id?    #(some % [underscore-id-key dash-id-key])]
    (and (automagic-hydration-key-model k)
         (every? contains-k-id? results))))

(defn- automagically-batched-hydrate
  "Hydrate keyword `dest-key` across all `results` by aggregating corresponding source keys (`DEST-KEY_id`),
  doing a single `db/select`, and mapping corresponding objects to `dest-key`."
  [results dest-key]
  {:pre [(keyword? dest-key)]}
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


;;;                         Function-Based Batched Hydration (fns marked ^:batched-hydrate)
;;; ==================================================================================================================

(defn- can-fn-based-batched-hydrate? [model k]
  (get-method batched-hydrate)
  (contains? (hydration-key->batched-f) k))

(defn- fn-based-batched-hydrate
  [results k]
  {:pre [(keyword? k)]}
  (((hydration-key->batched-f) k) results))


;;;                              Function-Based Simple Hydration (fns marked ^:hydrate)
;;; ==================================================================================================================

(def ^:private hydration-key->f*
  (atom nil))

(defn- hydration-key->f
  "Fetch a map of keys to functions marked `^:hydrate` for them."
  []
  (or @hydration-key->f*
      (reset! hydration-key->f* (lookup-functions-with-metadata-key :hydrate))))

(defn- simple-hydrate
  "Hydrate keyword K in results by calling corresponding functions when applicable."
  [results k]
  {:pre [(keyword? k)]}
  (for [result results]
    ;; don't try to hydrate if they key is already present. If we find a matching fn, hydrate with it
    (when result
      (or (when-not (k result)
            (when-let [f ((hydration-key->f) k)]
              (assoc result k (f result))))
          result))))


;;;                                     Resetting Hydration keys (for REPL usage)
;;; ==================================================================================================================

(defn flush-hydration-key-caches!
  "Clear out the cached hydration keys. Useful when doing interactive development and defining new hydration
  functions."
  []
  (reset! automagic-batched-hydration-key->model* nil)
  (reset! hydration-key->batched-f*               nil)
  (reset! hydration-key->f*                       nil))



;;;                                               Primary Hydration Fns
;;; ==================================================================================================================

(declare hydrate)

(defn- hydrate-vector
  "Hydrate a nested hydration form (vector) by recursively calling `hydrate`."
  [results [k & more :as vect]]
  (assert (> (count vect) 1)
    (format (str "Replace '%s' with '%s'. Vectors are for nested hydration. "
                 "There's no need to use one when you only have a single key.")
            vect (first vect)))
  (let [results (hydrate results k)]
    (if-not (seq more)
      results
      (counts-apply results k #(apply hydrate % more)))))

(defn- hydrate-kw
  "Hydrate a single key."
  [results k]
  (cond
    (can-automagically-batched-hydrate? results k) (automagically-batched-hydrate results k)
    (can-fn-based-batched-hydrate? results k)      (fn-based-batched-hydrate results k)
    :else                                          (simple-hydrate results k)))

(defn- hydrate-1
  "Hydrate a single hydration form."
  [results k]
  (if (keyword? k)
    (hydrate-kw results k)
    (hydrate-vector results k)))

(defn- hydrate-many
  "Hydrate many hydration forms across a *sequence* of RESULTS by recursively calling `hydrate-1`."
  [results k & more]
  (let [results (hydrate-1 results k)]
    (if-not (seq more)
      results
      (recur results (first more) (rest more)))))


;;;                                                 Public Interface
;;; ==================================================================================================================

;;                              hydrate <-------------+
;;                                |                   |
;;                            hydrate-many            |
;;                                | (for each form)   |
;;                            hydrate-1               | (recursively)
;;                                |                   |
;;                     keyword? --+-- vector?         |
;;                        |             |             |
;;                   hydrate-kw    hydrate-vector ----+
;;                        |
;;               can-automagically-batched-hydrate?
;;                              |
;;             true ------------+----------------- false
;;              |                                    |
;;     automagically-batched-hydrate    can-fn-based-batched-hydrate?
;;                                                  |
;;                                true -------------+------------- false
;;                                 |                                 |
;;                      fn-based-batched-hydrate              simple-hydrate

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
      \"Efficiently add `Fields` to a collection of TABLES.\"
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
  {:pre [(valid-hydration-form? k)
         (every? valid-hydration-form? ks)]}
  (when results
    (if (sequential? results)
      (if (empty? results)
        results
        (apply hydrate-many results k ks))
      (first (apply hydrate-many [results] k ks)))))

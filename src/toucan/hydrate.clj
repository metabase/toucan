(ns toucan.hydrate
  "Functions for deserializing and hydrating fields in objects fetched from the DB."
  (:require [clojure.java.classpath :as classpath]
            [clojure.string :as s]
            [clojure.tools.namespace.find :as ns-find]
            (toucan [db :as db]
                    [models :as models])))

;;;                                          Counts Destructuring & Restructuring
;;; ========================================================================================================================

;; #### *DISCLAIMER*
;;
;; This I wrote this code at 4 AM nearly 2 years ago and don't remember exactly what it is supposed to accomplish,
;; or why. It generates a sort of path that records the wacky ways in which objects in a collection are nested,
;; and how they fit into sequences; it then returns a flattened sequence of desired objects for easy modification.
;; Afterwards the modified objects can be put in place of the originals by passing in the sequence of modified objects
;; and the path.
;;
;; Nonetheless, it still works (somehow) and is well-tested. But it's definitely overengineered and crying out to be
;; replaced with a simpler implementation (`clojure.walk` would probably work here). PRs welcome!
;;
;; #### Original Overview
;;
;; At a high level, these functions let you aggressively flatten a sequence of maps by a key
;; so you can apply some function across it, and then unflatten that sequence.
;;
;;          +-------------------------------------------------------------------------+
;;          |                                                                         +--> (map merge) --> new seq
;;     seq -+--> counts-of ------------------------------------+                      |
;;          |                                                  +--> counts-unflatten -+
;;          +--> counts-flatten -> (modify the flattened seq) -+
;;
;; 1.  Get a value that can be used to unflatten a sequence later with `counts-of`.
;; 2.  Flatten the sequence with `counts-flatten`
;; 3.  Modify the flattened sequence as needed
;; 4.  Unflatten the sequence by calling `counts-unflatten` with the modified sequence and value from step 1
;; 5.  `map merge` the original sequence and the unflattened sequence.
;;
;; For your convenience `counts-apply` combines these steps for you.

(defn- counts-of
  "Return a sequence of counts / keywords that can be used to unflatten
   COLL later.

    (counts-of [{:a [{:b 1} {:b 2}], :c 2}
                {:a {:b 3}, :c 4}] :a)
      -> [2 :atom]

   For each `x` in COLL, return:

   *  `(count (k x))` if `(k x)` is sequential
   *  `:atom`         if `(k x)` is otherwise non-nil
   *  `:nil`          if `x` has key `k` but the value is nil
   *  `nil`           if `x` is nil."
  [coll k]
  (map (fn [x]
         (cond
           (sequential? (k x)) (count (k x))
           (k x)               :atom
           (contains? x k)     :nil
           :else               nil))
       coll))

(defn- counts-flatten
  "Flatten COLL by K.

    (counts-flatten [{:a [{:b 1} {:b 2}], :c 2}
                     {:a {:b 3}, :c 4}] :a)
      -> [{:b 1} {:b 2} {:b 3}]"
  [coll k]
  {:pre [(sequential? coll)
         (keyword? k)]}
  (->> coll
       (map k)
       (mapcat (fn [x]
                 (if (sequential? x)  x
                     [x])))))

(defn- counts-unflatten
  "Unflatten COLL by K using COUNTS from `counts-of`.

    (counts-unflatten [{:b 2} {:b 4} {:b 6}] :a [2 :atom])
      -> [{:a [{:b 2} {:b 4}]}
          {:a {:b 6}}]"
  ([coll k counts]
   (counts-unflatten [] coll k counts))
  ([acc coll k [count & more]]
   (let [[unflattend coll] (condp = count
                             nil   [nil (rest coll)]
                             :atom [(first coll) (rest coll)]
                             :nil  [:nil (rest coll)]
                             (split-at count coll))
         acc (conj acc unflattend)]
     (if-not (seq more) (map (fn [x]
                               (when x
                                 {k (when-not (= x :nil) x)}))
                             acc)
             (recur acc coll k more)))))

(defn- counts-apply
  "Apply F to values of COLL flattened by K, then return unflattened/updated results.

    (counts-apply [{:a [{:b 1} {:b 2}], :c 2}
                   {:a {:b 3}, :c 4}]
      :a #(update-in % [:b] (partial * 2)))

      -> [{:a [{:b 2} {:b 4}], :c 2}
          {:a {:b 3}, :c 4}]"
  [coll k f]
  (let [counts (counts-of coll k)
        new-vals (-> coll
                     (counts-flatten k)
                     f
                     (counts-unflatten k counts))]
    (map merge coll new-vals)))


;;;                                                        Util Fns
;;; ========================================================================================================================

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
  (keyword (str (name k) suffix)))

(defn- lookup-functions-with-metadata-key
  "Return a map of hydration keywords to functions that should be used to hydrate them, e.g.

     {:fields #'my-project.models.table/fields
      :tables #'my-project.models.database/tables
      ...}

   These functions are ones that are marked METADATA-KEY, e.g. `^:hydrate` or `^:batched-hydrate`."
  [metadata-key]
  (loop [m {}, [[k f] & more] (for [ns          (all-ns)
                                    [symb varr] (ns-interns ns)
                                    :let        [hydration-key (metadata-key (meta varr))]
                                    :when       hydration-key]
                                [(if (or (true? hydration-key)
                                         (false? hydration-key)) ; boolean?
                                   (keyword (name symb))
                                   hydration-key) varr])]
    (cond
      (not k) m
      (m k)   (throw (Exception. (format "Duplicate `^%s` functions for key '%s': %s and %s." metadata-key k (m k) f)))
      :else   (recur (assoc m k f) more))))


;;;                                     Automagic Batched Hydration (via :model-keys)
;;; ========================================================================================================================

(def ^:private automagic-batched-hydration-key->model
  "Delay that returns map of `hydration-key` -> model
   e.g. `:user -> User`.

   This is built pulling the `hydration-keys` set from all of our entities."
  (delay (for [ns-symb (ns-find/find-namespaces (classpath/classpath))
               :when   (s/starts-with? (name ns-symb) (str (models/root-namespace) \.))]
           (require ns-symb))
         (into {} (for [ns       (all-ns)
                        [_ varr] (ns-publics ns)
                        :let     [model (var-get varr)]
                        :when    (models/model? model)
                        :let     [hydration-keys (models/hydration-keys model)]
                        k        hydration-keys]
                    {k model}))))

(def ^:private automagic-batched-hydration-keys
  "Delay that returns set of keys that are elligible for batched hydration."
  (delay (set (keys @automagic-batched-hydration-key->model))))

(defn- can-automagically-batched-hydrate?
  "Can we do a batched hydration of RESULTS with key K?"
  [results k]
  (let [k-id-u (kw-append k "_id")
        k-id-d (kw-append k "-id")
        contains-k-id? (fn [obj]
                         (or (contains? obj k-id-u)
                             (contains? obj k-id-d)))]
    (and (contains? @automagic-batched-hydration-keys k)
         (every? contains-k-id? results))))

(defn- automagically-batched-hydrate
  "Hydrate keyword DEST-KEY across all RESULTS by aggregating corresponding source keys (`DEST-KEY_id`),
   doing a single `db/select`, and mapping corresponding objects to DEST-KEY."
  [results dest-key]
  {:pre [(keyword? dest-key)]}
  (let [model       (@automagic-batched-hydration-key->model dest-key)
        source-keys #{(kw-append dest-key "_id") (kw-append dest-key "-id")}
        ids         (set (for [result results
                               :when  (not (get result dest-key))
                               :let   [k (some source-keys result)]
                               :when  k]
                           k))
        objs        (if (seq ids)
                      (into {} (for [item (db/select model, :id [:in ids])]
                                 {(:id item) item}))
                      (constantly nil))]
    (for [result results
          :let [source-id (some source-keys result)]]
      (if (get result dest-key)
        result
        (assoc result dest-key (objs source-id))))))


;;;                            Function-Based Batched Hydration (fns marked ^:batched-hydrate)
;;; ========================================================================================================================

(def ^:private k->batched-f (delay (lookup-functions-with-metadata-key :batched-hydrate)))

(defn- hydration-key->batched-f
  "Get the function marked `^:batched-hydrate` for K."
  [k]
  (@k->batched-f k))

(def ^:private fn-based-batched-hydration-keys
  "Delay that returns set of keys that are elligible for batched hydration."
  (delay (set (keys @k->batched-f))))

(defn- can-fn-based-batched-hydrate? [_ k]
  (contains? @fn-based-batched-hydration-keys k))

(defn- fn-based-batched-hydrate
  [results k]
  {:pre [(keyword? k)]}
  ((hydration-key->batched-f k) results))


;;;                                 Function-Based Simple Hydration (fns marked ^:hydrate)
;;; ========================================================================================================================

(def ^:private k->f (delay (lookup-functions-with-metadata-key :hydrate)))

(defn- hydration-key->f
  "Get the function marked `^:hydrate` for K."
  [k]
  (@k->f k))

(defn- simple-hydrate
  "Hydrate keyword K in results by dereferencing corresponding delays when applicable."
  [results k]
  {:pre [(keyword? k)]}
  (for [result results]
    ;; don't try to hydrate if they key is already present. If we find a matching fn, hydrate with it
    (when result
      (or (when-not (k result)
            (when-let [f (hydration-key->f k)]
              (assoc result k (f result))))
          result))))



;;;                                                  Primary Hydration Fns
;;; ========================================================================================================================

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
  "Hydrate a single keyword."
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


;;;                                                    Public Interface
;;; ========================================================================================================================

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

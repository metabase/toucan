(ns toucan.db
  "Helper functions for querying the DB and inserting or updating records using Toucan models."
  (:refer-clojure :exclude [count])
  (:require [clojure.java.jdbc :as jdbc]
            [potemkin :as potemkin]
            [toucan
             [compile :as compile]
             [connection :as connection]
             [instance :as instance]
             [models :as models]]
            [toucan.db.impl :as impl]))

;; NOCOMMIT
(doseq [[symb] (ns-interns *ns*)]
  (ns-unmap *ns* symb))


;;;                                                       select
;;; ==================================================================================================================xf

(defn select
  ([object]
   (select object (models/primary-key-value object)))

  ([model & args]
   (let [query*        (partial connection/query model)
         honeysql-form (apply compile/compile-select model args)]
     (seq (impl/do-select model query* honeysql-form)))))

(defn select-reducible
  ([object]
   (select object (models/primary-key-value object)))

  ([model & args]
   (let [query*        (partial connection/reducible-query model)
         honeysql-form (apply compile/compile-select model args)]
     (impl/do-select model query* honeysql-form))))

(defn select-one
  "Select a single object from the database.

     (select-one ['Database :name] :id 1) -> {:name \"Sample Dataset\"}"
  {:style/indent 1, :arglists '([object] [model & args])}
  [& args]
  (first (apply select (concat args [{:limit 1}]))))

(defn select-one-field
  "Select a single `field` of a single object from the database.

     (select-one-field :name 'Database :id 1) -> \"Sample Dataset\""
  {:style/indent 2, :arglists '([field object] [field model & args])}
  [field model & args]
  {:pre [(keyword? field)]}
  (get (apply select-one [model field] args) field))

(defn select-one-id
  "Select the `:id` of a single object from the database.

     (select-one-id 'Database :name \"Sample Dataset\") -> 1"
  {:style/indent 1, :arglists '([object] [model & args])}
  [model & args]
  (let [pk-key (models/primary-key model)]
    (if-not (sequential? pk-key)
      (apply select-one-field pk-key model args)
      (let [[result] (apply select-one (into [model] pk-key) args)]
        (when result
          (map result pk-key))))))

(defn select-field-seq
  {:style/indent 2, :arglists '([field object] [field model & args])}
  [field model & options]
  {:pre [(keyword? field)]}
  (seq (map field (apply select [model field] options))))

(defn select-field-set
  "Select values of a single field for multiple objects. These are returned as a set if any matching fields
   were returned, otherwise `nil`.

     (select-field :name 'Database) -> #{\"Sample Dataset\", \"test-data\"}"
  {:style/indent 2, :arglists '([field object] [field model & args])}
  [field model & args]
  (set (apply select-field-seq field model args)))

(defn select-ids-seq
  [model & options]
  (let [pk-key (models/primary-key model)]
    (if-not (sequential? pk-key)
      (apply select-field-seq pk-key model options)
      (when-let [results (seq (apply select (into [model] pk-key) options))]
        (for [result results]
          (mapv result pk-key))))))

(defn select-ids-set
  "Select IDs for multiple objects. These are returned as a set if any matching IDs were returned, otherwise `nil`.

     (select-ids 'Table :db_id 1) -> #{1 2 3 4}"
  {:style/indent 1}
  [model & options]
  (seq (apply select-ids-seq model options)))

(defn select-field->field
  "Select fields `k` and `v` from objects in the database, and return them as a map from `k` to `v`.

     (select-field->field :id :name 'Database) -> {1 \"Sample Dataset\", 2 \"test-data\"}"
  {:style/indent 3}
  [k v model & options]
  {:pre [(keyword? k) (keyword? v)]}
  (into {} (for [result (apply select [model k v] options)]
             {(k result) (v result)})))

(defn select-field->id
  "Select `field` and `:id` from objects in the database, and return them as a map from `field` to `:id`.

     (select-field->id :name 'Database) -> {\"Sample Dataset\" 1, \"test-data\" 2}"
  {:style/indent 2}
  [field model & options]
  (let [pk-key (models/primary-key model)]
    (if-not (sequential? pk-key)
      (apply select-field->field field pk-key model options)
      (when-let [rows (seq (apply select (into [model field] pk-key) options))]
        (into {} (for [row rows]
                   [(get row field) (mapv row pk-key)]))))))

(defn select-id->field
  "Select `field` and `:id` from objects in the database, and return them as a map from `:id` to `field`.

     (select-id->field :name 'Database) -> {1 \"Sample Dataset\", 2 \"test-data\"}"
  {:style/indent 2}
  [field model & options]
  (let [pk-key (models/primary-key model)]
    (if-not (sequential? pk-key)
      (apply select-field->field pk-key field model options)
      (when-let [rows (seq (apply select (into [model field] pk-key) options))]
        (into {} (for [row rows]
                   [(mapv row pk-key) (get row field)]))))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                              Other query util fns                                              |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn count
  "Select the count of objects matching some condition.

     ;; Get all Users whose email is non-nil
     (count 'User :email [:not= nil]) -> 12"
  {:style/indent 1}
  [model & options]
  (:count (apply select-one [model [:%count.* :count]] options)))

(defn exists?
  "Easy way to see if something exists in the DB.

    (db/exists? User :id 100)"
  {:style/indent 1}
  ([object]
   (exists? object {:where (models/primary-key-where-clause object)}))

  ([model & options]
   (let [results (connection/query model (merge
                                          (compile/compile-select-options options)
                                          {:select [[true :exists]]
                                           :from   [(instance/model model)]
                                           :limit  1}))]
     (boolean (seq results)))))



;;;                                                      update!
;;; ==================================================================================================================

;; TODO - these fns need to call `debug`

(defn update!
  "Update a single row in the database. Returns updated object if a row was affected, `nil` otherwise. Accepts either an
  object, a single map of changes, or an id and keyword args. Various `pre-update` methods for the model are called
  before performing the updates, and `post-update` methods are called on the results.

     (db/update! (assoc (db/select-one Label 11) :name \"ToucanFriendly\"))
     (db/update! Label 11 :name \"ToucanFriendly\")
     (db/update! Label 11 {:name \"ToucanFriendly\"})"
  ([object]
   {:pre [(map? object)]}
   (update! object (models/primary-key-value object) (instance/changes object)))

  ([model pk-value changes]
   {:pre [(instance/model model) (map? changes)]}
   (when (seq changes)
     (let [pk-value (models/assoc-primary-key-value changes pk-value)
           object   (impl/do-pre-update (instance/of model pk-value))
           changes  (models/dissoc-primary-key-value object)]
       (when (seq changes)
         (connection/transaction
           (let [[num-rows-changed] (connection/execute! object {:update [object]
                                                                 :set    changes
                                                                 :where  (models/primary-key-where-clause object)})]
             (when (pos? num-rows-changed)
               (impl/do-post-update (select object)))))))))

  ([model id k v & more]
   (update! model id (into {k v} more))))

(defn update-where!
  "Convenience for updating several objects matching `conditions-map`. Selects objects matching `conditions` map, then
  calls `update!` on all matching objects sequentially.

     (db/update-where! Table {:name table-name
                              :db_id (:id database)}
       :active false)

  This function favors convenience over performance; for updating truly massive numbers of objects (where you don't
  care about calling the usual `pre-update` and `post-update` hooks) consider using `execute!` directly."
  {:style/indent 2}
  ([model conditions changes]
   {:pre [(instance/model model) (map? conditions) (map? changes)]}
   (when (seq changes)
     (when-let [matching-objects (seq (apply select model (mapcat identity {:a 1, :b 2})))]
       (connection/transaction
         (mapv update! (for [object matching-objects]
                         (merge object changes)))))))

  ([model conditions-map k v & more]
   (update-where! model conditions-map (into {k v} more))))


(defn update-non-nil-keys!
  "Like `update!`, but filters out key-value pairs with `nil` values."
  {:style/indent 2}
  ([model id kvs]
   (update! model id (into {} (for [[k v] kvs
                                     :when (not (nil? v))]
                                 [k v]))))
  ([model id k v & more]
   (update-non-nil-keys! model id (apply array-map k v more))))


;;;                                                      insert!
;;; ==================================================================================================================

(def ^:private ^:deprecated inserted-id-keys
  "Different possible keys that might come back for the ID of a newly inserted row. Differs by DB."
  [;; Postgres, newer H2, and most others return :id
   :id
   ;; :generated_key is returned by MySQL
   :generated_key
   ;; MariaDB returns :insert_id
   :insert_id
   ;; scope_identity() returned by older versions of H2
   (keyword "scope_identity()")
   ;; last_insert_rowid() returned by SQLite3
   (keyword "last_insert_rowid()")])

(defn ^:deprecated get-inserted-id
  "Get the ID of a row inserted by `jdbc/db-do-prepared-return-keys`."
  [primary-key insert-result]
  (when insert-result
    (some insert-result (cons primary-key inserted-id-keys))))

(defn ^:deprecated simple-insert-many!
  "Do a simple JDBC `insert!` of multiple objects into the database.
  Normally you should use `insert-many!` instead, which calls the model's `pre-insert` method on the `row-maps`;
  `simple-insert-many!` is offered for cases where you'd like to specifically avoid this behavior. Returns a sequences
  of IDs of newly inserted objects.

     (db/simple-insert-many! 'Label [{:name \"Toucan Friendly\"}
                                     {:name \"Bird Approved\"}]) ;;=> (38 39)"
  {:style/indent 1}
  [model row-maps]
  {:pre [(sequential? row-maps) (every? map? row-maps)]}
  (when (seq row-maps)
    (let [primary-key (models/primary-key model)]
      (doall
       (for [row-map row-maps
             :let    [sql (compile/compile-honeysql {:insert-into model, :values [row-map]})]]
         (->> (jdbc/db-do-prepared-return-keys (connection/connection) false sql {}) ; false = don't use a transaction
              (get-inserted-id primary-key)))))))

(defn insert-many!
  "Insert several new rows into the Database. Resolves `entity`, and calls `pre-insert` on each of the `row-maps`.
  Returns a sequence of the IDs of the newly created objects.

  Note: this *does not* call `post-insert` on newly created objects. If you need `post-insert` behavior, use
  `insert!` instead. (This might change in the future: there is an [open issue to consider
  this](https://github.com/metabase/toucan/issues/4)).

     (db/insert-many! 'Label [{:name \"Toucan Friendly\"}
                              {:name \"Bird Approved\"}]) -> [38 39]"
  {:style/indent 1}
  [model row-maps]
  (simple-insert-many! model (for [row-map row-maps]
                               (impl/do-pre-insert model row-map))))

(defn ^:deprecated simple-insert!
  "Do a simple JDBC `insert` of a single object.
  This is similar to `insert!` but returns the ID of the newly created object rather than the object itself,
  and does not call `pre-insert` or `post-insert`.

     (db/simple-insert! 'Label :name \"Toucan Friendly\") -> 1

  Like `insert!`, `simple-insert!` can be called with either a single `row-map` or kv-style arguments."
  {:style/indent 1}
  ([model row-map]
   {:pre [(map? row-map) (every? keyword? (keys row-map))]}
   (first (simple-insert-many! model [row-map])))

  ([model k v & more]
   (simple-insert! model (apply array-map k v more))))

;; TODO
(defn insert!
  "Insert a new object into the Database. Resolves `entity`, calls its `pre-insert` method on `row-map` to prepare
  it before insertion; after insert, it fetches and the newly created object, passes it to `post-insert`, and
  returns the results.

  For flexibility, `insert!` can handle either a single map or individual kwargs:

     (db/insert! Label {:name \"Toucan Unfriendly\"})
     (db/insert! 'Label :name \"Toucan Friendly\")"
  {:style/indent 1}
  ([model row-map]
   {:pre [(map? row-map) (every? keyword? (keys row-map))]}
   (when-let [id (simple-insert! model (impl/do-pre-insert model row-map))]
     (models/post-insert (model id))))

  ([model k v & more]
   (insert! model (into {k v} more))))


;;;                                                      delete!
;;; ==================================================================================================================

;; TODO
(defn ^:deprecated simple-delete!
  "Delete an object or objects from the application DB matching certain constraints.
  Returns `true` if something was deleted, `false` otherwise.

     (db/simple-delete! 'Label)                ; delete all Labels
     (db/simple-delete! Label :name \"Cam\")   ; delete labels where :name == \"Cam\"
     (db/simple-delete! Label {:name \"Cam\"}) ; for flexibility either a single map or kwargs are accepted

  Unlike `delete!`, this does not call `pre-delete` on the object about to be deleted."
  {:style/indent 1}
  ([model]
   (simple-delete! model {}))

  ([model & conditions]
   {:pre [(map? conditions) (every? keyword? (keys conditions))]}
   (not= [0] (connection/execute!
              (merge
               {:delete-from [(instance/model model)]}
               (compile/compile-select-options conditions))))))

;; TODO
(defn delete!
  "Delete of object(s). For each matching object, the `pre-delete` multimethod is called, which should do
  any cleanup needed before deleting the object, (such as deleting objects related to the object about to
  be deleted), or otherwise enforce preconditions before deleting (such as refusing to delete the object if
  something else depends on it).

     (delete! Database :id 1)"
  {:style/indent 1}
  [model & conditions]
  (let [primary-key (models/primary-key model)]
    (doseq [object (apply select model conditions)]
      (models/pre-delete object)
      (simple-delete! model primary-key (primary-key object)))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                     save!                                                      |
;;; +----------------------------------------------------------------------------------------------------------------+

;; TODO

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                     copy!                                                      |
;;; +----------------------------------------------------------------------------------------------------------------+

;; TODO

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                      Etc                                                       |
;;; +----------------------------------------------------------------------------------------------------------------+

;; TODO
#_(defn- invoke-model
  "Fetch an object with a specific ID or all objects of type ENTITY from the DB.

     (invoke-model Database)           -> seq of all databases
     (invoke-model Database 1)         -> Database w/ ID 1
     (invoke-model Database :id 1 ...) -> A single Database matching some key-value args"
  ([model]
   ((resolve 'toucan.db/select) model))

  ([model id]
   (when id
     (invoke-model model (primary-key model) id)))

  ([model k v & more]
   (apply (resolve 'toucan.db/select-one) model k v more)))

(potemkin/import-vars
 [connection connection transaction query reducible-query])

;; TODO - `instance-of` <-> `instance/of`

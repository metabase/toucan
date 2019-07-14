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
            [toucan.db.impl :as impl]
            [toucan.util :as u]))

;; TODO - we shouldn't reference `jdbc` here, everything should be in `connection` instead

(def ^:dynamic *behavior* :default)
;;;                                                       select
;;; ==================================================================================================================xf

(defn select
  ([model-or-object]
   (if-let [pk-value (models/primary-key-value model-or-object)]
     (select model-or-object pk-value)
     (select model-or-object {})))

  ([model & args]
   (let [query*        (partial connection/query model)
         honeysql-form (apply compile/compile-select model args)]
     (seq (impl/do-select *behavior* model query* honeysql-form)))))

(defn select-reducible
  ([object]
   (select object (models/primary-key-value object)))

  ([model & args]
   (let [query*        (partial connection/reducible-query model)
         honeysql-form (apply compile/compile-select model args)]
     (impl/do-select *behavior* model query* honeysql-form))))

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
                                           :from   [(instance/table model)]
                                           :limit  1}))]
     (boolean (seq results)))))



;;;                                                      update!
;;; ==================================================================================================================

(defn- update!* [[instance :as instances]]
  (let [where-clauses (for [instance instances
                            :let     [changes (instance/changes instance)]
                            :when    (seq changes)]
                        (let [honeysql-form {:update [(instance/table instance)]
                                             :set    changes
                                             :where  (models/primary-key-where-clause instance)}]
                          (when (connection/execute! instance honeysql-form)
                            (models/primary-key-where-clause instance))))]
    ;; return updated objects
    ;; TODO - seems inefficient
    (select instance {:where (into [:or] where-clauses)})))

(defn update!
  "Update a single row in the database. Returns updated object if a row was affected, `nil` otherwise. Accepts either an
  object, a single map of changes, or an id and keyword args. Various `pre-update` methods for the model are called
  before performing the updates, and `post-update` methods are called on the results.

     (db/update! (assoc (db/select-one Label 11) :name \"ToucanFriendly\"))
     (db/update! Label 11 :name \"ToucanFriendly\")
     (db/update! Label 11 {:name \"ToucanFriendly\"})"
  ([instance-or-instances]
   (when-let [instances (seq (u/sequencify instance-or-instances))]
     (impl/do-update! *behavior* update!* instances)))

  ([model pk-value changes]
   (when (seq changes)
     (update! (instance/toucan-instance
               model
               (models/assoc-primary-key-value (instance/of model) pk-value)
               (models/dissoc-primary-key-value (instance/of model changes))
               nil))))

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
     (when-let [matching-objects (seq (apply select model conditions))]
       (update! (for [object matching-objects]
                  (merge object changes))))))

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

#_(def ^:private ^:deprecated inserted-id-keys
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

#_(defn ^:deprecated get-inserted-id
  "Get the ID of a row inserted by `jdbc/db-do-prepared-return-keys`."
  [primary-key insert-result]
  (when insert-result
    (some insert-result (cons primary-key inserted-id-keys))))

(defn- insert!* [[instance :as instances]]
  (let [honeysql-form {:insert-into (instance/table instance)
                       :values      instances}
        result-rows   (connection/insert! instance honeysql-form)]
    (map
     (fn [instance result-row]
       (let [pk-value (models/primary-key-value (instance/of instance result-row))]
         (models/assoc-primary-key-value instance pk-value)))
     instances
     result-rows)))

(defn insert!
  "Insert a new object into the Database. Resolves `entity`, calls its `pre-insert` method on `row-map` to prepare
  it before insertion; after insert, it fetches and the newly created object, passes it to `post-insert`, and
  returns the results.

  For flexibility, `insert!` can handle either a single map or individual kwargs:

     (db/insert! Label {:name \"Toucan Unfriendly\"})
     (db/insert! 'Label :name \"Toucan Friendly\")"
  {:style/indent 1} ; TODO - not sure if want
  ([instance-or-instances]
   (if (sequential? instance-or-instances)
     (impl/do-insert! *behavior* insert!* instance-or-instances)
     (first (insert! [instance-or-instances]))))

  ([model row-or-rows]
   (insert! (if (sequential? row-or-rows)
              (for [row row-or-rows]
                (instance/of model row))
              (instance/of model row-or-rows))))

  ([model k v & more]
   (insert! model (into {k v} more))))


;;;                                                      delete!
;;; ==================================================================================================================

(defn delete!* [[instance :as instances]]
  (connection/delete! instance {:delete-from [(instance/table instance)]
                                :where       (into [:or] (map models/primary-key-where-clause instances))}))

;; TODO
(defn delete!
  "Delete of object(s). For each matching object, the `pre-delete` multimethod is called, which should do
  any cleanup needed before deleting the object, (such as deleting objects related to the object about to
  be deleted), or otherwise enforce preconditions before deleting (such as refusing to delete the object if
  something else depends on it).

     (delete! Database :id 1)"
  {:style/indent 1}
  ([instance-or-instances]
   (let [[instance :as instances] (u/sequencify instance-or-instances)]
     (when (seq instances))
     (impl/do-delete! *behavior* delete!* instances)))

  ([model & conditions]
   (delete! (apply select model conditions))))


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



(potemkin/import-vars
 [impl]
 [connection
  connection
  transaction
  query
  reducible-query
  execute!
  with-call-counting
  debug-count-calls])

;; TODO - `instance-of` <-> `instance/of`

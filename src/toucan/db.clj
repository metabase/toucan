(ns toucan.db
  "Helper functions for querying the DB and inserting or updating records using Toucan models."
  (:refer-clojure :exclude [count])
  (:require [clojure
             [pprint :refer [pprint]]
             [string :as s]
             [walk :as walk]]
            [clojure.java.jdbc :as jdbc]
            [clojure.tools.logging :as log]
            [honeysql
             [core :as hsql]
             [format :as hformat]
             [helpers :as h]]
            [toucan
             [compile :as compile]
             [instance :as instance]
             [dispatch :as dispatch]
             [models :as models]
             [util :as u]]
            [toucan.db.impl :as impl]
            [toucan.connection :as connection]))

;; NOCOMMIT
(doseq [[symb] (ns-interns *ns*)]
  (ns-unmap *ns* symb))

;; TODO `save!` (?) `copy!` (?) Or do

;; TODO - separate debug namespace ??

(defmacro with-call-counting
  "Execute `body` and track the number of DB calls made inside it. `call-count-fn-binding` is bound to a zero-arity
  function that can be used to fetch the current DB call count.

     (db/with-call-counting [call-count] ...
       (call-count))"
  {:style/indent 1}
  [[call-count-fn-binding] & body]
  `(impl/-do-with-call-counting (fn [~call-count-fn-binding] ~@body)))

(defmacro debug-count-calls
  "Print the number of DB calls executed inside `body` to `stdout`. Intended for use during REPL development."
  {:style/indent 0}
  [& body]
  `(with-call-counting [call-count#]
     (let [results# (do ~@body)]
       (println "DB Calls:" (call-count#))
       results#)))

(defmacro debug
  "Print the HoneySQL and SQL forms of any queries executed inside `body` to `stdout`. Intended for use during REPL
  development."
  {:style/indent 0}
  [& body]
  `(binding [impl/*debug* true]
     ~@body))

(defn select
  ;; TODO
  [model & args]
  (let [model          (instance/model model)
        honeysql-form  (impl/do-pre-select model (compile/compile-select-args model args))
        do-post-select (impl/post-select-fn model)]
    (map
     #(do-post-select (instance/of model %))
     (connection/query model honeysql-form))))

(defn select-reducible
  ;; TODO
  [model & args]
  (let [model         (instance/model model)
        honeysql-form (impl/do-pre-select model (compile/compile-select-args model args))]
    (eduction
     (comp (map (partial instance/of model))
           (map (impl/post-select-fn model)))
     (connection/reducible-query model honeysql-form))))



;;; ### UPDATE!

(defn update!
  "Update a single row in the database. Returns `true` if a row was affected, `false` otherwise. Accepts either a
  single map of updates to make or kwargs. `entity` is automatically resolved, and `pre-update` is called on `kvs`
  before the object is inserted into the database.

     (db/update! 'Label 11 :name \"ToucanFriendly\")
     (db/update! 'Label 11 {:name \"ToucanFriendly\"})"
  {:style/indent 2}

  (^Boolean [model honeysql-form]
   (not= [0] (connection/execute! model (merge (h/update model) honeysql-form))))

  (^Boolean [model id kvs]
   {:pre [(some? id) (map? kvs) (every? keyword? (keys kvs))]}
   (let [primary-key (models/primary-key model)
         kvs         (-> (impl/do-pre-update model (assoc kvs primary-key id))
                         (dissoc primary-key))
         updated?    (update! model (-> (h/sset {} kvs)
                                        (compile/where primary-key id)))]
     (impl/do-post-update instance)))

  (^Boolean [model id k v & more]
   (update! model id (apply array-map k v more))))

(defn update-where!
  "Convenience for updating several objects matching `conditions-map`. Returns `true` if any objects were affected.
  For updating a single object, prefer using `update!`, which calls `entity`'s `pre-update` method first.

     (db/update-where! Table {:name  table-name
                              :db_id (:id database)}
       :active false)"
  {:style/indent 2}
  ^Boolean [model conditions-map & {:as values}]
  {:pre [(map? conditions-map) (every? keyword? (keys values))]}
  (update! model (where {:set values} conditions-map)))


(defn update-non-nil-keys!
  "Like `update!`, but filters out KVS with `nil` values."
  {:style/indent 2}
  ([model id kvs]
   (update! model id (into {} (for [[k v] kvs
                                     :when (not (nil? v))]
                                 [k v]))))
  ([model id k v & more]
   (update-non-nil-keys! model id (apply array-map k v more))))


;;; ### INSERT!

(def ^:private inserted-id-keys
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

(defn get-inserted-id
  "Get the ID of a row inserted by `jdbc/db-do-prepared-return-keys`."
  [primary-key insert-result]
  (when insert-result
    (some insert-result (cons primary-key inserted-id-keys))))

(defn simple-insert-many!
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
    (let [model       (resolve-model model)
          primary-key (models/primary-key model)]
      (doall
       (for [row-map row-maps
             :let    [sql (compile-honeysql {:insert-into model, :values [row-map]})]]
         (->> (jdbc/db-do-prepared-return-keys (connection) false sql {}) ; false = don't use a transaction
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
  (let [model (resolve-model model)]
    (simple-insert-many! model (for [row-map row-maps]
                                  (models/do-pre-insert model row-map)))))

(defn simple-insert!
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
   (let [model (resolve-model model)]
     (when-let [id (simple-insert! model (models/do-pre-insert model row-map))]
       (models/post-insert (model id)))))
  ([model k v & more]
   (insert! model (apply array-map k v more))))

;;; ### SELECT

;; All of the following functions are based off of the old `sel` macro and can do things like select
;; certain fields by wrapping ENTITY in a vector and automatically convert kv-args to a `where` clause

(defn select-one
  "Select a single object from the database.

     (select-one ['Database :name] :id 1) -> {:name \"Sample Dataset\"}"
  {:style/indent 1}
  [model & options]
  (let [fields (model->fields model)]
    (simple-select-one model (where+ {:select (or fields [:*])} options))))

(defn select-one-field
  "Select a single `field` of a single object from the database.

     (select-one-field :name 'Database :id 1) -> \"Sample Dataset\""
  {:style/indent 2}
  [field model & options]
  {:pre [(keyword? field)]}
  (field (apply select-one [model field] options)))

(defn select-one-id
  "Select the `:id` of a single object from the database.

     (select-one-id 'Database :name \"Sample Dataset\") -> 1"
  {:style/indent 1}
  [model & options]
  (let [model (resolve-model model)]
    (apply select-one-field (models/primary-key model) model options)))

(defn count
  "Select the count of objects matching some condition.

     ;; Get all Users whose email is non-nil
     (count 'User :email [:not= nil]) -> 12"
  {:style/indent 1}
  [model & options]
  (:count (apply select-one [model [:%count.* :count]] options)))

(defn select
  "Select objects from the database.

     (select 'Database :name [:not= nil] {:limit 2}) -> [...]"
  {:style/indent 1}
  [model & options]
  (simple-select model (where+ {:select (or (model->fields model)
                                            [:*])}
                               options)))

(defn select-reducible
  "Select objects from the database, returns a reducible.

     (transduce (map :name) conj [] (select 'Database :name [:not= nil] {:limit 2}))
        -> [\"name1\", \"name2\"]"
  {:style/indent 1}
  [model & options]
  (simple-select-reducible model (where+ {:select (or (model->fields model)
                                                      [:*])}
                                         options)))

(defn select-field
  "Select values of a single field for multiple objects. These are returned as a set if any matching fields
   were returned, otherwise `nil`.

     (select-field :name 'Database) -> #{\"Sample Dataset\", \"test-data\"}"
  {:style/indent 2}
  [field model & options]
  {:pre [(keyword? field)]}
  (when-let [results (seq (map field (apply select [model field] options)))]
    (set results)))

(defn select-ids
  "Select IDs for multiple objects. These are returned as a set if any matching IDs were returned, otherwise `nil`.

     (select-ids 'Table :db_id 1) -> #{1 2 3 4}"
  {:style/indent 1}
  [model & options]
  (let [model (resolve-model model)]
    (apply select-field (models/primary-key model) model options)))

(defn select-field->field
  "Select fields `k` and `v` from objects in the database, and return them as a map from `k` to `v`.

     (select-field->field :id :name 'Database) -> {1 \"Sample Dataset\", 2 \"test-data\"}"
  {:style/indent 3}
  [k v model & options]
  {:pre [(keyword? k) (keyword? v)]}
  (into {} (for [result (apply select [model k v] options)]
             {(k result) (v result)})))

(defn select-field->id
  "Select FIELD and `:id` from objects in the database, and return them as a map from `field` to `:id`.

     (select-field->id :name 'Database) -> {\"Sample Dataset\" 1, \"test-data\" 2}"
  {:style/indent 2}
  [field model & options]
  (let [model (resolve-model model)]
    (apply select-field->field field (models/primary-key model) model options)))

(defn select-id->field
  "Select `field` and `:id` from objects in the database, and return them as a map from `:id` to `field`.

     (select-id->field :name 'Database) -> {1 \"Sample Dataset\", 2 \"test-data\"}"
  {:style/indent 2}
  [field model & options]
  (let [model (resolve-model model)]
    (apply select-field->field (models/primary-key model) field model options)))

;;; ### EXISTS?

(defn exists?
  "Easy way to see if something exists in the DB.

    (db/exists? User :id 100)"
  {:style/indent 1}
  ^Boolean [model & kvs]
  (let [model (resolve-model model)]
    (boolean (select-one-id model (apply where (h/select {} (models/primary-key model)) kvs)))))

;;; ### DELETE!

(defn simple-delete!
  "Delete an object or objects from the application DB matching certain constraints.
  Returns `true` if something was deleted, `false` otherwise.

     (db/simple-delete! 'Label)                ; delete all Labels
     (db/simple-delete! Label :name \"Cam\")   ; delete labels where :name == \"Cam\"
     (db/simple-delete! Label {:name \"Cam\"}) ; for flexibility either a single map or kwargs are accepted

  Unlike `delete!`, this does not call `pre-delete` on the object about to be deleted."
  {:style/indent 1}
  ([model]
   (simple-delete! model {}))
  ([model conditions]
   {:pre [(map? conditions) (every? keyword? (keys conditions))]}
   (let [model (resolve-model model)]
     (not= [0] (execute! (-> (h/delete-from model)
                             (where conditions))))))
  ([model k v & more]
   (simple-delete! model (apply array-map k v more))))

(defn delete!
  "Delete of object(s). For each matching object, the `pre-delete` multimethod is called, which should do
  any cleanup needed before deleting the object, (such as deleting objects related to the object about to
  be deleted), or otherwise enforce preconditions before deleting (such as refusing to delete the object if
  something else depends on it).

     (delete! Database :id 1)"
  {:style/indent 1}
  [model & conditions]
  (let [model       (resolve-model model)
        primary-key (models/primary-key model)]
    (doseq [object (apply select model conditions)]
      (models/pre-delete object)
      (simple-delete! model primary-key (primary-key object)))))

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

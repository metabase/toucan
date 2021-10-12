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
             [models :as models]
             [util :as u]]))

;;;                                                   CONFIGURATION
;;; ==================================================================================================================

;;; #### Quoting Style

;; The quoting style is the HoneySQL quoting style that should be used to quote identifiers. By default, this is
;; `:ansi`, which wraps identifers in double-quotes. Alternatively, you can specify `:mysql` (backticks), or
;; `:sqlserver` (square brackets)

(defonce ^:private default-quoting-style (atom :ansi))

(def ^:dynamic *quoting-style*
  "Bind this to override the identifier quoting style. Provided for cases where you want to override the quoting
  style (such as when connecting to a different DB) without changing the default value."
  nil)

(defn set-default-quoting-style!
  "Set the default quoting style that should be used to quote identifiers. Defaults to `:ansi`, but you can instead
  set it to `:mysql` or `:sqlserver`."
  [new-quoting-style]
  (reset! default-quoting-style new-quoting-style))

(defn quoting-style
  "Fetch the HoneySQL quoting style that should be used to quote identifiers. One of `:ansi`, `:mysql`, or
  `:sqlserver`.

  Returns the value of `*quoting-style*` if it is bound, otherwise returns the default quoting style, which is
  normally `:ansi`; this can be changed by calling `set-default-quoting-style!`."
  ^clojure.lang.Keyword []
  (or *quoting-style*
      @default-quoting-style))

;;; ### Additional HoneySQL options

;;; #### Automatically Convert Dashes & Underscores

;; Convert dashes to underscores in queries going into the DB, and underscores in results back to dashes coming out of
;; the DB. By default, this is disabled. See the documentation in `setup.md` for more details.

(defonce ^:private default-automatically-convert-dashes-and-underscores (atom false))

(def ^:dynamic *automatically-convert-dashes-and-underscores*
  "Bind this to enable automatic conversion between dashes and underscores for indentifiers. Provided for cases where
  you want to override the behavior (such as when connecting to a different DB) without changing the default value."
  nil)

(defn set-default-automatically-convert-dashes-and-underscores!
  "Set the default value for allowing dashes in field names. Defaults to `true`."
  [^Boolean new-automatically-convert-dashes-and-underscores]
  (reset! default-automatically-convert-dashes-and-underscores new-automatically-convert-dashes-and-underscores))

(defn automatically-convert-dashes-and-underscores?
  "Deterimine whether we should automatically convert dashes and underscores in identifiers.

  Returns the value of `*automatically-convert-dashes-and-underscores*` if it is bound, otherwise returns the
  `default-automatically-convert-dashes-and-underscores`, which is normally `false`; this can be changed by calling
  `set-default-automatically-convert-dashes-and-underscores!`."
  ^Boolean []
  (if (nil? *automatically-convert-dashes-and-underscores*)
    @default-automatically-convert-dashes-and-underscores
    *automatically-convert-dashes-and-underscores*))

;;; #### DB Connection

;; The default DB connection is used automatically when accessing the DB; it can be anything that would normally be
;; passed to `clojure.java.jdbc` functions -- normally a connection details map, but alternatively something like
;; a C3PO connection pool.

(defonce ^:private default-db-connection (atom nil))

(def ^:dynamic *db-connection*
  "Bind this to override the default DB connection used by `toucan.db` functions. Provided for situations where you'd
  like to connect to a DB other than the primary application DB, or connect to it with different connection options."
  nil)

(defn set-default-db-connection!
  "Set the JDBC connecton details map for the default application DB connection. This connection is used by default by
  the various `toucan.db` functions.

   `db-connection-map` is passed directly to `clojure.java.jdbc`; it can be anything that is accepted by it.

     (db/set-default-db-connection!
       {:classname   \"org.postgresql.Driver\"
        :subprotocol \"postgresql\"
        :subname     \"//localhost:5432/my_db\"
        :user        \"cam\"})"
  {:style/indent 0}
  [db-connection-map]
  (reset! default-db-connection db-connection-map))

(defonce ^:private default-default-jdbc-options
  ;; FIXME: This has already been fixed in `clojure.java.jdbc`, so
  ;;        this option can be removed when using >= 0.7.10.
  {:identifiers u/lower-case})

(defonce ^:private default-jdbc-options
  (atom default-default-jdbc-options))

(defn set-default-jdbc-options!
  "Set the default options to be used for all calls to `clojure.java.jdbc/query` or `execute!`."
  [jdbc-options]
  (reset! default-jdbc-options (merge default-default-jdbc-options jdbc-options)))



;;;                                         TRANSACTION & CONNECTION UTIL FNS
;;; ==================================================================================================================

(def ^:dynamic *transaction-connection*
  "Transaction connection to the application DB. Used internally by `transaction`."
  nil)

(defn connection
  "Fetch the JDBC connection details for passing to `clojure.java.jdbc`. Returns `*db-connection*`, if it is set;
  otherwise `*transaction-connection*`, if we're inside a `transaction` (this is bound automatically); otherwise the
  default DB connection, set by `set-default-db-connection!`.

  If no DB connection has been set this function will throw an exception."
  []
  (or *db-connection*
      *transaction-connection*
      @default-db-connection
      (throw (Exception. "DB is not set up. Make sure to call set-default-db-connection! or bind *db-connection*."))))

(defn do-in-transaction
  "Execute F inside a DB transaction. Prefer macro form `transaction` to using this directly."
  [f]
  (jdbc/with-db-transaction [conn (connection)]
    (binding [*transaction-connection* conn]
      (f))))

(defmacro transaction
  "Execute all queries within the body in a single transaction."
  {:arglists '([body] [options & body]), :style/indent 0}
  [& body]
  `(do-in-transaction (fn [] ~@body)))


;;;                                                   QUERY UTIL FNS
;;; ==================================================================================================================

(def ^:dynamic ^Boolean *disable-db-logging*
  "Should we disable logging for database queries? Normally `false`, but bind this to `true` to keep logging from
  getting too noisy during operations that require a lot of DB access, like the sync process."
  false)

(defn- model-symb->ns
  "Return the namespace symbol where we'd expect to find an model symbol.

     (model-symb->ns 'CardFavorite) -> 'my-project.models.card-favorite"
  [symb]
  {:pre [(symbol? symb)]}
  (symbol (str (models/root-namespace) \. (u/lower-case (s/replace (name symb) #"([a-z])([A-Z])" "$1-$2")))))

(defn- resolve-model-from-symbol
  "Resolve the model associated with SYMB, calling `require` on its namespace if needed.

     (resolve-model-from-symbol 'CardFavorite) -> my-project.models.card-favorite/CardFavorite"
  [symb]
  (let [model-ns (model-symb->ns symb)]
    @(try (ns-resolve model-ns symb)
          (catch Throwable _
            (require model-ns)
            (ns-resolve model-ns symb)))))

(defn resolve-model
  "Resolve a model *if* it's quoted. This also unwraps entities when they're inside vectores.

     (resolve-model Database)         -> #'my-project.models.database/Database
     (resolve-model [Database :name]) -> #'my-project.models.database/Database
     (resolve-model 'Database)        -> #'my-project.models.database/Database"
  [model]
  {:post [(:toucan.models/model %)]}
  (cond
    (:toucan.models/model model) model
    (vector? model)              (resolve-model (first model))
    (symbol? model)              (resolve-model-from-symbol model)
    :else                        (throw (Exception. (str "Invalid model: " model)))))

(defn quote-fn
  "The function that JDBC should use to quote identifiers for our database. This is passed as the `:entities` option
  to functions like `jdbc/insert!`."
  []
  ((quoting-style) @(resolve 'honeysql.format/quote-fns))) ; have to call resolve because it's not public


(def ^:private ^:dynamic *call-count*
  "Atom used as a counter for DB calls when enabled. This number isn't *perfectly* accurate, only mostly; DB calls
  made directly to JDBC won't be logged."
  nil)

(defn -do-with-call-counting
  "Execute F with DB call counting enabled. F is passed a single argument, a function that can be used to retrieve the
  current call count. (It's probably more useful to use the macro form of this function, `with-call-counting`,
  instead.)"
  {:style/indent 0}
  [f]
  (binding [*call-count* (atom 0)]
    (f (partial deref *call-count*))))

(defmacro with-call-counting
  "Execute `body` and track the number of DB calls made inside it. `call-count-fn-binding` is bound to a zero-arity
  function that can be used to fetch the current DB call count.

     (db/with-call-counting [call-count] ...
       (call-count))"
  {:style/indent 1}
  [[call-count-fn-binding] & body]
  `(-do-with-call-counting (fn [~call-count-fn-binding] ~@body)))

(defmacro debug-count-calls
  "Print the number of DB calls executed inside `body` to `stdout`. Intended for use during REPL development."
  {:style/indent 0}
  [& body]
  `(with-call-counting [call-count#]
     (let [results# (do ~@body)]
       (println "DB Calls:" (call-count#))
       results#)))


(defn- format-sql [sql]
  (when sql
    (loop [sql sql, [k & more] ["FROM" "LEFT JOIN" "INNER JOIN" "WHERE" "GROUP BY" "HAVING" "ORDER BY" "OFFSET"
                                "LIMIT"]]
      (if-not k
        sql
        (recur (s/replace sql (re-pattern (format "\\s+%s\\s+" k)) (format "\n%s " k))
               more)))))

(def ^:dynamic ^:private *debug-print-queries* false)

(defn -do-with-debug-print-queries
  "Execute `f` with debug query logging enabled. Don't use this directly; prefer the `debug-print-queries` macro form
  instead."
  [f]
  (binding [*debug-print-queries* true]
    (f)))

(defmacro debug-print-queries
  "Print the HoneySQL and SQL forms of any queries executed inside `body` to `stdout`. Intended for use during REPL
  development."
  {:style/indent 0}
  [& body]
  `(-do-with-debug-print-queries (fn [] ~@body)))

(defn honeysql->sql
  "Compile `honeysql-form` to SQL.
  This returns a vector with the SQL string as its first item and prepared statement params as the remaining items."
  [honeysql-form]
  {:pre [(map? honeysql-form)]}
  ;; Not sure *why* but without setting this binding on *rare* occasion HoneySQL will unwantedly
  ;; generate SQL for a subquery and wrap the query in parens like "(UPDATE ...)" which is invalid
  (let [[sql & args :as sql+args]
        (binding [hformat/*subquery?* false]
          (hsql/format honeysql-form
                       :quoting             (quoting-style)
                       :allow-dashed-names? (not (automatically-convert-dashes-and-underscores?))))]
    (when *debug-print-queries*
      (println (pprint honeysql-form)
               (format "\n%s\n%s" (format-sql sql) args)))
    (when-not *disable-db-logging*
      (log/debug (str "DB Call: " sql))
      (when *call-count*
        (swap! *call-count* inc)))
    sql+args))

(defn query
  "Compile `honeysql-from` and call `jdbc/query` against the application database. Options are passed along to
  `jdbc/query`."
  [honeysql-form & {:as options}]
  (jdbc/query (connection)
              (honeysql->sql honeysql-form)
              (merge @default-jdbc-options options)))

(defn reducible-query
  "Compile `honeysql-from` and call `jdbc/reducible-query` against the application database. Options are passed along
  to `jdbc/reducible-query`. Note that the query won't actually be executed until it's reduced."
  [honeysql-form & {:as options}]
  (jdbc/reducible-query (connection) (honeysql->sql honeysql-form) (merge @default-jdbc-options options)))


(defn qualify
  "Qualify a `field-name` name with the name its `entity`. This is necessary for disambiguating fields for HoneySQL
  queries that contain joins.

     (db/qualify 'CardFavorite :id) -> :report_cardfavorite.id"
  ^clojure.lang.Keyword [model field-name]
  (if (vector? field-name)
    [(qualify model (first field-name)) (second field-name)]
    (hsql/qualify (:table (resolve-model model)) field-name)))

(defn qualified?
  "Is `field-name` qualified (e.g. with its table name)?"
  ^Boolean [field-name]
  (if (vector? field-name)
    (qualified? (first field-name))
    (boolean (re-find #"\." (name field-name)))))

(defn- maybe-qualify
  "Qualify `field-name` with its table name if it's not already qualified."
  ^clojure.lang.Keyword [model field-name]
  (if (qualified? field-name)
    field-name
    (qualify model field-name)))


(defn- model->fields
  "Get the fields that should be used in a query, destructuring `entity` if it's wrapped in a vector, otherwise
   calling `default-fields`. This will return `nil` if the model isn't wrapped in a vector and uses the default
   implementation of `default-fields`.

     (model->fields 'User) -> [:id :email :date_joined :first-name :last-name :last_login :is_superuser :is_qbnewb]
     (model->fields ['User :first-name :last-name]) -> [:first-name :last-name]
     (model->fields 'Database) -> nil"
  [model]
  (if (vector? model)
    (let [[model & fields] model]
      (for [field fields]
        (maybe-qualify model field)))
    (models/default-fields (resolve-model model))))

(defn- replace-underscores
  "Replace underscores in `k` with dashes. In other words, converts a keyword from `:snake_case` to `:lisp-case`.

     (replace-underscores :2_cans) ; -> :2-cans"
  ^clojure.lang.Keyword [k]
  ;; if k is not a string or keyword don't transform it
  (if-not ((some-fn string? keyword?) k)
    k
    (let [k-str (u/keyword->qualified-name k)]
      (if (s/index-of k-str \_)
        (keyword (s/replace k-str \_ \-))
        k))))

(defn- transform-keys
  "Replace the keys in any maps in `x` with the result of `(f key)`. Recursively walks `x` using `clojure.walk`."
  [f x]
  (walk/postwalk
   (fn [y]
     (if-not (map? y)
       y
       (into {} (for [[k v] y]
                  [(f k) v]))))
   x))

(defn do-post-select
  "Perform post-processing for objects fetched from the DB. Convert results `objects` to `entity` record types and
  call the model's `post-select` method on them."
  {:style/indent 1}
  [model objects]
  (let [model            (resolve-model model)
        key-transform-fn (if-not (automatically-convert-dashes-and-underscores?)
                           identity
                           (partial transform-keys replace-underscores))]
    (vec (for [object objects]
           (models/do-post-select model (key-transform-fn object))))))

(defn- merge-select-and-from
  "Includes projected fields and a from clause for `honeysql-form`. Will not override if already present."
  [resolved-model honeysql-form]
  (merge {:select (or (models/default-fields resolved-model)
                      [:*])
          :from   [resolved-model]}
         honeysql-form))

(defn simple-select
  "Select objects from the database.

  Like `select`, but doesn't offer as many conveniences, so prefer that instead; like `select`,
  `simple-select` callts `post-select` on the results, but unlike `select`, only accepts a single
  raw HoneySQL form as an argument.

    (db/simple-select 'User {:where [:= :id 1]})"
  {:style/indent 1}
  [model honeysql-form]
  (let [model (resolve-model model)]
    (do-post-select model (query (merge-select-and-from model honeysql-form)))))

(defn simple-select-reducible
  "Select objects from the database.

  Same as `simple-select`, but returns something reducible instead of a result set. Like `simple-select`, will call
  `post-select` on the results, but will do so lazily.

    (transduce (filter can-read?) conj [] (simple-select-reducible 'User {:where [:= :id 1]}))"
  {:style/indent 1}
  [model honeysql-form]
  (let [model (resolve-model model)]
    (eduction (map #(models/do-post-select model %))
              (reducible-query (merge-select-and-from model honeysql-form)))))

(defn simple-select-one
  "Select a single object from the database.

  Like `select-one`, but doesn't offer as many conveniences, so prefer that instead; like `select-one`,
  `simple-select-one` callts `post-select` on the results, but unlike `select-one`, only accepts a single raw HoneySQL
  form as an argument.

    (db/simple-select-one 'User (h/where [:= :first-name \"Cam\"]))"
  ([model]
   (simple-select-one model {}))
  ([model honeysql-form]
   (first (simple-select model (h/limit honeysql-form (hsql/inline 1))))))

(defn execute!
  "Compile `honeysql-form` and call `jdbc/execute!` against the application DB.
  `options` are passed directly to `jdbc/execute!` and can be things like `:multi?` (default `false`) or
  `:transaction?` (default `true`)."
  [honeysql-form & {:as options}]
  (jdbc/execute! (connection) (honeysql->sql honeysql-form) (merge @default-jdbc-options options)))

(defn- where
  "Generate a HoneySQL `where` form using key-value args.

     (where {} :a :b)        -> (h/merge-where {} [:= :a :b])
     (where {} :a [:!= b])   -> (h/merge-where {} [:!= :a :b])
     (where {} {:a [:!= b]}) -> (h/merge-where {} [:!= :a :b])"
  {:style/indent 1}

  ([honeysql-form]
   honeysql-form) ; no-op

  ([honeysql-form m]
   (apply where honeysql-form (apply concat m)))

  ([honeysql-form k v]
   (h/merge-where honeysql-form (if (vector? v)
                                  (let [[f & args] v] ; e.g. :id [:!= 1] -> [:!= :id 1]
                                    (assert (keyword? f))
                                    (vec (cons f (cons k args))))
                                  [:= k v])))

  ([honeysql-form k v & more]
   (apply where (where honeysql-form k v) more)))

(defn- where+
  "Generate a HoneySQL form, converting pairs of arguments with keywords into a `where` clause, and merging other
  HoneySQL clauses in as-is. Meant for internal use by functions like `select`. (So-called because it handles `where`
  *plus* other clauses).

     (where+ {} [:id 1 {:limit 10}]) -> {:where [:= :id 1], :limit 10}"
  [model honeysql-form args]
  (loop [honeysql-form honeysql-form, [first-arg & [second-arg & more, :as butfirst]] args]
    (cond
      (keyword? first-arg) (recur (where honeysql-form first-arg
                                         (models/do-type-fn model first-arg second-arg :in)) more)
      first-arg            (recur (merge honeysql-form first-arg)            butfirst)
      :else                honeysql-form)))

;;; ### UPDATE!

(defn- method-implemented? [^clojure.lang.Keyword methodk model]
  (not (nil? (find-protocol-method models/IModel methodk model))))

(defn update!
  "Update a single row in the database. Returns `true` if a row was affected, `false` otherwise. Accepts either a
  single map of updates to make or kwargs. `entity` is automatically resolved, and `pre-update` is called on `kvs`
  before the object is inserted into the database.

     (db/update! 'Label 11 :name \"ToucanFriendly\")
     (db/update! 'Label 11 {:name \"ToucanFriendly\"})"
  {:style/indent 2}

  (^Boolean [model honeysql-form]
   (let [model (resolve-model model)]
     (not= [0] (execute! (merge (h/update model)
                                honeysql-form)))))

  (^Boolean [model id kvs]
   {:pre [(some? id) (map? kvs) (every? keyword? (keys kvs))]}
   (let [model       (resolve-model model)
         primary-key (models/primary-key model)
         kvs-with-pk (models/do-pre-update model (assoc kvs primary-key id))
         kvs         (dissoc kvs-with-pk primary-key)
         updated?    (update! model (-> (h/sset {} kvs)
                                        (where primary-key (primary-key kvs-with-pk))))]
        (when (and updated?
                   (method-implemented? :post-update model))
          (models/post-update (model id)))
     updated?))

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
             :let    [sql (honeysql->sql {:insert-into model, :values [row-map]})]]
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
       (models/post-insert (model (models/do-type-fn model (models/primary-key model) id :out))))))
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
    (simple-select-one model (where+ (resolve-model model) {:select (or fields [:*])} options))))

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
  (simple-select model (where+ (resolve-model model)
                               {:select (or (model->fields model)
                                            [:*])}
                               options)))

(defn select-reducible
  "Select objects from the database, returns a reducible.

     (transduce (map :name) conj [] (select 'Database :name [:not= nil] {:limit 2}))
        -> [\"name1\", \"name2\"]"
  {:style/indent 1}
  [model & options]
  (simple-select-reducible model (where+ (resolve-model model)
                                         {:select (or (model->fields model)
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
      (simple-delete! model primary-key
                      (models/do-type-fn model primary-key (primary-key object) :in)))))

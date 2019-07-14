(ns toucan.connection
  (:require [clojure.java.jdbc :as jdbc]
            [toucan
             [compile :as compile]
             [dispatch :as dispatch]
             [instance :as instance]]))

;; TODO - does this belong here, or in `db?`

;; NOCOMMIT
(doseq [[symb] (ns-interns *ns*)]
  (ns-unmap *ns* symb))
;;;                                                     Connection
;;; ==================================================================================================================

(defmulti spec
  "Return a JDBC connecton spec that should be to run queries. The spec is passed directly to `clojure.java.jdbc`; it
  can be anything that is accepted by it. In most cases, you'll want to use the same connection for all Models in your
  application; in this case, provide an implementation for `:default`:

    ;; set the default connection
    (defmethod connecton/spec :default [_]
      {:classname \"org.postgresql.Driver\"
       :subprotocol \"postgresql\"
       :subname     \"//localhost:5432/my_db\"
       :user        \"cam\"})

  You can implement this method for individual models for cases where your application connects to multiple databases.

  For most production uses, you'll probably want to use a connection pool (HikariCP and C3P0 are common choices)
  rather than establishing a new connection for each query; consider using something
  like [`metabase/connection-pool`](https://github.com/metabase/connection-pool):

    (require '[metabase.connection-pool :as pool])

    (def jdbc-spec
      {:classname \"org.postgresql.Driver\", ...})

    (def connection-pool
      (delay
       (pool/connection-pool-spec jdbc-spec)))

    (defmethod connecton/spec :default [_] @connection-pool)"
  {:arglists '([model])}
  dispatch/dispatch-value)

(defmethod spec :default
  [_]
  ;; TODO - better exception message
  (throw (Exception. "Don't know how to get a DB connection")))

(def ^:private ^:dynamic *transaction-connection*
  "Transaction connection to the application DB. Used internally by `transaction`."
  nil)

;; TODO - should we still have `*connection` if one wants to set it ?
(defn connection
  "The connection we are current Fetch a JDBC connection spec for passing to `clojure.java.jdbc`. Returns
  `*transaction-connection*`, if it is set (i.e., if we're inside a `transaction` -- this is bound automatically);
  otherwise calls `spec`."
  ([]
   (connection :default))

  ([model]
   (or *transaction-connection*
       (spec model))))

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


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                   Debugging                                                    |
;;; +----------------------------------------------------------------------------------------------------------------+

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

;; TODO - query and execute should call debugging functions

;;;                                              Low-level JDBC functions
;;; ==================================================================================================================

(defmulti query*
  ;; TODO
  {:arglists '([model honeysql-form-or-sql-params jdbc-options])}
  dispatch/dispatch-value)

(defmethod query* :default
  [model honeysql-form-or-sql-params jdbc-options]
  (jdbc/query (connection model) (compile/maybe-compile-honeysql model honeysql-form-or-sql-params) jdbc-options))

(defn query
  ;; TODO - update dox
  "Compile `honeysql-from` and call `jdbc/query` against the application database. Options are passed along to
  `jdbc/query`."
  {:arglists '([model? honeysql-form-or-sql-params jdbc-options?])}
  [& args]
  (let [[model query jdbc-options] (if (instance/model (first args))
                                args
                                (cons :default args))]
    (query* model query jdbc-options)))

(defmulti reducible-query*
  ;; TODO
  {:arglists '([model honeysql-form-or-sql-params jdbc-options])}
  dispatch/dispatch-value)

(defmethod reducible-query* :default
  [model honeysql-form-or-sql-params jdbc-options]
  (jdbc/reducible-query (connection model) (compile/maybe-compile-honeysql model honeysql-form-or-sql-params) jdbc-options))

(defn reducible-query
  ;; TODO - update dox
  "Compile `honeysql-from` and call `jdbc/reducible-query` against the application database. Options are passed along
  to `jdbc/reducible-query`. Note that the query won't actually be executed until it's reduced."
  {:arglists '([model? honeysql-form-or-sql-params jdbc-options?])}
  [& args]
  (let [[model query jdbc-options] (if (instance/model (first args))
                                     args
                                     (cons :default args))]
    (reducible-query* model query jdbc-options)))

;; TODO - `execute!*` ?
(defn execute!
  "Compile `honeysql-form` and call `jdbc/execute!` against the application DB.
  `options` are passed directly to `jdbc/execute!` and can be things like `:multi?` (default `false`) or
  `:transaction?` (default `true`)."
  {:arglists '([model? honeysql-form-or-sql-params jdbc-options?])}
  [& args]
  (let [[model statement jdbc-options] (if (instance/model (first args))
                                         args
                                         (cons :default args))]
    (jdbc/execute! (connection model) (compile/maybe-compile-honeysql model statement) jdbc-options)))

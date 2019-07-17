(ns toucan.connection
  (:require [clojure.java.jdbc :as jdbc]
            [toucan.dispatch :as dispatch]))

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
  dispatch/dispatch-value
  :hierarchy #'dispatch/hierarchy)

(when-not (get-method spec :default)
  (defmethod spec :default
    [_]
    ;; TODO - better exception message
    (throw (Exception. (str "Don't know how to get a DB connection. You can set the default connection by providing a"
                            " default implementation for `toucan.connection/spec`.")))))

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
  {:style/indent 0}
  [& body]
  `(connection/do-in-transaction (fn [] ~@body)))

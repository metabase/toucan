(ns toucan.test-models.pg-enum
  "A model with a PostgreSQL ENUM typed attribute."
  (:require [clojure.java.jdbc :refer [IResultSetReadColumn ISQLValue]]
            [clojure.string :as str]
            [toucan.models :as models])
  (:import [clojure.lang Keyword]
           [java.sql ResultSetMetaData]
           [org.postgresql.util PGobject]))

(def ^:private enum-types
  "A set of all PostgreSQL ENUM types in the DB schema.
   Used to convert enum values back into Clojure keywords."
  #{"thing_type"})

(defn- ^PGobject kwd->pg-enum
  [kwd]
  (let [type (-> (namespace kwd)
                 (str/replace "-" "_"))
        value (name kwd)]
    (doto (PGobject.)
      (.setType type)
      (.setValue value))))

;; NB: This way of using custom datatype is ignored by HoneySQL,
;;     but for `clojure.java.jdbc` this is an idiomatic approach
;;     to its implementation, thus we prefer it over the issue #80.
(extend-protocol ISQLValue
  Keyword
  (sql-value [kwd]
    (let [pg-obj (kwd->pg-enum kwd)
          type (.getType pg-obj)]
      (if (contains? enum-types type)
        pg-obj
        kwd))))

(defn- pg-enum->kwd
  ([^PGobject pg-obj]
   (pg-enum->kwd (.getType pg-obj)
                 (.getValue pg-obj)))
  ([type val]
   {:pre [(not (str/blank? type))
          (not (str/blank? val))]}
   (keyword (str/replace type "_" "-") val)))

(extend-protocol IResultSetReadColumn
  String
  (result-set-read-column [val ^ResultSetMetaData rsmeta idx]
    (let [type (.getColumnTypeName rsmeta idx)]
      (if (contains? enum-types type)
        (pg-enum->kwd type val)
        val)))

  PGobject
  (result-set-read-column [val _ _]
    (if (contains? enum-types (.getType val))
      (pg-enum->kwd val)
      val)))

;; NB: This one is different from the plain ':keyword' by the fact
;;     that an additional DB ENUM type values check is carried out.
(models/add-type!
  :enum
  :in kwd->pg-enum
  :out identity)

(models/defmodel TypedThing :typed
  models/IModel
  (types [_]
    {:type :enum}))

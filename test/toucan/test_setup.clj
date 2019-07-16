(ns toucan.test-setup
  "Test setup logic and helper functions. All test namespaces should require this one to make sure the env is set up
  properly."
  (:require [clojure.java.jdbc :as jdbc]
            expectations
            [toucan
             [connection :as connection]
             [db :as db]
             [test-models :as m]])
  (:import java.sql.Timestamp))

;; Don't run unit tests whenever JVM shuts down
(expectations/disable-run-on-shutdown)

;;; Basic Setup

(def ^:private spec
  (merge
   {:classname   "org.postgresql.Driver"
    :subprotocol "postgresql"
    :subname     (format "//%s:%s/%s"
                         (or (System/getenv "TOUCAN_TEST_DB_HOST") "localhost")
                         (or (System/getenv "TOUCAN_TEST_DB_PORT") "5432")
                         (or (System/getenv "TOUCAN_TEST_DB_NAME") "toucan_test"))}
   (when-let [user (System/getenv "TOUCAN_TEST_DB_USER")]
     {:user user})
   (when-let [password (System/getenv "TOUCAN_TEST_DB_PASS")]
     {:password password})))

(defmethod connection/spec :default
  [_]
  spec)

(defn- execute! {:style/indent 0} [& statements]
  (jdbc/with-db-connection [conn spec]
    (doseq [sql statements]
      (jdbc/execute! conn [sql]))))

;; TODO - maybe these should go in each model's namespace?
(defn- create-models! []
  (execute!
    ;; User
    "CREATE TABLE IF NOT EXISTS users (
       id SERIAL PRIMARY KEY,
       \"first-name\" VARCHAR(256) NOT NULL,
       \"last-name\" VARCHAR(256) NOT NULL
     );"
    "TRUNCATE TABLE users RESTART IDENTITY CASCADE;"
    ;; Venue
    "CREATE TABLE IF NOT EXISTS venues (
       id SERIAL PRIMARY KEY,
       name VARCHAR(256) UNIQUE NOT NULL,
       category VARCHAR(256) NOT NULL,
       \"created-at\" TIMESTAMP NOT NULL,
       \"updated-at\" TIMESTAMP NOT NULL
      );"
    "TRUNCATE TABLE venues RESTART IDENTITY CASCADE;"
    ;; Category
    "CREATE TABLE IF NOT EXISTS categories (
       id SERIAL PRIMARY KEY,
       name VARCHAR(256) UNIQUE NOT NULL,
       \"parent-category-id\" INTEGER
     );"
    "TRUNCATE TABLE categories RESTART IDENTITY CASCADE;"
    ;; Address
    "CREATE TABLE IF NOT EXISTS address (
       id SERIAL PRIMARY KEY,
       street_name text NOT NULL
     );"
    "TRUNCATE TABLE address RESTART IDENTITY CASCADE;"
    ;; Phone Number
    "CREATE TABLE IF NOT EXISTS phone_numbers (
      number TEXT PRIMARY KEY,
      country_code VARCHAR(3) NOT NULL
    );"
    "TRUNCATE TABLE phone_numbers;"))


(def ^java.sql.Timestamp jan-first-2017 (Timestamp/valueOf "2017-01-01 00:00:00"))

(defn- insert-test-data! []
  (binding [db/*behavior* :no-pre-post]
    ;; User
    (db/insert! m/User
      [{:first-name "Cam", :last-name "Saul"}
       {:first-name "Rasta", :last-name "Toucan"}
       {:first-name "Lucky", :last-name "Bird"}])
    ;; Venue
    (db/insert! m/Venue
      [{:name "Tempest", :category "bar", :created-at jan-first-2017, :updated-at jan-first-2017}
       {:name "Ho's Tavern", :category "bar", :created-at jan-first-2017, :updated-at jan-first-2017}
       {:name "BevMo", :category "store", :created-at jan-first-2017, :updated-at jan-first-2017}])
    ;; Category
    (db/insert! m/Category
      [{:name "bar"}
       {:name "dive-bar", :parent-category-id 1}
       {:name "resturaunt"}
       {:name "mexican-resturaunt", :parent-category-id 3}])
    ;; Address
    (db/insert! m/Address {:street_name "1 Toucan Drive"})))

(defn reset-db!
  "Reset the DB to its initial state, creating tables if needed and inserting the initial test data."
  []
  (println "Initializing test DB...")
  (create-models!)
  (insert-test-data!))


(defmacro with-clean-db
  "Run test `body` and reset the database to its initial state afterwards."
  {:style/indent 0}
  [& body]
  `(try
     ~@body
     (finally (reset-db!))))

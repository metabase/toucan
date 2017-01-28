# Setting Up a Connection Pool

In many applications you'll see a big performance benefit from reüsing DB connections when possible. The following guide will
show you how to set up a simple [c3p0](http://www.mchange.com/projects/c3p0/) connection pool using Toucan.

## Adding the dependency

Add `[com.mchange/c3p0 "0.9.5.2"]` (or whatever the latest version is) to your `:dependencies` in your `project.clj` file.

## Creating the connection pool

A picture :camera: is worth a 1000 words, and a code example is worth even more. Here's an example of setting up a
connection pool using c3p0:

```clojure
(ns my-project.core
  (:require [toucan.db :as db])
  (:import java.util.Properties
           com.mchange.v2.c3p0.ComboPooledDataSource))

(def ^:private ^:const db-spec
  {:classname   "org.postgresql.Driver"
   :subprotocol "postgresql"
   :subname     "//localhost:5432/my_db"
   :user        "cam"})

(defn connection-pool
  "Create a c3p0 connection pool for the given database SPEC."
  [{:keys [subprotocol subname classname], :as spec}]
  {:datasource (doto (ComboPooledDataSource.)
                 (.setDriverClass                  classname)
                 (.setJdbcUrl                      (str "jdbc:" subprotocol ":" subname))
                 (.setMaxIdleTimeExcessConnections (* 30 60))   ; 30 seconds
                 (.setMaxIdleTime                  (* 3 60 60)) ; 3 minutes
                 (.setInitialPoolSize              3)
                 (.setMinPoolSize                  3)
                 (.setMaxPoolSize                  15)
                 (.setIdleConnectionTestPeriod     0)
                 (.setTestConnectionOnCheckin      false)
                 (.setTestConnectionOnCheckout     false)
                 (.setPreferredTestQuery           nil)
                 ;; set all other values of the DB spec besides subprotocol, subname, and classname as properties of the connection pool
                 (.setProperties                   (let [properties (Properties.)]
                                                     (doseq [[k v] (dissoc spec :classname :subprotocol :subname)]
                                                       (.setProperty properties (name k) (str v)))
                                                     properties)))})

;; make the default Toucan DB connection a connection pool with our db-spec
(db/set-default-db-connection! (connection-pool db-spec))
```

Tweak the settings as needed.

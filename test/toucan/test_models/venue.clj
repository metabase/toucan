(ns toucan.test-models.venue
  "A model with `:types`, custom `:properties`, and `:default-fields`."
  (:require [toucan
             [models :as models]
             [operations :as ops]])
  (:import java.sql.Timestamp))

(defn- now [] (Timestamp. (System/currentTimeMillis)))

(ops/defbefore ops/insert! ::timestamped [obj]
  (println "obj:" obj) ; NOCOMMIT
  (assoc obj :created-at (now), :updated-at (now)))

(ops/defbefore ops/update! ::timestamped [obj]
  (assoc obj :updated-at (now)))

(models/defmodel Venue
  (table :venues)
  (default-fields #{:id :name :category})
  (types {:category :keyword})
  ::timestamped)

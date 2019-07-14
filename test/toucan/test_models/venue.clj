(ns toucan.test-models.venue
  "A model with `:types`, custom `:properties`, and `:default-fields`."
  (:require [toucan.models :as models])
  (:import java.sql.Timestamp))

(defn- now [] (Timestamp. (System/currentTimeMillis)))

(defmethod models/pre-insert ::timestamped
  [_ obj]
  (assoc obj :created-at (now), :updated-at (now)))

(defmethod models/pre-update ::timestamped
  [obj]
  (assoc obj :updated-at (now)))

(models/defmodel Venue
  (table :venues)
  (default-fields #{:id :name :category})
  (types {:category :keyword})
  ::timestamped)

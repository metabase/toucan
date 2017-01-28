(ns toucan.test-models.venue
  "A model with `:types`, custom `:properties`, and `:default-fields`."
  (:require [toucan.models :as models])
  (:import java.sql.Timestamp))

(defn- now [] (Timestamp. (System/currentTimeMillis)))

(models/add-property! :timestamped?
  :insert (fn [obj _]
            (assoc obj :created-at (now), :updated-at (now)))
  :update (fn [obj _]
            (assoc obj :updated-at (now))))


(models/defmodel Venue :venues)

(extend (class Venue)
  models/IModel
  (merge models/IModelDefaults {:default-fields (constantly #{:id :name :category})
                                :types          (constantly {:category :keyword})
                                :properties     (constantly {:timestamped? true})}))

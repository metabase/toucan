(ns toucan.test-models.address
  (:require [toucan.models :as models]))

(models/defmodel Address
  (table :address))

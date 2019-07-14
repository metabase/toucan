(ns toucan.test-models.phone-number
  (:require [toucan.models :as models]))

(models/defmodel PhoneNumber
  (table :phone_numbers)
  (primary-key :number))

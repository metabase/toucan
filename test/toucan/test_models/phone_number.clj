(ns toucan.test-models.phone-number
  (:require [toucan.models :as models]))

(models/defmodel PhoneNumber :phone_numbers
  models/IModel
  (primary-key [_] :number))

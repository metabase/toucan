(ns toucan.test-models.address
  (:require [toucan.models :as models]
            [toucan.dispatch :as dispatch]))

(models/defmodel Address
  (table :address))

(defmethod common/definition Address
  [_]
  ["CREATE TABLE IF NOT EXISTS address (
       id SERIAL PRIMARY KEY,
       street_name text NOT NULL
     );"
   "TRUNCATE TABLE address RESTART IDENTITY CASCADE;"])

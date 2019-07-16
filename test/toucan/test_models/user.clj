(ns toucan.test-models.user
  "A very simple model for testing out basic DB functionality."
  (:require [toucan.models :as models]
            [toucan.instance :as instance]))

(models/defmodel User
  (table :users))

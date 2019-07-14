(ns toucan.test-models.user
  "A very simple model for testing out basic DB functionality."
  (:require [toucan.models :as models]
            [toucan.instance :as instance]))

(models/defmodel User
  (table :users))

;; TODO
(models/defmodel User2
  User)

(models/defmodel User3)

;; TODO
(derive (instance/model User3) (instance/model User2))

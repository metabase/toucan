(ns toucan.test-models.user
  "A very simple model for testing out basic DB functionality."
  (:require [toucan.models :as models]))

(models/defmodel User :users)

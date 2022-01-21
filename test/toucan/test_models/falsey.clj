(ns toucan.test-models.falsey
  "A model with `:types` that include a `falsey-type` which reproduces an issue #70."
  (:require [toucan.models :as models]))

(models/add-type! :falsey-type
                  :in #(if % "true" "false")
                  :out boolean)

(models/defmodel Falsey :falsey
  models/IModel
  (types [_]
    {:bool? :falsey-type}))

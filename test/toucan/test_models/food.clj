(ns toucan.test-models.food
  "A model with BYTEA as primary key."
  (:require [toucan.models :as models])
  (:import (java.nio.charset StandardCharsets)))

(defn- str->bytes [s]
  (when s
    (.getBytes s StandardCharsets/UTF_8)))

(defn- bytes->str [b]
  (when b
    (String. b StandardCharsets/UTF_8)))

;; Store string as byte array in the database
(models/add-type! :string-as-bytes
                  :in  str->bytes
                  :out bytes->str)

(models/defmodel Food :foods
  models/IModel
  (types [_]
    {:id :string-as-bytes}))

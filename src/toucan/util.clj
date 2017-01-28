(ns toucan.util
  "Utility functions used by other Toucan modules."
  (:require [clojure.string :as s]))

(defn keyword->qualified-name
  "Return keyword K as a string, including its namespace, if any (unlike `name`).

     (keyword->qualified-name :type/FK) -> \"type/FK\""
  [k]
  (when k
    (s/replace (str k) #"^:" "")))

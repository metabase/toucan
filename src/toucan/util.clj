(ns toucan.util
  "Utility functions used by other Toucan modules."
  (:require [clojure.string :as s])
  (:import java.util.Locale))

(defn keyword->qualified-name
  "Return keyword K as a string, including its namespace, if any (unlike `name`).

     (keyword->qualified-name :type/FK) -> \"type/FK\""
  [k]
  (when k
    (s/replace (str k) #"^:" "")))

(defn lower-case
  "Locale-agnostic version of `clojure.string/lower-case`.
  `clojure.string/lower-case` uses the default locale in conversions, turning
  `ID` into `ıd`, in the Turkish locale. This function always uses the
  `Locale/US` locale."
  [s]
  (.. s toString (toLowerCase (Locale/US))))

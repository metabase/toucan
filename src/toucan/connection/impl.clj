(ns toucan.connection.impl
  (:require [clojure.string :as str]))

(def ^:private ^:dynamic *call-count*
  "Atom used as a counter for DB calls when enabled. This number isn't *perfectly* accurate, only mostly; DB calls
  made directly to JDBC won't be logged."
  nil)

(defn -do-with-call-counting
  "Execute F with DB call counting enabled. F is passed a single argument, a function that can be used to retrieve the
  current call count. (It's probably more useful to use the macro form of this function, `with-call-counting`,
  instead.)"
  {:style/indent 0}
  [f]
  (binding [*call-count* (atom 0)]
    (f (partial deref *call-count*))))

(defn inc-call-count!
  ;; TODO - dox
  []
  (when *call-count*
    (swap! *call-count* inc)))

(def ^:dynamic *debug*
  ;; TODO - dox
  false)

(defmacro debug-println
  ;; TODO - dox
  [& args]
  `(when *debug*
     (println ~@args)))

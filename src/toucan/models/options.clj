(ns toucan.models.options
  (:require [toucan
             [dispatch :as dispatch]
             [instance :as instance]]
            [potemkin.types :as p.types]
            [toucan.util :as u])
  (:import clojure.lang.MultiFn))

(defmulti parse-list-option
  {:arglists '([option & args])}
  (fn [option & _] option))

(defmethod parse-list-option :default
  [option & args]
  (cons option args))

(defmulti parse-option
  {:arglists '([option])}
  class)

(defmethod parse-option :default
  [option]
  option)

(defmethod parse-option clojure.lang.IPersistentList
  [[option & args]]
  (apply parse-list-option option args))


(defmulti init!
  {:arglists '([option model])}
  dispatch/dispatch-value)

(defmulti aspect?
  {:arglists '([option])}
  dispatch/dispatch-value)

(defmethod aspect? :default
  [_]
  true)

(defn parse-options [model options]
  (for [option options]
    (try
      (let [option (parse-option option)
            symb   (if (keyword? option)
                     option
                     (gensym "option-"))]
        {:let-form  (when-not (keyword? option)
                      `[~symb ~option])
         :init-form (when (get-method init! (dispatch/dispatch-value option))
                      `(init! ~symb ~model))
         :aspect    (when (aspect? option)
                      symb)})
      (catch Throwable e
        (println "Error parsing option" option)
        (throw (ex-info (format "Error parsing option %s" option) {:model model, :option option} e))))))

(defn defmodel-options-form [model options]
  (println "(clojure.pprint/pprint options):" options) ; NOCOMMIT
  (let [options (parse-options model options)]
    (println "(clojure.pprint/pprint options):" (clojure.pprint/pprint options)) ; NOCOMMIT
    `(let [~@(mapcat :let-form options)]
       (defmethod dispatch/aspects ~model
         [~'_]
         ~(filterv some? (map :aspect options)))
       ~@(filter some? (map :init-form options)))))

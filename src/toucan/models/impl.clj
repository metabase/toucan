(ns toucan.models.impl
  (:require [toucan
             [dispatch :as dispatch]
             [instance :as instance]]))

(defmulti init-defmodel-option!
  ;; TODO - dox
  {:arglists '([model option args])}
  (fn [_ option _]
    option))

;; TODO
(defmulti init-aspect!
  {:arglists '([aspect model])}
  dispatch/dispatch-value)

;; NOCOMMIT
(defmethod init-aspect! :default
  [aspect model]
  (println (list 'init-aspect! aspect model)))

(defmethod init-defmodel-option! :aspects
  [model _ aspects]
  (when (seq aspects)
    (.addMethod ^clojure.lang.MultiFn dispatch/aspects (dispatch/dispatch-value model) (constantly aspects))
    (doseq [aspect aspects]
      (init-aspect! aspect model))))

(defmethod init-defmodel-option! :table
  [model _ [table-kw]]
  (.addMethod ^clojure.lang.MultiFn instance/table (dispatch/dispatch-value model) (constantly table-kw)))

;; TODO - parent option. `isa` derivation

(defn init-defmodel-options!
  ;; TODO - dox
  [model options]
  (doseq [[option args] options]
    (init-defmodel-option! model option args)))

(defn validate-defmodel-options
  ;; TODO - dox
  [options]
  (doseq [option options]
    (when-not (and (list? option)
                   (symbol? (first option)))
      (throw (ex-info (format "Invalid option: %s" option) {:option option})))))

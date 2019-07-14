(ns toucan.models.options
  (:require [toucan
             [dispatch :as dispatch]
             [instance :as instance]])
  (:import clojure.lang.MultiFn))

;; NOCOMMIT
(doseq [[symb] (ns-interns *ns*)]
  (ns-unmap *ns* symb))

(defmulti global-option
  {:arglists '([option])}
  identity)

(defmethod global-option :default [_] nil)

(defmulti init!
  {:arglists '([model option])}
  (fn [_ option]
    (dispatch/dispatch-value option)))

(defmethod init! :default
  [& _]
  nil)

(defmulti aspect
  {:arglists '([model option])}
  (fn [_ option]
    (dispatch/dispatch-value option)))

(defmethod aspect :default
  [_ option & _]
  option)

(defn defmodel-options [model options]
  (doseq [option options]
    (init! model option))
  (let [aspects (filterv some? (for [option options]
                                 (aspect model option)))]
    (when (seq aspects)
      (.addMethod ^MultiFn dispatch/aspects model (constantly aspects)))))

(defn- resolve-option [model option]
  (let [[option & args] (if (sequential? option) option [option])
        option (or (global-option option) option)]
    (if (seq args)
      (concat (list option model) args)
      option)))

(defn resolve-options [model options]
  (vec (for [option options]
         (resolve-option model option))))


;;;                                             Global Option Definitions
;;; ==================================================================================================================

(defn table [__ table-name]
  (instance/of ::table {:table-name table-name}))

(defmethod global-option 'table [_] `table)

(defn primary-key [_ primary-key]
  (instance/of ::primary-key {:primary-key primary-key}))

(defmethod global-option 'primary-key [_] `primary-key)

(defn default-fields [_ fields]
  (instance/of ::default-fields {:default-fields fields}))

(defmethod global-option 'default-fields [_] `default-fields)

(defn types [_ m]
  (instance/of ::types {:types m}))

(defmethod global-option 'types [_] `types)

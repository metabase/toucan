(ns toucan.models.options
  (:require [toucan
             [dispatch :as dispatch]
             [instance :as instance]]
            [toucan.util :as u])
  (:import clojure.lang.MultiFn))

;; TODO - should probably just move this whole namespace into models

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

(defn- resolve-option [model option]
  (let [[option & args] (u/sequencify option)
        option (or (global-option option) option)]
    (if (seq args)
      (concat (list option model) args)
      option)))

(defn resolve-options [model options]
  (vec (for [option options]
         (resolve-option model option))))

(defn init-options! [model options]
  (doseq [option options]
    (init! model option)))

(defn option-aspects [model options]
  (vec (for [option options
             :let   [aspect (aspect model option)]
             :when  aspect]
         aspect)))

(defn defmodel-options [model options]
  (let [options (resolve-options model options)]
    `(let [options# ~options
           aspects# (option-aspects ~model options#)]
       (init-options! ~model options#)
       (defmethod dispatch/aspects ~model
         [~'_]
         aspects#))))


;;;                                             Global Option Definitions
;;; ==================================================================================================================

;; TODO - move all of these into `models`

(defn table [__ table-name]
  (instance/of ::table {:table-name table-name}))

(defmethod global-option 'table [_] `table)

(defn primary-key [_ primary-key]
  (instance/of ::primary-key {:primary-key primary-key}))

(defn default-fields [_ fields]
  (instance/of ::default-fields {:fields fields}))

(defmethod global-option 'default-fields [_] `default-fields)

(defn types [_ m]
  (instance/of ::types {:types m}))

(defmethod global-option 'types [_] `types)

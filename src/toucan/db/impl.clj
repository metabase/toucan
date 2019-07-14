(ns toucan.db.impl
  (:require [toucan
             [dispatch :as dispatch]
             [instance :as instance]
             [models :as models]]
            [toucan.connection :as connection]))


;;; ### `select`

(defmulti do-select
  {:arglists '([behavior model f honeysql-form])}
  (fn [behavior & _] behavior))

(defmethod do-select :no-pre-post
  [_ _ f honeysql-form]
  (f honeysql-form))

(defn pre-select-fn [model]
  (dispatch/combined-method models/pre-select model reverse))

(defn post-select-fn [model]
  (dispatch/combined-method models/post-select model))

(defmethod do-select :default
  [_ model f honeysql-form]
  (let [pre-select  (pre-select-fn model)
        post-select (comp (map (partial instance/of model))
                          (map (post-select-fn model)))]
    (let [results (f (pre-select honeysql-form))]
      (eduction post-select results))))


;;; ### `insert`

(defmulti do-insert!
  {:arglists '([behavior f instances])}
  (fn [behavior & _] behavior))

(defmethod do-insert! :no-pre-post
  [_ f instances]
  (f instances))

(defn pre-insert-fn [model]
  (dispatch/combined-method models/pre-insert model reverse))

(defn post-insert-fn [model]
  (dispatch/combined-method models/post-insert model))

(defmethod do-insert! :default
  [_ f [instance :as instances]]
  ;; TODO - transaction ??
  (->> instances
       (map (pre-insert-fn instance))
       f
       (map (post-insert-fn instance))))


;;; ### `update`

(defmulti do-update!
  {:arglists '([f instances])}
  (fn [behavior & _] behavior))

(defmethod do-update! :no-pre-post
  [f instances]
  (f instances))

(defn pre-update-fn [model]
  (dispatch/combined-method models/pre-update model reverse))

(defn post-update-fn [model]
  (dispatch/combined-method models/post-update model))

(defmethod do-update! :default
  [f [instance :as instances]]
  (connection/transaction
    (->> instances
         (map (pre-update-fn instance))
         f
         (map (post-update-fn instance)))))


;;; ### `delete`

(defmulti do-delete!
  {:arglists '([behavior f instances])}
  (fn [behavior & _] behavior))

(defmethod do-delete! :no-pre-post
  [_ f instances]
  (f instances))

(defn pre-delete-fn [model]
  (dispatch/combined-method models/pre-delete model reverse))

(defn post-delete-fn [model]
  (dispatch/combined-method models/post-delete model))

(defmethod do-delete! :default
  [_ f [instance :as instances]]
  (->> instances
       (map (pre-delete-fn instance))
       f
       (map (post-delete-fn instance))))

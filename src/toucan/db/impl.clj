(ns toucan.db.impl
  (:require [toucan
             [dispatch :as dispatch]
             [instance :as instance]
             [models :as models]]))

(defn do-pre-select [model honeysql-form]
  ((dispatch/combined-method models/pre-select model reverse) honeysql-form))

(defn post-select-fn [model f honeysql-form]
  (dispatch/combined-method models/post-select model))

(defn do-select [model f honeysql-form]
  (let [pre-select  (partial do-pre-select model)
        post-select (comp (map (partial instance/of model))
                          (map (post-select-fn model)))]
    (let [results (f (pre-select honeysql-form))]
      (eduction post-select results))))

(defn do-pre-update [instance]
  ((dispatch/combined-method models/pre-update instance reverse) instance))

(defn do-post-update [instance]
  ((dispatch/combined-method models/post-update instance) instance))

(defn do-pre-insert [instance]
  ((dispatch/combined-method models/pre-insert instance reverse) instance))

(defn do-post-insert [instance]
  ((dispatch/combined-method models/post-insert instance) instance))

(defn do-pre-delete [instance]
  ((dispatch/combined-method models/pre-delete instance reverse) instance))

(defn do-post-delete [instance]
  ((dispatch/combined-method models/post-delete instance) instance))

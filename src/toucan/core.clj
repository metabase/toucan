(ns toucan.core
  (:require [clojure.java.jdbc :as jdbc]
            [potemkin :as potemkin]
            [toucan
             [dispatch :as dispatch]
             [instance :as instance]]))

;; NOCOMMIT
(doseq [[symb] (ns-interns *ns*)]
  (ns-unmap *ns* symb))

(potemkin/import-vars
 [dispatch toucan-type]
 [hydrate hydrate])

;;;                                                 defmodel & related
;;; ==================================================================================================================

(defmulti table
  {:arglists '([model])}
  toucan-type
  :hierarchy #'dispatch/hierarchy)

(defn- model-kw [model-symb]
  (keyword (name (ns-name *ns*)) (name model-symb)))

;; TODO - some way to add optional attributes to a model?
;; TODO - some way to add docstrings to a model?

(defmacro defaspect
  {:style/indent 1}
  [aspect-name & parent-aspects]
  `(do
     (def ~aspect-name
       ~(model-kw aspect-name))

     (defmethod dispatch/aspects ~(model-kw aspect-name)
       [~'_]
       ~(vec parent-aspects))))

(defmacro defmodel
  {:style/indent 2}
  [model-name table & parent-aspects]
  `(do
     (defaspect ~model-name
       ~@parent-aspects)

     (defmethod table ~(model-kw model-name)
       [~'_]
       ~table)))

;; TODO - `primary-key` (?)

;; TODO - `hydration-keys`

;;;                                                 Low-level JDBC Fns
;;; ==================================================================================================================


;; TODO
(defmulti connection
  {:arglists '([model])}
  toucan-type
  :hierarchy #'dispatch/hierarchy)

(defmulti query
  {:arglists '([model sql-params] [model sql-params opts])}
  toucan-type
  :hierarchy #'dispatch/hierarchy)

(defmethod query :default
  [model & args]
  (apply jdbc/query (connection model) args))

;; TODO - execute!

;; TODO - debugging

;;;                                           HoneySQL -> SQL & Where+ Impl
;;; ==================================================================================================================

;; TODO
(defn honeysql->sql [model honeysql-form]
  {:query (list 'honeysql->sql honeysql-form)})


;;;                                                      CRUD Fns
;;; ==================================================================================================================

(defmulti pre-select
  {:arglists '([model honeysql-form])}
  toucan-type
  :hierarchy #'dispatch/hierarchy)

(defmulti post-select
  {:arglists '([model row])}
  toucan-type
  :hierarchy #'dispatch/hierarchy)

(defmulti select-behavior
  {:arglists '([model f honeysql-form])}
  toucan-type
  :hierarchy #'dispatch/hierarchy)

;; TODO - should these be multimethods?
(defn do-pre-select [model honeysql-form]
  ((dispatch/combined-method pre-select model) honeysql-form))

(defn do-post-select [model rows]
  (map (comp (partial instance/of model)
             (dispatch/combined-method post-select model))
       rows))

(defn simple-select-honeysql [model honeysql-form]
  (query model (honeysql->sql model honeysql-form)))

(defmulti select-honeysql
  {:arglists '([model honeysql-form])}
  toucan-type
  :hierarchy #'dispatch/hierarchy)

(defmethod select-honeysql :default
  [model honeysql-form]
  (->> honeysql-form
       (do-pre-select model)
       (simple-select-honeysql model)
       (do-post-select model)))

;; TODO - `update!`

;; TODO - `delete!`

;; TODO - `insert!`

;; TODO - `save!`

;; TODO - `clone!`

;; TODO - `upsert!`?


;;;                                                 Predefined Aspects
;;; ==================================================================================================================

(defmulti type-in
  {:arglists '([type-name v])}
  toucan-type
  :hierarchy #'dispatch/hierarchy)

(defmulti type-out
  {:arglists '([type-name v])}
  toucan-type
  :hierarchy #'dispatch/hierarchy)

;; TODO - `pre-select`

;; TODO - how to apply types to `pre-select` ??

(defmethod post-select ::types
  [{:keys [types]} result]
  (reduce
   (fn [result [field type-fn]]
     (update result field (if (fn? type-fn)
                            type-fn
                            (partial type-out type-fn))))
   result
   types))

(defn types [field->type-fn]
  {:toucan/type ::types, :types field->type-fn})

;; TODO - default fields

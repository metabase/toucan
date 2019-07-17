(ns toucan.models
  (:require [toucan
             [compile :as compile]
             [dispatch :as dispatch]
             [instance :as instance]
             [operations :as ops]]
            [toucan.models.options :as options])
  (:import clojure.lang.MultiFn))

(defn instance-of
  "Convenience."
  [model]
  (instance/of model))

(defn table
  "Convenience."
  [model]
  (compile/table model))

(defn primary-key
  "Convenience."
  [model]
  (compile/primary-key model))

;;;                                                      defmodel
;;; ==================================================================================================================

(defmacro defmodel
  "Define a new \"model\". Models encapsulate information and behaviors related to a specific table in the application
  DB."
  ;; TODO - more dox
  {:style/indent 1, :arglists '([model docstring? & options])}
  [model & args]
  (let [[docstring & options] (if (string? (first args))
                                args
                                (cons nil args))
        model-kw              (keyword (name (ns-name *ns*)) (name model))]
    `(do
       (dispatch/derive ~model-kw :model/any)
       (def ~model
         ~@(when docstring [docstring])
         ~model-kw)
       (options/defmodel-options ~model ~@options))))


;;;                                            Predefined Options & Aspects
;;; ==================================================================================================================

;; TODO - `parent` option

(defmethod options/parse-list-option 'table
  [_ table-name]
  ;; TODO - should we validate that `table-name` is a something that HoneySQL can use?
  [::table table-name])

(defmethod options/init! ::table [[_ table-name] model]
  (.addMethod ^MultiFn compile/table model (constantly table-name)))

(defmethod options/aspect? ::table [_] false)


;;; ### `primary-key`

(defmethod options/parse-list-option 'primary-key
  [_ pk]
  [::primary-key pk])

(defmethod options/init! ::primary-key [[_ pk] model]
  {:pre [(or (keyword? pk)
             (and (sequential? pk)
                  (every? keyword? pk)))]}
  (.addMethod ^MultiFn compile/primary-key model (constantly pk)))

(defmethod options/aspect? ::primary-key [_] false)

;;; ### `default-fields`

(defmethod options/parse-list-option 'default-fields
  [_ default-fields]
  [::default-fields default-fields])

(ops/def-before-advice ops/select ::default-fields [{existing-select :select, :as honeysql-form}]
  (let [[_ fields] &model]
    (assert ((some-fn sequential? set?) fields))
    (if (or (not existing-select) (= existing-select [:*]))
      (assoc honeysql-form :select fields)
      honeysql-form)))


;;; ### `types`

;; Model types are a easy way to define functions that should be used to transform values of a certain column
;; when they come out from or go into the database.
;;
;; For example, suppose you had a `Venue` model, and wanted the value of its `:category` column to automatically
;; be converted to a Keyword when it comes out of the DB, and back into a string when put in. You could let Toucan
;; know to take care of this by defining the model as follows:
;;
;;     (defmodel Venue :my_venue_table
;;       (types {:category :keyword})
;;
;; Whenever you fetch a Venue, Toucan will automatically apply the appropriate `:out` function for values of
;; `:category`:
;;
;;    (db/select-one Venue) ; -> {:id 1, :category :bar, ...}
;;
;; In the other direction, `insert!`, `update!`, and `save!` will automatically do the reverse, and call the
;; appropriate `type-in` implementation.
;;
;; `:keyword` is the only Toucan type defined by default, but adding more is simple.
;;
;; You can add a new type by implementing `type-in` and `type-out`:
;;
;;    ;; add a :json type (using Cheshire) will serialize instances as JSON
;;    ;; going into the DB, and deserialize JSON coming out from the DB
;;    (defmethod type-in :json
;;      [_ v]
;;      (json/generate-string v))
;;
;;    (defmethod type-out :json
;;      [_ v]
;;      (json/parse-string v keyword))
;;
;; In the example above, values of any columns marked as `:json` would be serialized as JSON before going into the DB,
;; and deserialized *from* JSON when coming out of the DB.

(defmethod options/parse-list-option 'types
  [_ m]
  [::types m])

(defmulti type-in
  {:arglists '([type-name v])}
  dispatch/dispatch-value
  :hierarchy #'dispatch/hierarchy)

(defmulti type-out
  {:arglists '([type-name v])}
  dispatch/dispatch-value
  :hierarchy #'dispatch/hierarchy)

;; TODO - `pre-select` (?)

(defn apply-type-fns [f instance types]
  {:pre [(map? types)]}
  (reduce
   (fn [instance [field type-fn]]
     (update instance field (if (fn? type-fn)
                              type-fn
                              (partial f type-fn))))
   instance
   types))

(ops/def-after-advice ops/select ::types [instance]
  (let [[_ types] &model]
    (apply-type-fns type-out instance types)))

(ops/def-before-advice :operations/insert-or-update ::types [instance]
  (let [[_ types] &model]
    (apply-type-fns type-in instance types)))

(ops/def-after-advice :operations/insert-or-update ::types [instance]
  (let [[_ types] &model]
    (apply-type-fns type-out instance types)))

;; TODO - `*-delete` ?


;;; #### Predefined Types

(defmethod type-in :keyword
  [_ v]
  (if (keyword? v)
    (str (when-let [keyword-ns (namespace v)]
           (str keyword-ns "/"))
         (name v))
    v))

(defmethod type-out :keyword
  [_ v]
  (if (string? v)
    (keyword v)
    v))

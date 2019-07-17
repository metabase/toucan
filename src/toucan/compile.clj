(ns toucan.compile
  (:require [honeysql
             [core :as hsql]
             [format :as hformat]
             [helpers :as h]]
            [toucan
             [dispatch :as dispatch]
             [util :as u]]))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                              HoneySQL Compilation                                              |
;;; +----------------------------------------------------------------------------------------------------------------+

(defmulti automatically-convert-dashes-and-underscores?
  {:arglists '([model])}
  dispatch/dispatch-value
  :hierarchy #'dispatch/hierarchy)

(when-not (get-method automatically-convert-dashes-and-underscores? :default)
  (defmethod automatically-convert-dashes-and-underscores? :default
    [_]
    false))

;; TODO - I think we do need seperate options
(defmulti honeysql-options
  "Options to pass to HoneySQL when compiling to SQL (via `honeysql.core/format`). This map can contain anything
  accepted by HoneySQL, but the most interesting ones are:

  * `:quoting` -- the quoting style that should be used to quote identifiers when compiling HoneySQL forms. Keywords
    can be anything accepted by HoneySQL (see `honeysql.format/quote-fns`) -- at the time of this writing, valid options
    are `:ansi`, `:mysql`, `:sqlserver`, and `:oracle`.

  * `:allow-dashed-names?` -- whether to convert dashes to underscores in identifiers when compiling a HoneySQL form.
    By default, this is `true`, which means dashes are left as-is; set it to `false` to automatically convert them."
  {:arglists '([model])}
  dispatch/dispatch-value
  :hierarchy #'dispatch/hierarchy)

;; TODO - should we validate the options before use?

(when-not (get-method honeysql-options :default)
  (defmethod honeysql-options :default
    [model]
    {:quoting :ansi, :allow-dashed-names? (not (automatically-convert-dashes-and-underscores? model))}))

(defn quoting-style
  ;; TODO - dox
  ([]
   (quoting-style :default))

  ([model]
   (:quoting (honeysql-options model))))

(defn quote-fn
  "The function that JDBC should use to quote identifiers for our database. This is passed as the `:entities` option
  to functions like `jdbc/insert!`."
  ([]
   (quote-fn :default))

  ([model]
   (get @#'honeysql.format/quote-fns (quoting-style model))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                         Info about models needed for compilation (table, primary-key)                          |
;;; +----------------------------------------------------------------------------------------------------------------+

(defmulti table
  {:arglists '([model-or-instance])}
  dispatch/dispatch-value
  :hierarchy #'dispatch/hierarchy)

(defmethod table :default [x]
  (throw
   (ex-info
    (str (or (dispatch/dispatch-value x) x)
         " does not have a table associated with it. You can specify its `table` by implementing `toucan.compile/table`"
         " or by passing a `(table ...)` to `defmodel`.")
    {:arg x, :model (dispatch/dispatch-value x)})))

;; TODO - you could override this to validate etc
(defmulti primary-key
  "Defines the primary key(s) for this Model. Defaults to `:id`.

    (defmethod primary-key User [_]
      :email)

  You can specify a composite key by returning a vector of keys:

    (defmethod primary-key Session [_]
      [:user_id :session_token])"
  {:arglists '([model])}
  dispatch/dispatch-value
  :hierarchy #'dispatch/hierarchy)

(defmethod primary-key :default
  [_]
  :id)

(defmulti primary-key-value
  {:arglists '([instance])}
  dispatch/dispatch-value
  :hierarchy #'dispatch/hierarchy)

(defmethod primary-key-value :default
  [instance]
  (let [pk-key (primary-key instance)]
    (if-not (sequential? pk-key)
      (get instance pk-key)
      (mapv (partial get instance) pk-key))))

(defmulti assoc-primary-key
  {:arglists '([instance primary-key-value])}
  dispatch/dispatch-value
  :hierarchy #'dispatch/hierarchy)

(defmethod assoc-primary-key :default
  [instance primary-key-value]
  (let [pk-key (primary-key instance)]
    (if-not (sequential? pk-key)
      (assoc instance pk-key primary-key-value)
      (into instance (zipmap pk-key primary-key-value)))))

(defmulti dissoc-primary-key
  {:arglists '([instance])}
  dispatch/dispatch-value
  :hierarchy #'dispatch/hierarchy)

(defmethod dissoc-primary-key :default
  [instance]
  (let [pk-key (primary-key instance)]
    (if-not (sequential? pk-key)
      (dissoc instance pk-key)
      (reduce dissoc instance pk-key))))

(defmulti primary-key-where-clause
  {:arglists '([instance] [model pk-value])}
  dispatch/dispatch-value
  :hierarchy #'dispatch/hierarchy)

(defmethod primary-key-where-clause :default
  ([instance]
   (primary-key-where-clause instance (primary-key-value instance)))

  ;; TODO - not sure if needed
  ([model pk-value]
   (when-let [pk-key (primary-key model)]
     (if-not (sequential? pk-key)
       [:= pk-key pk-value]
       (into [:and] (for [[k v] (zipmap pk-key pk-value)]
                      [:= k v]))))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                  Compilation                                                   |
;;; +----------------------------------------------------------------------------------------------------------------+


(defn compile-honeysql
  "Compile `honeysql-form` to SQL. This returns a vector with the SQL string as its first item and prepared statement
  params as the remaining items."
  [model honeysql-form]
  {:pre [(map? honeysql-form)]}
  ;; Not sure *why* but without setting this binding on *rare* occasion HoneySQL will unwantedly
  ;; generate SQL for a subquery and wrap the query in parens like "(UPDATE ...)" which is invalid
  (let [[sql & args :as sql-args] (binding [hformat/*subquery?* false]
                                    (apply hsql/format honeysql-form (mapcat identity (honeysql-options model))))]
    sql-args))

(defn maybe-compile-honeysql
  ;; TODO
  [model honeysql-form-or-sql-args]
  {:pre [((some-fn map? string? sequential?) honeysql-form-or-sql-args)]}
  (if (map? honeysql-form-or-sql-args)
    (compile-honeysql model honeysql-form-or-sql-args)
    honeysql-form-or-sql-args))

(defn qualified?
  "Is `field-name` qualified (e.g. with its table name)?"
  [field-name]
  (if (vector? field-name)
    (qualified? (first field-name))
    (boolean (re-find #"\." (name field-name)))))

(defn qualify
  "Qualify a `field-name` name with the name its `entity`. This is necessary for disambiguating fields for HoneySQL
  queries that contain joins.

    (db/qualify CardFavorite :id) ;-> :card_favorite.id"
  [model field-name]
  (if (vector? field-name)
    [(qualify model (first field-name)) (second field-name)]
    (hsql/qualify (table model) field-name)))

(defn maybe-qualify
  "Qualify `field-name` with its table name if it's not already qualified."
  [model field-name]
  (if (qualified? field-name)
    field-name
    (qualify model field-name)))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                              Parsing Select Forms                                              |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- where
  "Generate a HoneySQL `where` form using key-value args.

     (where {} :a :b)        -> (h/merge-where {} [:= :a :b])
     (where {} :a [:!= b])   -> (h/merge-where {} [:!= :a :b])"
  [honeysql-form k v]
  (let [where-clause (if (vector? v)
                       (let [[f & args] v]
                         (assert (keyword? f))
                         (into [f k] args))
                       [:= k v])]
    (h/merge-where honeysql-form where-clause)))

(defn compile-select-options [args]
  (loop [honeysql-form {}, [arg1 arg2 & more :as remaining] args]
    (cond
      (nil? arg1)
      honeysql-form

      (keyword? arg1)
      (recur (where honeysql-form arg1 arg2) more)

      (map? arg1)
      (recur (merge honeysql-form arg1) (cons arg2 more))

      :else
      (throw (ex-info (format "Don't know what to do with arg: %s. Expected keyword or map" arg1)
                      {:arg arg1, :all-args args})))))

(defn compile-select
  {:arglists '([model-or-object pk-value-or-honeysql-form? & options])}
  ([object]
   (compile-select object (primary-key-value object)))

  ([model form]
   ;; TODO - how to differentiate wrapped model w/ model + fields vector...
   (let [[model & fields] (u/sequencify model)]
     (if (map? form)
       (merge
        {:select (if (seq fields)
                   (vec fields)
                   [:*])
         :from   [(table model)]}
        form)
       (compile-select model {:where (primary-key-where-clause model form)}))))

  ([model arg & more]
   (compile-select model (compile-select-options (cons arg more)))))

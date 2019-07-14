(ns toucan.compile
  (:require [clojure.pprint :refer [pprint]]
            [clojure.tools.logging :as log]
            [honeysql
             [core :as hsql]
             [format :as hformat]
             [helpers :as h]]
            [toucan
             [dispatch :as dispatch]
             [instance :as instance]
             [models :as models]
             [util :as u]]
            [toucan.connection.impl :as connection.impl]))

;; NOCOMMIT
(doseq [[symb] (ns-interns *ns*)]
  (ns-unmap *ns* symb))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                              HoneySQL Compilation                                              |
;;; +----------------------------------------------------------------------------------------------------------------+

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
  dispatch/dispatch-value)

;; TODO - should we validate the options before use?

(defmethod honeysql-options :default
  [_]
  {:quoting :ansi, :allow-dashed-names? true})

(defn quoting-style
  ;; TODO - dox
  ([]
   (quoting-style :default))
  ([model]
   (:quoting-style (honeysql-options model))))

(defn quote-fn
  "The function that JDBC should use to quote identifiers for our database. This is passed as the `:entities` option
  to functions like `jdbc/insert!`."
  ([]
   (quote-fn :default))
  ([model]
   (get #'honeysql.format/quote-fns (quoting-style model))))


(defn compile-honeysql
  "Compile `honeysql-form` to SQL. This returns a vector with the SQL string as its first item and prepared statement
  params as the remaining items."
  [model honeysql-form]
  {:pre [(map? honeysql-form)]}
  ;; Not sure *why* but without setting this binding on *rare* occasion HoneySQL will unwantedly
  ;; generate SQL for a subquery and wrap the query in parens like "(UPDATE ...)" which is invalid
  (let [[sql & args :as sql-args] (binding [hformat/*subquery?* false]
                                    (apply hsql/format honeysql-form (mapcat identity (honeysql-options model))))]
    (connection.impl/debug-println
     (pprint honeysql-form)
     (format "\n%s\n%s" (u/format-sql sql) args))
    (log/debug (str "DB Call: " sql))
    (connection.impl/inc-call-count!)
    sql-args))

(defn maybe-compile-honeysql
  ;; TODO
  [model honeysql-form-or-sql-args]
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
    (hsql/qualify (models/table model) field-name)))

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
      (empty? remaining)
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
   (compile-select object (models/primary-key-value object)))

  ([model pk-value-or-honeysql-form]
   (if (map? pk-value-or-honeysql-form)
     (merge
      {:select [:*]
       :from   [(instance/model model)]}
      pk-value-or-honeysql-form)
     (compile-select model {:where (models/primary-key-where-clause model pk-value-or-honeysql-form)})))

  ([model arg & more]
   (compile-select model (compile-select-options (cons arg more)))))

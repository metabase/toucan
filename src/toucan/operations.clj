(ns toucan.operations
  (:require [clojure.java.jdbc :as jdbc]
            [toucan
             [compile :as compile]
             [connection :as connection]
             [debug :as debug]
             [dispatch :as dispatch]
             [instance :as instance]
             [util :as u]]))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                      Op Advice Method (e.g. post-select)                                       |
;;; +----------------------------------------------------------------------------------------------------------------+

(dispatch/derive :advice/before     :advice/any)
(dispatch/derive :advice/after      :advice/any)
(dispatch/derive :advice/around     :advice/any)
(dispatch/derive :advice/around-all :advice/any)

(defmulti advice
  {:arglists '([operation advice-type model arg])}
  dispatch/dispatch-3
  :hierarchy #'dispatch/hierarchy)

(defmacro defadvice
  {:style/indent 4}
  [operation advice-type model [arg-binding] & body]
  `(defmethod advice [~(if (symbol? operation)
                         `(::operation (meta (var ~operation)))
                         operation)
                      ~(if (namespace advice-type) (keyword advice-type) (keyword "advice" (name advice-type)))
                      ~(if (keyword? model)
                         model
                         `(dispatch/dispatch-value ~model))]
     [~'&operation ~'&advice-type ~'&model ~(or arg-binding '_)]
     ~@body))

(defmacro def-before-advice {:style/indent 3} [operation model [arg-binding] & body]
  `(defadvice ~operation :advice/before ~model [~arg-binding]
     ~@body))

(defmacro def-after-advice {:style/indent 3} [operation model [results-binding] & body]
  `(defadvice ~operation :advice/after ~model [~results-binding]
     ~@body))

(defmacro def-around-advice {:style/indent 3} [operation model [f-binding] & body]
  `(defadvice ~operation :advice/around ~model [~f-binding]
     ~@body))

;; TODO - not sure we need this...
(defmacro def-around-all-advice {:style/indent 3} [operation model [f-binding] & body]
  `(defadvice ~operation :advice/around-all ~model [~f-binding]
     ~@body))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                            Applying Combined Advice                                            |
;;; +----------------------------------------------------------------------------------------------------------------+

(dispatch/derive :strategy/combine :strategy/any)
(dispatch/derive :strategy/normal  :strategy/any)
(dispatch/derive :strategy/skip    :strategy/any)

;; TODO - should default strategy also dispatch on model? So you could do something like `VenueNoPrePost` if you were
;; so inclined. Or use strategy-based caching
(defmulti default-strategy-for-operation
  {:arglists '([operation])}
  identity)

(defmethod default-strategy-for-operation :default
  [_]
  :strategy/combine)

(def ^:dynamic *advice-strategy*
  "Strategy that should be used to combine advice for the current operation. Bind this to override the default strategy,
  obtained by calling `default-strategy-for-operation`."
  nil)

;; TODO - should we also have a strategy that doesn't apply combined? (e.g. for `:query` and `execute!`

(defmulti apply-advice
  {:arglists '([operation advice-type strategy model arg])}
  dispatch/dispatch-4
  :hierarchy #'dispatch/hierarchy)

(defmethod apply-advice :default
  [_ _ _ _ arg]
  arg)


;;; #### Default Advice

(defmethod apply-advice [:operation/any :advice/any :strategy/normal :model/any]
  [op advice-type _ model arg]
  (advice op advice-type model arg))

;; `around-all` and `before` both combine in reversed order; `around` and `after` both combine in normal order
(dispatch/derive :advice/around-all ::combine-reversed)
(dispatch/derive :advice/before     ::combine-reversed)
(dispatch/derive :advice/around     ::combine-normally)
(dispatch/derive :advice/after      ::combine-normally)

(defmethod apply-advice [:operation/any ::combine-reversed :strategy/combine :model/any]
  [op advice-type _ model arg]
  ((dispatch/combined-method [advice op advice-type] model reverse) arg))

(defmethod apply-advice [:operation/any ::combine-normally :strategy/combine :model/any]
  [op advice-type _ model arg]
  ((dispatch/combined-method [advice op advice-type] model reverse) arg))


;;; ### Predefined Advice Behaviors

;;; #### Debugging

(dispatch/derive :strategy/debug :strategy/any)

(defmethod apply-advice [:operation/any :advice/any :strategy/debug :model/any]
  [op advice-type _ model arg]
  (printf "[%s] Invoking %s advice for model %s, arg:\n" op advice-type model) ;; WOW!
  (println arg)
  (apply-advice op advice-type (default-strategy-for-operation op) model arg))

(defmacro debug {:style/indent 0} [& body]
  `(binding [*advice-strategy* :strategy/debug]
     ~@body))


;;; ### Ignoring

(defmethod apply-advice [:operation/any :advice/any :strategy/skip :model/any]
  [op advice-type _ _ arg]
  (debug/debug-println "IGNORING" op advice-type)
  arg)

;; TODO - maybe rename this to `skip-advice` or `ignore-advice-fns` to make it clearer at first glance
(defmacro ignore-advice {:style/indent 0} [& body]
  `(binding [*advice-strategy* :strategy/skip]
     ~@body))


;;; ### Helper Fns

(defn- do-operation-with-advice [operation model f arg]
  (let [strategy     (or *advice-strategy* (default-strategy-for-operation operation))
        apply-advice (fn [advice-type arg]
                       (debug/debug-println (list 'apply-advice operation advice-type strategy model (class arg)))
                       (apply-advice operation advice-type strategy model arg))
        f            (fn [arg]
                       (->> arg
                            (apply-advice :advice/before)
                            ((apply-advice :advice/around f))
                            (apply-advice :advice/after)))]
    ((apply-advice :advice/around-all f) arg)))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                              Operation Interface                                               |
;;; +----------------------------------------------------------------------------------------------------------------+

(dispatch/derive :operation/any :default)

(defmulti operation
  {:arglists '([operation-type model arg])}
  dispatch/dispatch-2
  :hierarchy #'dispatch/hierarchy)


;;; ### Helper Fns

(defmulti do-operation
  {:arglists '([operation-type model arg])}
  dispatch/dispatch-2
  :hierarchy #'dispatch/hierarchy)

(defmethod do-operation :default [operation-type model arg]
  (let [f (partial operation operation-type model)]
    (do-operation-with-advice operation-type model f arg)))

(defmacro defoperation
  {:arglists '([operation docstring? options?] [operation docstring? options? [model-binding arg-binding] & body])}
  [operation-symb & args]
  (let [[docstring & args]          (if (string? (first args))
                                      args
                                      (cons nil args))
        [options & args]            (if (map? (first args))
                                      args
                                      (cons nil args))
        [bindings & body]           args
        {:keys [dispatch-value
                dispatch-parent]}   options
        operation-kw                (or dispatch-value
                                        (keyword (name (ns-name *ns*)) (name operation-symb)))
        [model-binding arg-binding] bindings
        model-binding               (or model-binding '_)
        arg-binding                 (or arg-binding '_)]
    `(do
       (dispatch/derive ~operation-kw ~(or dispatch-parent :operation/any))

       ~@(when (seq body)
           `[(defmethod operation [~operation-kw :model/any]
               [~'&operation ~model-binding ~arg-binding]
               ~@body)])

       (def ~(vary-meta (symbol (name operation-symb)) (partial merge {:arglists   (list 'quote (list ['model arg-binding]))
                                                                       ::operation operation-kw}))
         ~@(when docstring
             [docstring])
         (partial do-operation ~operation-kw)))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                             Operation Definitions                                              |
;;; +----------------------------------------------------------------------------------------------------------------+

(dispatch/derive :operation/read :operation/any)
(dispatch/derive :operation/write :operation/any)

;; TODO - should `query` and `execute!` be operations themselves?

(def ^:dynamic *jdbc-options* nil)

(defoperation query
  {:dispatch-value :operation/query, :dispatch-parent :operation/read}
  [model a-query]
  (jdbc/query (connection/connection) (compile/maybe-compile-honeysql model a-query) *jdbc-options*))

(defoperation reducible-query
  {:dispatch-value :operation/reducible-query, :dispatch-parent :operation/query}
  [model a-query]
  (debug/inc-call-count!)
  (jdbc/reducible-query (connection/connection) (compile/maybe-compile-honeysql model a-query) *jdbc-options*))

(defoperation execute!
  {:dispatch-value :operation/execute!, :dispatch-parent :operation/write}
  [model statement]
  (debug/inc-call-count!)
  (jdbc/execute! (connection/connection) (compile/maybe-compile-honeysql model statement) *jdbc-options*))


;;; -------------------------------------------- Higher-level operations ---------------------------------------------

(defoperation select
  {:dispatch-value :operation/select, :dispatch-parent :operation/read}
  [model a-query]
  (query model a-query))

(defmethod apply-advice [:operation/select :advice/after :strategy/combine :model/any]
  [op advice-type strategy model results]
  (let [parent-method (u/get-method-for-dispatch-value
                       apply-advice :operation/select-reducible advice-type strategy model results)]
    (seq (parent-method op advice-type strategy model results))))

(defoperation select-reducible
  {:dispatch-value :operation/select-reducible, :dispatch-parent :operation/select}
  [model query]
  (reducible-query model query))

(defmethod apply-advice [:operation/select-reducible :advice/after :strategy/combine :model/any]
  [op advice-type _ model results]
  (let [f (comp (map (partial instance/of model))
                (map (dispatch/combined-method [advice op advice-type] model)))]
    (eduction f results)))

(defoperation execute-returning-primary-keys!
  {:dispatch-value :operation/execute-returning-primary-keys!, :dispatch-parent :operation/execute!}
  [model [sql & params]]
  (with-open [^java.sql.PreparedStatement prepared-statement (jdbc/prepare-statement
                                                              (jdbc/get-connection (connection/connection model))
                                                              sql
                                                              {:return-keys (u/sequencify (compile/primary-key model))})]
    (#'jdbc/dft-set-parameters prepared-statement params)
    (debug/inc-call-count!)
    (when (pos? (.executeUpdate prepared-statement))
      (with-open [generated-keys-result-set (.getGeneratedKeys prepared-statement)]
        (vec (jdbc/result-set-seq generated-keys-result-set))))))

;; provided for convenience since often times it's nice to do things like validate values on either insert or update
(dispatch/derive :operation/insert-or-update :operation/write)

(defoperation insert!
  {:dispatch-value :operation/insert!, :dispatch-parent :operation/insert-or-update}
  [model instances]
  (when (seq instances)
    (let [honeysql-form {:insert-into (compile/table model)
                         :values      instances}
          result-rows   (execute-returning-primary-keys! model (compile/compile-honeysql model honeysql-form))]
      (map
       (fn [instance result-row]
         (let [pk-value (compile/primary-key-value (instance/of instance result-row))]
           (compile/assoc-primary-key instance pk-value)))
       instances
       result-rows))))

(defoperation update!
  {:dispatch-value :operation/update!, :dispatch-parent :operation/insert-or-update}
  [model instances]
  (when (seq instances)
    ;; TODO - should this be in a transaction?
    (let [where-clauses (for [instance instances
                              :let     [changes (instance/changes instance)]
                              :when    (seq changes)]
                          (let [honeysql-form {:update [(compile/table model)]
                                               :set    changes
                                               :where  (compile/primary-key-where-clause model)}]
                            (when (execute! model honeysql-form)
                              (compile/primary-key-where-clause model))))]
      ;; TODO - it would be nicer if we just used `return-keys` to fetch the entire object instead of fetching it a
      ;; second time
      (select model {:where (into [:or] where-clauses)}))))

(defoperation delete!
  {:dispatch-value :operation/delete!, :dispatch-parent :operation/write}
  [model instances]
  (when (seq instances)
    ;; TODO - should this be in a transaction?
    ;; TODO - how to tell if `execute!` is successful?
    (execute! model {:delete-from [(compile/table model)]
                     :where       (into [:or] (map compile/primary-key-where-clause instances))})
    instances))

;;; ------------------------------------------ New Higher-Level Operations -------------------------------------------

(defoperation save!
  {:dispatch-value :operation/save!, :dispatch-parent :operation/write}
  [model instances]
  ;; TODO - seems a little inefficient
  (for [instance instances]
    ((if (compile/primary-key-value instance)
       update!
       insert!) model instance)))

(defoperation clone!
  {:dispatch-value :operation/clone!, :dispatch-parent :operation/write}
  [model instances]
  (insert! model (map compile/dissoc-primary-key instances)))

(ns toucan.db.operations
  (:require [clojure.java.jdbc :as jdbc]
            [toucan
             [connection :as connection]
             [dispatch :as dispatch]
             [instance :as instance]
             [models :as models]]))

(doseq [[symb] (ns-interns *ns*)]
  (ns-unmap *ns* symb))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                      Op Advice Method (e.g. post-select)                                       |
;;; +----------------------------------------------------------------------------------------------------------------+

(dispatch/derive :advice/before :advice/any)
(dispatch/derive :advice/after  :advice/any)
(dispatch/derive :advice/around :advice/any)

(defmulti advice
  {:arglists '([operation advice-type model arg])}
  (fn [operation advice-type model arg] [operation advice-type model arg])
  :hierarchy #'dispatch/hierarchy)

(defmacro defadvice {:style/indent 4} [operation advice-type model arg-binding & body]
  `(defmethod advice [~(if (symbol? operation)
                         `(::operation (meta (var ~operation)))
                         operation)
                      ~(if (namespace advice-type) (keyword advice-type) (keyword "advice" (name advice-type)))
                      ~(if (keyword? model)
                         model
                         `(dispatch/dispatch-value ~model))]
     [~'&operation ~'&advice-type ~'&model ~@arg-binding]
     ~@body))

(defmacro def-before-advice {:style/indent 3} [operation model arg-binding & body]
  `(defadvice ~operation :advice/before ~model ~arg-binding
     ~@body))

(defmacro def-after-advice {:style/indent 3} [operation model arg-binding & body]
  `(defadvice ~operation :advice/after ~model ~arg-binding
     ~@body))

(defmacro def-around-advice {:style/indent 3} [operation model arg-binding & body]
  `(defadvice ~operation :advice/around ~model ~arg-binding
     ~@body))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                            Applying Combined Advice                                            |
;;; +----------------------------------------------------------------------------------------------------------------+

(dispatch/derive :behavior/apply-combined :behavior/any)
(dispatch/derive :behavior/no-combine     :behavior/any)

(defmulti default-advice-behavior
  {:arglists '([operation])}
  identity)

(defmethod default-advice-behavior :default
  [_]
  :behavior/apply-combined)

(def ^:dynamic *advice-behavior* nil)

;; TODO - should we also have a behavior that doesn't apply combined? (e.g. for `:query` and `execute!`

(defmulti apply-advice
  {:arglists '([operation advice-type behavior model arg])}
  (fn [operation advice-type behavior model _]
    [operation advice-type behavior model])
  :hierarchy #'dispatch/hierarchy)

(defmethod apply-advice :default
  [_ _ _ _ arg]
  arg)


;;; #### Default Advice

(defmethod apply-advice [:operation/any :advice/any :behavior/no-combine :model/any]
  [op advice-type _ model arg]
  (advice op advice-type model arg))


(defmethod apply-advice [:operation/any :advice/before :behavior/apply-combined :model/any]
  [op advice-type _ model arg]
  ((dispatch/combined-method [advice op advice-type] model reverse) arg))

;; after & around have the same impl for `:behavior/apply-combined`
(defmethod apply-advice [:operation/any :advice/any :behavior/apply-combined :model/any]
  [op advice-type _ model result]
  ((dispatch/combined-method [advice op advice-type] model) result))


;;; ### Predefined Advice Behaviors

;;; #### Debugging

(dispatch/derive :behavior/debug :behavior/any)

(defmethod apply-advice [:operation/any :advice/any :behavior/debug :model/any]
  [op advice-type _ model arg]
  (printf "[%s] Invoking %s advice for model %s, arg:\n" op advice-type model) ;; WOW!
  (println arg)
  (apply-advice op advice-type (default-advice-behavior op) model arg))

(defmacro debug [& body]
  `(binding [*advice-behavior* :behavior/debug]
     ~@body))


;;; ### Ignoring

(dispatch/derive :behavior/ignore :behavior/any)

(defmethod apply-advice [:operation/any :advice/any :behavior/ignore :model/any]
  [op advice-type _ _ arg]
  (println "IGNORING" op advice-type)
  arg)

(defmacro ignore-advice [& body]
  `(binding [*advice-behavior* :behavior/ignore]
     ~@body))


;;; ### Helper Fns

(defn- do-operation-with-advice [operation model f arg]
  (let [behavior     (or *advice-behavior* (default-advice-behavior operation))
        apply-advice (fn [advice-type arg]
                       (apply-advice operation advice-type behavior model arg))]
    (->> arg
         (apply-advice :advice/before)
         ((apply-advice :advice/around f))
         (apply-advice :advice/after))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                              Operation Interface                                               |
;;; +----------------------------------------------------------------------------------------------------------------+

(dispatch/derive :operation/any :default)

(defmulti operation
  {:arglists '([operation-type model arg])}
  (fn [operation-type model _] [operation-type model])
  :hierarchy #'dispatch/hierarchy)


;;; ### Helper Fns

(defn do-operation [operation-type model arg]
  (let [f (partial operation operation-type model)]
    (do-operation-with-advice operation-type model f arg)))

(defn operation-keyword [operation-symb]
  (if (namespace operation-symb)
    (keyword operation-symb)
    (keyword (name (ns-name *ns*)) (name operation-symb))))

(defmacro defoperation
  {:arglists '([operation docstring? options?] [operation docstring? options? op-binding & body])}
  [operation-symb & args]
  (let [[docstring & args]     (if (string? (first args))
                                 args
                                 (cons nil args))
        [options & args]       (if (map? (first args))
                                 args
                                 (cons nil args))
        [[arg-binding] & body] args
        {:keys [parent]}       options
        operation-kw           (operation-keyword operation-symb)]
    `(do
       (dispatch/derive ~operation-kw ~(or parent :operation/any))

       ~@(when (seq body)
           `[(defmethod operation [~operation-kw :model/any]
               [~'&operation ~'&model ~(or arg-binding '_)]
               ~@body)])

       (def ~(vary-meta (symbol (name operation-symb)) (partial merge {:arglists   ''([model arg])
                                                                       ::operation operation-kw}))
         ~@(when docstring
             [docstring])
         (partial do-operation ~operation-kw)))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                             Operation Definitions                                              |
;;; +----------------------------------------------------------------------------------------------------------------+

;; TODO - should `query` and `execute!` be operations themselves?


;;; ----------------------------------------------------- select -----------------------------------------------------

(defoperation operation/select
  "Select something. That would be nice!"
  [query]
  (jdbc/query (connection/connection) query))

(defmethod apply-advice [:operation/select :advice/after :behavior/apply-combined :model/any]
  [op advice-type behavior model results]
  (let [parent-method (get-method apply-advice [:operation/select-reducible advice-type behavior model])]
    (seq (parent-method op advice-type behavior model results))))



;;; ------------------------------------------------ select-reducible ------------------------------------------------

(dispatch/derive :operation/select-reducible :operation/select)

(defmethod apply-advice [:operation/select-reducible :advice/after :behavior/apply-combined :model/any]
  [op advice-type _ model results]
  (let [f (comp (map (partial instance/of model))
                (map (dispatch/combined-method [advice op advice-type] model)))]
    (eduction f results)))


;;; ----------------------------------------------------- Others -----------------------------------------------------

(dispatch/derive :operation/insert! :operation/any)
(dispatch/derive :operation/update! :operation/any)
(dispatch/derive :operation/delete! :operation/any)

;; TODO - unify behaviors somehow, or perhaps differentiate between external "behavior" (e.g. debug) and internal
;; "strategy" (e.g. no-pre-post)

(dispatch/derive :model/any :default)

(dispatch/derive ::Parent :model/any)
(dispatch/derive ::Child :model/any)

(defn- x
  ([]
   (x ::Parent))


  ([model]
   (select model "SELECT * FROM venues LIMIT 2;")))

(def-after-advice select ::Parent [results]
  results)

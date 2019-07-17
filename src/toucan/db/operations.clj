(ns toucan.db.operations
  (:require [clojure.java.jdbc :as jdbc]
            [toucan
             [connection :as connection]
             [dispatch :as dispatch]
             [instance :as instance]
             [models :as models]]
            [toucan.util :as u]
            [toucan.debug :as debug]))

(doseq [[symb] (ns-interns *ns*)]
  (ns-unmap *ns* symb))

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

(defmacro defadvice {:style/indent 4} [operation advice-type model [arg-binding] & body]
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

(dispatch/derive :behavior/apply-combined :behavior/any)
(dispatch/derive :behavior/no-combine     :behavior/any)

;; TODO - should default behavior also dispatch on model? So you could do something like `VenueNoPrePost` if you were
;; so inclined. Or use behavior-based caching
(defmulti default-behavior-for-operation
  {:arglists '([operation])}
  identity)

(defmethod default-behavior-for-operation :default
  [_]
  :behavior/apply-combined)

(def ^:dynamic *advice-behavior* nil)

;; TODO - should we also have a behavior that doesn't apply combined? (e.g. for `:query` and `execute!`

(defmulti apply-advice
  {:arglists '([operation advice-type behavior model arg])}
  dispatch/dispatch-4
  :hierarchy #'dispatch/hierarchy)

(defmethod apply-advice :default
  [_ _ _ _ arg]
  arg)


;;; #### Default Advice

(defmethod apply-advice [:operation/any :advice/any :behavior/no-combine :model/any]
  [op advice-type _ model arg]
  (advice op advice-type model arg))

;; `around-all` and `before` both combine in reversed order; `around` and `after` both combine in normal order
(dispatch/derive :advice/around-all ::combine-reverse)
(dispatch/derive :advice/before     ::combine-reverse)
(dispatch/derive :advice/around     ::combine-normally)
(dispatch/derive :advice/after      ::combine-normally)

(defmethod apply-advice [:operation/any ::combine-reverse :behavior/apply-combined :model/any]
  [op advice-type _ model arg]
  ((dispatch/combined-method [advice op advice-type] model reverse) arg))

(defmethod apply-advice [:operation/any ::combine-normally :behavior/apply-combined :model/any]
  [op advice-type _ model arg]
  ((dispatch/combined-method [advice op advice-type] model reverse) arg))


;;; ### Predefined Advice Behaviors

;;; #### Debugging

(dispatch/derive :behavior/debug :behavior/any)

(defmethod apply-advice [:operation/any :advice/any :behavior/debug :model/any]
  [op advice-type _ model arg]
  (printf "[%s] Invoking %s advice for model %s, arg:\n" op advice-type model) ;; WOW!
  (println arg)
  (apply-advice op advice-type (default-behavior-for-operation op) model arg))

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
  (let [behavior     (or *advice-behavior* (default-behavior-for-operation operation))
        apply-advice (fn [advice-type arg]
                       (debug/debug-println (list 'apply-advice operation advice-type behavior model (class arg)))
                       (apply-advice operation advice-type behavior model arg))
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
  (let [parent-method (u/get-method-for-dispatch-value apply-advice :operation/select-reducible advice-type behavior model results)]
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

(dispatch/derive ::cached-behavior :behavior/any)

(def Venue ::Venue)
(dispatch/derive Venue :model/any)

(def-after-advice select Venue [result]
  (assoc result :after-venue? true))

(def ^:private cache
  (atom {}))

(defn- cached-fn [f]
  (fn [query]
    (or
     (when-let [cached-results (get @cache query)]
       (println "CACHED!!")
       cached-results)
     (println "Fetching from DB...")
     (let [results (f query)]
       (swap! cache assoc query results)
       results))))

;; behavior-based caching

(defmethod apply-advice [:operation/any :advice/around ::cached-behavior :model/any]
  [op advice-type behavior model f]
  (apply-advice op advice-type (default-behavior-for-operation op) model (cached-fn f)))


(defn y []
  (binding [*advice-behavior* ::cached-behavior]
    (select ::Child "SELECT * FROM venues LIMIT 2;")))


;; derived model caching

(dispatch/derive ::CachedModel :model/any)

(defmethod operation [:operation/select ::CachedModel]
  [op model query]
  (let [other-parent-model (first (disj (parents dispatch/hierarchy model) ::CachedModel))
        method             (u/get-method-for-dispatch-value operation op other-parent-model query)]
    ((cached-fn (partial method op model)) query)))

(defmacro def-cached-model [model-name parent]
  `(do
     (def ~model-name
       ~(keyword (name (ns-name *ns*)) (str (name model-name) "_")))
     (dispatch/derive ~model-name ~parent)
     (dispatch/derive ~model-name ::CachedModel)))

(def-cached-model CachedVenue Venue)

(defn z []
  (select CachedVenue "SELECT * FROM venues LIMIT 2;"))

;; wrapped model caching

(dispatch/derive ::cached-wrapper :model/any)

(defmethod do-operation [:operation/select ::cached-wrapper]
  [op [_ wrapped-model] query]
  ((cached-fn (partial do-operation op wrapped-model)) query))

(defn wrap-cached [model]
  [::cached-wrapper model])

(defn- b []
  (select (wrap-cached Venue) "SELECT * FROM venues LIMIT 2;"))

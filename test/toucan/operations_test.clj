(ns toucan.operations-test
  (:require [toucan
             [dispatch :as dispatch]
             [operations :as ops]
             [util :as u]]))

;; TODO - unify strategys somehow, or perhaps differentiate between external "strategy" (e.g. debug) and internal
;; "strategy" (e.g. no-pre-post)

(dispatch/derive :model/any :default)

(dispatch/derive ::Parent :model/any)
(dispatch/derive ::Child :model/any)

(ops/defafter ops/select ::Parent [results]
  results)

(dispatch/derive ::cached-strategy :strategy/any)

(def ^:private Venue ::Venue)

(dispatch/derive Venue :model/any)

(ops/defafter ops/select Venue [result]
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

;; strategy-based caching

(defmethod ops/apply-advice [:operation/any :advice/around ::cached-strategy :model/any]
  [op advice-type strategy model f]
  (ops/apply-advice op advice-type (ops/default-strategy-for-operation op) model (cached-fn f)))


(defn y []
  (binding [ops/*advice-strategy* ::cached-strategy]
    (ops/select ::Child "SELECT * FROM venues LIMIT 2;")))

;; derived model caching

(dispatch/derive ::CachedModel :model/any)

(defmethod ops/operation [:operation/select ::CachedModel]
  [op model query]
  (let [other-parent-model (first (disj (parents dispatch/hierarchy model) ::CachedModel))
        method             (u/get-method-for-dispatch-value ops/operation op other-parent-model query)]
    ((cached-fn (partial method op model)) query)))

(defmacro ^:private def-cached-model [model-name parent]
  `(do
     (def ~model-name
       ~(keyword (name (ns-name *ns*)) (str (name model-name) "_")))
     (dispatch/derive ~model-name ~parent)
     (dispatch/derive ~model-name ::CachedModel)))

(def-cached-model ^:private CachedVenue Venue)

(defn- z []
  (ops/select CachedVenue "SELECT * FROM venues LIMIT 2;"))

;; wrapped model caching

(dispatch/derive ::cached-wrapper :model/any)

(defmethod ops/do-operation [:operation/select ::cached-wrapper]
  [op [_ wrapped-model] query]
  ((cached-fn (partial ops/do-operation op wrapped-model)) query))

(defn- wrap-cached [model]
  [::cached-wrapper model])

(defn- b []
  (ops/select (wrap-cached Venue) "SELECT * FROM venues LIMIT 2;"))

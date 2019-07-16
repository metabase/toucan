(ns toucan.dispatch
  (:refer-clojure :exclude [derive])
  (:require [flatland.ordered.map :as ordered-map]
            [potemkin.types :as p.types]
            [pretty.core :as pretty]))

(defonce hierarchy
  (make-hierarchy))

(defn derive [child parent]
  (alter-var-root #'hierarchy clojure.core/derive child parent))

(p.types/defprotocol+ DispatchValue
  (dispatch-value* [this]))

(declare dispatch-value-with-meta)

(extend-protocol DispatchValue
  Object
  (dispatch-value* [_] nil)

  clojure.lang.Sequential
  (dispatch-value* [this] (dispatch-value-with-meta (first this))))


(p.types/defprotocol+ ^:private DispatchValueIncludingMeta
  (^:private dispatch-value-with-meta [this]))

(extend-protocol DispatchValueIncludingMeta
  clojure.lang.IMeta
  (dispatch-value-with-meta [this]
    (or
     (:toucan/dispatch (.meta ^clojure.lang.IMeta this))
     (dispatch-value* this))))

;; somewhat faster to call use these functions directly instead of providing new fns via `extend-protocol`
(extend Object
  DispatchValueIncludingMeta
  {:dispatch-value-with-meta dispatch-value*})

(extend nil
  DispatchValueIncludingMeta
  {:dispatch-value-with-meta identity})

(extend clojure.lang.Keyword
  DispatchValueIncludingMeta
  {:dispatch-value-with-meta identity})

(defn dispatch-value
  ([x]             (dispatch-value-with-meta x))
  ([x _]           (dispatch-value-with-meta x))
  ([x _ _]         (dispatch-value-with-meta x))
  ([x _ _ _]       (dispatch-value-with-meta x))
  ([x _ _ _ _]     (dispatch-value-with-meta x))
  ([x _ _ _ _ & _] (dispatch-value-with-meta x)))

(defn the-dispatch-value [x & _]
  (or (dispatch-value x)
      (throw (ex-info (format "Invalid dispatch value: %s" x) {:value x}))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                           Aspects & combined-method                                            |
;;; +----------------------------------------------------------------------------------------------------------------+

;; TODO - are we sure this belongs here, and not in `models`?
;; TODO - dox
(defmulti aspects
  {:arglists '([model])}
  dispatch-value
  :hierarchy #'hierarchy)

(defmethod aspects :default
  [_]
  nil)

;; TODO - dox
(defn all-aspects
  ([x]
   (all-aspects x #{}))

  ([x already-seen]
   (concat
    (reduce
     (fn [acc aspect]
       (if (already-seen aspect)
         acc
         (concat acc (all-aspects aspect (into already-seen acc)))))
     []
     (aspects x))
    [x])))

;; TODO - dox
(defn all-aspect-methods
  ([multifn model]
   (let [[multifn & args] (if (sequential? multifn)
                            multifn
                            [multifn])]
     (all-aspect-methods multifn args model)))

  ([multifn multifn-args model]
   {:pre [(instance? clojure.lang.MultiFn multifn) (some? model)]}
   (let [default-method (get-method multifn :default)]
     (into
      (ordered-map/ordered-map
       (when default-method
         {:default default-method}))
      (for [aspect (all-aspects model)
            :let   [method (get-method multifn (concat multifn-args [(dispatch-value aspect)]))]
            :when  (not (identical? method default-method))]
        [aspect (if (seq multifn-args)
                  (apply partial method multifn-args)
                  method)])))))

(defn combined-method [multifn model & [all-methods-xform]]
  (reduce
   (fn [f [dispatch-value method]]
     (fn [arg]
       (method dispatch-value (f arg))))
   identity
   (let [all-methods (all-aspect-methods multifn model)]
     (cond-> all-methods
       all-methods-xform all-methods-xform))))

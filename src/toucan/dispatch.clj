(ns toucan.dispatch
  (:refer-clojure :exclude [derive underive])
  (:require [flatland.ordered.map :as ordered-map]
            [potemkin.types :as p.types]
            [pretty.core :as pretty]
            [toucan.debug :as debug]
            [toucan.util :as u]))

(defonce hierarchy
  (make-hierarchy))

(defn derive [child parent]
  (alter-var-root #'hierarchy clojure.core/derive child parent))

(defn underive [child parent]
  (alter-var-root #'hierarchy clojure.core/underive child parent))

(defn model? [x]
  (isa? hierarchy :model/any))

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
            :let   [method (apply u/get-method-for-dispatch-value multifn (concat multifn-args [(dispatch-value aspect)]))]
            :when  (not (identical? method default-method))]
        [aspect (if (seq multifn-args)
                  (apply partial method multifn-args)
                  method)])))))

(defn combined-method [multifn model & [all-methods-xform]]
  (debug/debug-println (format "Combining methods of %s for model %s (all aspects: %s)" multifn model (vec (all-aspects model))))
  (reduce
   (fn [f [dispatch-value method]]
     (fn [arg]
       (debug/debug-println (list method dispatch-value (list f arg)))
       (method dispatch-value (f arg))))
   identity
   (when-let [all-methods (seq (all-aspect-methods multifn model))]
     (debug/debug-println (format "Combining methods %s (xform: %s)" all-methods all-methods-xform))
     ((or all-methods-xform identity) all-methods))))

(defn dispatch-2
  ([x model]           [x (dispatch-value model)])
  ([x model _]         [x (dispatch-value model)])
  ([x model _ _]       [x (dispatch-value model)])
  ([x model _ _ _]     [x (dispatch-value model)])
  ([x model _ _ _ & _] [x (dispatch-value model)]))

(defn dispatch-3
  ([x y model]         [x y (dispatch-value model)])
  ([x y model _]       [x y (dispatch-value model)])
  ([x y model _ _]     [x y (dispatch-value model)])
  ([x y model _ _ & _] [x y (dispatch-value model)]))

(defn dispatch-4
  ([x y z model]       [x y z (dispatch-value model)])
  ([x y z model _]     [x y z (dispatch-value model)])
  ([x y z model _ & _] [x y z (dispatch-value model)]))

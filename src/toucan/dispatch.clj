(ns toucan.dispatch
  (:require [flatland.ordered.map :as ordered-map]
            [toucan.instance :as instance]))

;; NOCOMMIT
(doseq [[symb] (ns-interns *ns*)]
  (ns-unmap *ns* symb))

(def hierarchy
  (make-hierarchy))

;; TODO - `derive`

;; TODO - fix confusion between `type`, `aspect`, and `model`
(defn toucan-type [x & _]
  (or
   (instance/model x)
   (if (map? x)
     (:toucan/type x)
     (keyword x))))

(defmulti aspects
  {:arglists '([model])}
  toucan-type
  :hierarchy #'hierarchy)

(defmethod aspects :default
  [_]
  nil)

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

(defn all-aspect-methods [multifn model]
  {:pre [(instance? clojure.lang.MultiFn multifn) (some? model)]}
  (let [default-method (get-method multifn :default)]
    (into
     (ordered-map/ordered-map
      (when default-method
        {:default default-method}))
     (for [aspect (all-aspects model)
           :let   [method (get-method multifn (toucan-type aspect))]
           :when  (not (identical? method default-method))]
       [aspect method]))))

(defn combined-method [method model]
  (reduce
   (fn [f [dispatch-value method]]
     (fn [arg]
       (method dispatch-value (f arg))))
   identity
   (all-aspect-methods method model)))

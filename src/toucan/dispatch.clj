(ns toucan.dispatch
  (:require [flatland.ordered.map :as ordered-map]
            [toucan.instance :as instance]))

#_(doseq [[symb] (ns-interns *ns*)]
  (ns-unmap *ns* symb))



;; TODO - rename to `dispatch-value`?
(defn dispatch-value [x & _]
  (instance/model x))

;; TODO - are we sure this belongs here, and not in `models`?
;; TODO - dox
(defmulti aspects
  {:arglists '([model])}
  dispatch-value)

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
(defn all-aspect-methods [multifn model]
  {:pre [(instance? clojure.lang.MultiFn multifn) (some? model)]}
  (let [default-method (get-method multifn :default)]
    (into
     (ordered-map/ordered-map
      (when default-method
        {:default default-method}))
     (for [aspect (all-aspects model)
           :let   [method (get-method multifn (dispatch-value aspect))]
           :when  (not (identical? method default-method))]
       [aspect method]))))

(defn combined-method [multifn model & [all-methods-xform]]
  (reduce
   (fn [f [dispatch-value method]]
     (fn [arg]
       (method dispatch-value (f arg))))
   identity
   (let [all-methods (all-aspect-methods multifn model)]
     (cond-> all-methods
       all-methods-xform all-methods-xform))))

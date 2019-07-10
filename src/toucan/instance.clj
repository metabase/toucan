(ns toucan.instance
  (:require [clojure.pprint :as pprint]
            [potemkin :as potemkin]
            [pretty.core :as pretty]))

;; NOCOMMIT
(doseq [[symb] (ns-interns *ns*)]
  (ns-unmap *ns* symb))

;; TODO - make a Toucan instance invokable

#_(defn- ifn-invoke-forms
  "Macro helper, generates

       (~'invoke [this#]
        (invoke-model-or-instance this#))
       (~'invoke [this# id#]
        (invoke-model-or-instance this# id#))
       (~'invoke [this# arg1# arg2#]
        (invoke-model-or-instance this# arg1# arg2#))
       ,,,"
  []
  (let [args (map #(symbol (str "arg" %)) (range 1 19))
        arg-lists (reductions conj ['this] args)]
    (for [l arg-lists]
      (list 'invoke l (concat `(invoke-model-or-instance) l)))))

(potemkin/def-map-type ToucanInstance [model original m mta]
  (get [_ k default-value]
       (get m k default-value))
  (assoc [_ k v]
         (ToucanInstance. model original (assoc m k v) mta))
  (dissoc [_ k]
          (ToucanInstance. model original (dissoc m k) mta))
  (keys [_]
        (keys m))
  (meta [_]
        mta)
  (with-meta [_ mta]
    (ToucanInstance. model original m mta)))

(extend-protocol pretty/PrettyPrintable
  ToucanInstance
  (pretty [this]
    (list 'toucan.instance/of (.model ^ToucanInstance this) (.m ^ToucanInstance this))))

(defmethod print-method ToucanInstance
  [this writer]
  (print-method (pretty/pretty this) writer))

(defmethod pprint/simple-dispatch ToucanInstance
  [this]
  (pretty/pretty this))

(defn of
  ([model]
   (of model {}))

  ([model m]
   (ToucanInstance. model m m (meta m))))

(defprotocol ToucanModel
  (model [this]))

(extend-protocol ToucanModel
  ToucanInstance
  (model [this] (.model ^ToucanInstance this))
  Object
  (model [_] nil)
  nil
  (model [_] nil))

(defprotocol ToucanOriginal
  (original [this]))

(extend-protocol ToucanOriginal
  ToucanInstance
  (original [this] (.original ^ToucanInstance this))
  Object
  (original [_] nil)
  nil
  (original [_] nil))

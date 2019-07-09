(ns toucan.instance
  (:require #_[pretty.core :as pretty]
            [potemkin :as potemkin]))

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

;; TODO - implement `pretty`

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

(ns toucan.instance
  (:require [clojure.data :as data]
            [potemkin
             [collections :as p.collections]
             [types :as p.types]]
            [pretty.core :as pretty]
            [toucan.dispatch :as dispatch]))

;; TODO - dox
(p.types/defprotocol+ Original
  (original [this]))

(extend-protocol Original
  Object
  (original [_] nil)

  nil
  (original [_] nil))

;; TODO - dox ?
(p.types/deftype+ ToucanInstance [^clojure.lang.Keyword model, orig m mta]
  dispatch/DispatchValue
  (dispatch-value* [_] model)

  p.collections/AbstractMap
  (get*       [_ k default-value] (get m k default-value))
  (assoc*     [_ k v]             (ToucanInstance. model orig (assoc m k v) mta))
  (dissoc*    [_ k]               (ToucanInstance. model orig (dissoc m k) mta))
  (keys*      [_]                 (keys m))
  (meta*      [_]                 mta)
  (with-meta* [_ new-meta]        (ToucanInstance. model orig m new-meta))

  Original
  (original [_]
    orig)

  clojure.lang.IPersistentCollection
  (empty [_]
    (ToucanInstance. model (empty orig) (empty m) mta))

  ;; TODO - not sure if want
  clojure.lang.Named
  (getName      [_] (name model))
  (getNamespace [_] (namespace model))

  pretty/PrettyPrintable
  (pretty [_]
    ;; TODO - `toucan.db/instance-of` (?)
    (if (seq m)
      (list 'toucan.instance/of model m)
      (list 'toucan.instance/of model)))

  clojure.lang.IFn
  (applyTo [this arglist]
    (apply m arglist))
  (invoke [_ k]
    (get m k))
  (invoke [_ k not-found]
    (get m k not-found)))

(ns-unmap 'toucan.instance '->ToucanInstance)

(defmulti instance-type
  {:arglists '([model])}
  dispatch/dispatch-value
  :hierarchy #'dispatch/hierarchy)

(defmethod instance-type :default
  [model]
  model)


(defn toucan-instance [model orig m mta]
  (ToucanInstance. (dispatch/the-dispatch-value (instance-type model)) orig m mta))

;; TODO - dox
(defn of
  {:style/indent 1}
  (^toucan.instance.ToucanInstance [model]
   (toucan-instance model nil nil nil))

  ;; TODO - not 100% sure calling `model` here makes sense... what if we do something like the following (see below)
  (^toucan.instance.ToucanInstance [model m]
   (assert ((some-fn nil? map?) m)
           (format "Not a map: %s" m))
   (toucan-instance model m m (meta m))))

(defn changes [m]
  (when (seq m)
    (second (data/diff (original m) m))))

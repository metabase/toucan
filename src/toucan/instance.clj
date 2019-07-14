(ns toucan.instance
  (:require [clojure.data :as data]
            [honeysql.format :as hsql.format]
            [potemkin
             [collections :as p.collections]
             [types :as p.types]]
            [pretty.core :as pretty]))


;; TODO - rename to `type`?
(p.types/defprotocol+ Model
  (model [this]))

;; TODO - dox
(defn the-model [x]
  (or (model x)
      (throw (ex-info (format "Not valid model found for %s" x) {:value x}))))

;; TODO - dox
(defn dispatch-on-model [x & args]
  (model x))

;; TODO - dox
(p.types/defprotocol+ Original
  (original [this]))

(defmulti table
  ;; TODO - dox
  {:arglists '([x])}
  dispatch-on-model)

(defmethod table :default [x]
  (throw
   (ex-info
    (str (model x)
         " does not have a table associated with it. This might be because it's an aspect; if not, you can specify its"
         " `table` by implementing `toucan.models/table` or by passing a `(table ...)` option to its `defmodel` form.")
    {:arg x, :model (model x)})))


;; TODO - dox ?
;; `modl` is always a keyword (?)
(p.types/deftype+ ToucanInstance [modl orig m mta]
  Model
  (model [_]
    modl)

  Original
  (original [_]
    orig)

  p.collections/AbstractMap
  (get*       [_ k default-value] (get m k default-value))
  (assoc*     [_ k v]             (ToucanInstance. modl orig (assoc m k v) mta))
  (dissoc*    [_ k]               (ToucanInstance. modl orig (dissoc m k) mta))
  (keys*      [_]                 (keys m))
  (meta*      [_]                 mta)
  (with-meta* [_ new-meta]        (ToucanInstance. modl orig m new-meta))

  clojure.lang.IPersistentCollection
  (empty [_]
    (ToucanInstance. modl {} {} mta))

  clojure.lang.Named
  (getName [_]
    (name modl))
  (getNamespace [_]
    (namespace modl))

  pretty/PrettyPrintable
  (pretty [_]
    ;; TODO - `toucan.db/instance-of` (?)
    (if (seq m)
      (list 'toucan.instance/of modl m)
      (list 'toucan.instance/of modl)))

  hsql.format/ToSql
  (to-sql [_]
    (table modl))

  clojure.lang.IFn
  (applyTo [this arglist]
    (apply m arglist))
  (invoke [_ k]
    (get m k))
  (invoke [_ k not-found]
    (get m k not-found)))

(extend-protocol Model
  clojure.lang.Keyword
  (model [this] this)

  clojure.lang.IPersistentMap
  (model [this]
    ;; TODO - fix this HACKINESS
    (if (instance? ToucanInstance this)
      (do
        (println "FIXME: `model` is being hacky!") ; NOCOMMIT
        (.modl ^ToucanInstance this))
      (:toucan/model this)))

  clojure.lang.IPersistentVector
  (model [this]
    (model (first this)))

  Object
  (model [_] nil)

  nil
  (model [_] nil))

(extend-protocol Original
  Object
  (original [_] nil)

  nil
  (original [_] nil))

(defn toucan-instance [model orig m mta]
  (ToucanInstance. (the-model model) orig m mta))

;; TODO - dox
(defn of
  ([a-model]
   (of a-model {}))

  ;; TODO - not 100% sure calling `model` here makes sense... what if we do something like the following (see below)
  ([a-model m]
   {:pre [((some-fn nil? map?) m)]}
   (toucan-instance a-model m m (meta m))))

(defn changes [m]
  (when m
    (second (data/diff (original m) m))))

;; TODO - `instance-of?` fn

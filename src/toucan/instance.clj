(ns toucan.instance
  (:require [clojure.data :as data]
            [honeysql.format :as hsql.format]
            [potemkin
             [collections :as p.collections]
             [types :as p.types]]
            [pretty.core :as pretty]))

;; TODO - dox
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

(defmulti invoke-instance
  ;; TODO - dox
  {:arglists '([instance & args])}
  dispatch-on-model)
;; TODO - implement elsewhere

(defmulti table
  ;; TODO - dox
  {:arglists '([x])}
  model)

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

  java.util.concurrent.Callable
  (call [_]
    (invoke-instance modl))

  java.lang.Runnable
  (run [_]
    (invoke-instance modl))

  clojure.lang.IFn
  (applyTo [this arglist]
    (apply invoke-instance modl arglist))
  (invoke [_]
    (invoke-instance modl))
  (invoke [_ a]
    (invoke-instance modl a))
  (invoke [_ a b]
    (invoke-instance modl a b))
  (invoke [_ a b c]
    (invoke-instance modl a b c))
  (invoke [_ a b c d]
    (invoke-instance modl a b c d))
  (invoke [_ a b c d e]
    (invoke-instance modl a b c d e))
  (invoke [_ a b c d e f]
    (invoke-instance modl a b c d e f))
  (invoke [_ a b c d e f g]
    (invoke-instance modl a b c d e f g))
  (invoke [_ a b c d e f g h]
    (invoke-instance modl a b c d e f g h))
  (invoke [_ a b c d e f g h i]
    (invoke-instance modl a b c d e f g h i))
  (invoke [_ a b c d e f g h i j]
    (invoke-instance modl a b c d e f g h i j))
  (invoke [_ a b c d e f g h i j k]
    (invoke-instance modl a b c d e f g h i j k))
  (invoke [_ a b c d e f g h i j k l]
    (invoke-instance modl a b c d e f g h i j k l))
  (invoke [_ a b c d e f g h i j k l m]
    (invoke-instance modl a b c d e f g h i j k l m))
  (invoke [_ a b c d e f g h i j k l m n]
    (invoke-instance modl a b c d e f g h i j k l m n))
  (invoke [_ a b c d e f g h i j k l m n o]
    (invoke-instance modl a b c d e f g h i j k l m n o))
  (invoke [_ a b c d e f g h i j k l m n o p]
    (invoke-instance modl a b c d e f g h i j k l m n o p))
  (invoke [_ a b c d e f g h i j k l m n o p q]
    (invoke-instance modl a b c d e f g h i j k l m n o p q))
  (invoke [_ a b c d e f g h i j k l m n o p q r]
    (invoke-instance modl a b c d e f g h i j k l m n o p q r))
  (invoke [_ a b c d e f g h i j k l m n o p q r s]
    (invoke-instance modl a b c d e f g h i j k l m n o p q r s))
  (invoke [_ a b c d e f g h i j k l m n o p q r s t]
    (invoke-instance modl a b c d e f g h i j k l m n o p q r s t))
  (invoke [_ a b c d e f g h i j k l m n o p q r s t more]
    (invoke-instance modl a b c d e f g h i j k l m n o p q r s t more)))

(extend-protocol Model
  clojure.lang.Keyword
  (model [this] this)

  clojure.lang.Symbol
  (model [this] (keyword this))

  String
  (model [this] (keyword this))

  clojure.lang.IPersistentMap
  (model [this]
    ;; TODO - fix this HACKINESS
    (if (instance? ToucanInstance this)
      (do
        (println "FIXME: `model` is being hacky!")
        (.modl ^ToucanInstance this))
      (:toucan/model this)))

  nil
  (model [_] nil))

(extend-protocol Original
  Object
  (original [_] nil)

  nil
  (original [_] nil))


;; TODO - dox
(defn of
  ([a-model]
   (of a-model {}))

  ;; TODO - not 100% sure calling `model` here makes sense... what if we do something like the following (see below)
  ([a-model m]
   (ToucanInstance. (the-model a-model) m m (meta m))))

(defn changes [m]
  (when m
    (second (data/diff (original m) m))))

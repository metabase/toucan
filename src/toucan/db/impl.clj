(ns toucan.db.impl
  (:require [clojure.string :as str]
            [toucan.models :as models]))

(def ^:private ^:dynamic *call-count*
  "Atom used as a counter for DB calls when enabled. This number isn't *perfectly* accurate, only mostly; DB calls
  made directly to JDBC won't be logged."
  nil)

(defn -do-with-call-counting
  "Execute F with DB call counting enabled. F is passed a single argument, a function that can be used to retrieve the
  current call count. (It's probably more useful to use the macro form of this function, `with-call-counting`,
  instead.)"
  {:style/indent 0}
  [f]
  (binding [*call-count* (atom 0)]
    (f (partial deref *call-count*))))

(defn inc-call-count!
  ;; TODO - dox
  []
  (when *call-count*
    (swap! *call-count* inc)))

(defn format-sql [sql]
  (when sql
    (loop [sql sql, [k & more] ["FROM" "LEFT JOIN" "INNER JOIN" "WHERE" "GROUP BY" "HAVING" "ORDER BY" "OFFSET"
                                "LIMIT"]]
      (if-not k
        sql
        (recur (str/replace sql (re-pattern (format "\\s+%s\\s+" k)) (format "\n%s " k))
               more)))))

(def ^:dynamic *debug*
  ;; TODO - dox
  false)

(defmacro debug-println
  ;; TODO - dox
  [& args]
  `(when *debug*
     (println ~@args)))

(defn- replace-underscores
  "Replace underscores in `k` with dashes. In other words, converts a keyword from `:snake_case` to `:lisp-case`.

     (replace-underscores :2_cans) ; -> :2-cans"
  ^clojure.lang.Keyword [k]
  ;; if k is not a string or keyword don't transform it
  (if-not ((some-fn string? keyword?) k)
    k
    (let [k-str (u/keyword->qualified-name k)]
      (if (s/index-of k-str \_)
        (keyword (s/replace k-str \_ \-))
        k))))

(defn- transform-keys
  "Replace the keys in any maps in `x` with the result of `(f key)`. Recursively walks `x` using `clojure.walk`."
  [f x]
  (walk/postwalk
   (fn [y]
     (if-not (map? y)
       y
       (into {} (for [[k v] y]
                  [(f k) v]))))
   x))

(defn do-pre-select [model honeysql-form]
  ((dispatch/combined-method models/pre-select model :in) honeysql-form))

(defn post-select-fn [model]
  (dispatch/combined-method models/post-select model :out))

(defn do-pre-update [instance]
  ((dispatch/combined-method models/pre-update instance :in) instance))

(defn do-post-update [instance]
  ((dispatch/combined-method models/post-update instance :in) instance))

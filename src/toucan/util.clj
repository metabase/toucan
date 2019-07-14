(ns toucan.util
  "Utility functions used by other Toucan modules."
  (:require [clojure
             [string :as str]
             [walk :as walk]]))

(defn keyword->qualified-name
  "Return keyword `k` as a string, including its namespace, if any (unlike `name`).

     (keyword->qualified-name :type/FK) -> \"type/FK\""
  [k]
  (if (and (keyword? k)
           (namespace k))
    (str (namespace k) "/" (name k))
    (name k)))

(defn replace-underscores
  "Replace underscores in `k` with dashes. In other words, converts a keyword from `:snake_case` to `:lisp-case`.

     (replace-underscores :2_cans) ; -> :2-cans"
  ^clojure.lang.Keyword [k]
  ;; if k is not a string or keyword don't transform it
  (if-not ((some-fn string? keyword?) k)
    k
    (let [k-str (keyword->qualified-name k)]
      (if (str/index-of k-str \_)
        (keyword (str/replace k-str \_ \-))
        k))))

(defn transform-keys
  "Replace the keys in any maps in `x` with the result of `(f key)`. Recursively walks `x` using `clojure.walk`."
  [f x]
  (walk/postwalk
   (fn [y]
     (if-not (map? y)
       y
       (into {} (for [[k v] y]
                  [(f k) v]))))
   x))

(defn format-sql [sql]
  (when sql
    (loop [sql sql, [k & more] ["FROM" "LEFT JOIN" "INNER JOIN" "WHERE" "GROUP BY" "HAVING" "ORDER BY" "OFFSET"
                                "LIMIT"]]
      (if-not k
        sql
        (recur (str/replace sql (re-pattern (format "\\s+%s\\s+" k)) (format "\n%s " k))
               more)))))

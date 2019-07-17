(ns toucan.debug)

(def ^:dynamic *debug*
  ;; TODO - dox
  false)

;; TODO - rename to `println`
;; TODO - different levels? `println-trace`, etc. or just `trace`
(defmacro debug-println
  ;; TODO - dox
  [& args]
  `(when *debug*
     (println ~@args)))

(defmacro debug
  "Print the HoneySQL and SQL forms of any queries executed inside `body` to `stdout`. Intended for use during REPL
  development."
  {:style/indent 0}
  [& body]
  `(binding [*debug* true]
     ~@body))

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

(defmacro with-call-counting
  "Execute `body` and track the number of DB calls made inside it. `call-count-fn-binding` is bound to a zero-arity
  function that can be used to fetch the current DB call count.

     (db/with-call-counting [call-count] ...
       (call-count))"
  {:style/indent 1}
  [[call-count-fn-binding] & body]
  `(-do-with-call-counting (fn [~call-count-fn-binding] ~@body)))

(defmacro debug-count-calls
  "Print the number of DB calls executed inside `body` to `stdout`. Intended for use during REPL development."
  {:style/indent 0}
  [& body]
  `(with-call-counting [call-count#]
     (let [results# (do ~@body)]
       (println "DB Calls:" (call-count#))
       results#)))

(ns toucan.util.test
  "Utility functions for writing tests with Toucan models."
  (:require [toucan.db :as db]
            [potemkin.types :as p.types]))

;;;                                                    TEMP OBJECTS
;;; ==================================================================================================================

;; For your convenience Toucan makes testing easy with *Temporary Objects*. A temporary object is created and made
;; available to some body of code, and then wiped from that database via a `finally` statement (i.e., whether the body
;; completes successfully or not). This makes it easy to write tests that do not change your test database when they
;; are ran.
;;
;; Here's an example of a unit test using a temporary object created via `with-temp`:
;;
;;     ;; Make sure newly created users aren't admins
;;     (expect false
;;       (with-temp User [user {:first-name "Cam", :last-name "Saul"}]
;;         (is-admin? user)))
;;
;; In this example, a new instance of `User` is created (via the normal `insert!` pathway), and bound to `user`; the
;; body of `with-temp` (the `test-something` fncall) is executed. Immediately after, the `user` is removed from the
;; Database, but the entire statement returns the results of the body (hopefully `false`).
;;
;; Often a Model will require that many fields be `NOT NULL`, and specifying all of them in every test can get
;; tedious. In the example above, we don't care about the `:first-name` or `:last-name` of the user. We can provide
;; default values for temporary objects by implementing the `WithTempDefaults` protocol:
;;
;;     (defn- random-name
;;       "Generate a random name of 10 uppercase characters"
;;       []
;;       (apply str (map char (repeatedly 10 #(rand-nth (range (int \A) (inc (int \Z))))))))
;;
;;     (extend-protocol WithTempDefaults
;;       (class User)
;;       (with-temp-defaults [_] {:first-name (random-name), :last-name (random-name)}))
;;
;; Now whenever we use `with-temp` to create a temporary `User`, a random `:first-name` and `:last-name` will be
;; provided.
;;
;;     (with-temp User [user]
;;       user)
;;     ;; -> {:first-name "RIQGVIDTZN", :last-name "GMYROFEZYO", ...}
;;
;; You can still override any of the defaults, however:
;;
;;     (with-temp User [user {:first-name "Cam"}]
;;       user)
;;     ;; -> {:first-name "Cam", :last-name "OVTAAJBVOF"}
;;
;; Finally, Toucan provides a couple more advanced versions of `with-temp`. The first, `with-temp*`, can be used to
;; create multiple objects at once:
;;
;;     (with-temp* [User         [user]
;;                  Conversation [convo {:user_id (:id user)}]]
;;       ...)
;;
;; Each successive object can reference the temp object before it; the form is equivalent to writing multiple
;; `with-temp` forms.
;;
;; The last helper macro is available if you use the `expectations` unit test framework:
;;
;;     ;; Make sure our get-id function works on users
;;     (expect-with-temp [User [user {:first-name "Cam"}]]
;;       (:id user)
;;       (get-id user))
;;
;; This macro makes the temporary object available to both the "expected" and "actual" parts of the test. (PRs for
;; similar macros for other unit test frameworks are welcome!)


(p.types/defprotocol+ WithTempDefaults
  "Protocol defining the `with-temp-defaults` method, which provides default values for new temporary objects."
  (with-temp-defaults ^clojure.lang.IPersistentMap [this]
    "Return a map of default values that should be used when creating a new temporary object of this model.

       ;; Use a random first and last name for new temporary Users unless otherwise specified
       (extend-protocol WithTempDefaults
         (class User)
         (with-temp-defaults [_] {:first-name (random-name), :last-name (random-name)}))"))


;; default impl
(extend Object
  WithTempDefaults
  {:with-temp-defaults (constantly {})})


(defn do-with-temp
  "Internal implementation of `with-temp` (don't call this directly)."
  [model attributes f]
  (let [temp-object (db/insert! model (merge (when (satisfies? WithTempDefaults model)
                                               (with-temp-defaults model))
                                             attributes))]
    (try
      (f temp-object)
      (finally
        (db/delete! model :id (:id temp-object))))))


(defmacro with-temp
  "Create a temporary instance of ENTITY bound to BINDING-FORM, execute BODY,
   then deletes it via `delete!`.

   Our unit tests rely a heavily on the test data and make some assumptions about the
   DB staying in the same *clean* state. This allows us to write very concise tests.
   Generally this means tests should \"clean up after themselves\" and leave things the
   way they found them.

   `with-temp` should be preferrable going forward over creating random objects *without*
   deleting them afterward.

    (with-temp EmailReport [report {:creator_id (user->id :rasta)
                                    :name       (random-name)}]
      ...)"
  [model [binding-form & [options-map]] & body]
  `(do-with-temp ~model ~options-map (fn [~binding-form]
                                       ~@body)))

(defmacro with-temp*
  "Like `with-temp` but establishes multiple temporary objects at the same time.

     (with-temp* [Database [{database-id :id}]
                  Table    [table {:db_id database-id}]]
       ...)"
  [model-bindings & body]
  (loop [[pair & more] (reverse (partition 2 model-bindings)), body `(do ~@body)]
    (let [body `(with-temp ~@pair
                  ~body)]
      (if (seq more)
        (recur more body)
        body))))


;;;                                             EXPECTATIONS HELPER MACROS
;;; ==================================================================================================================

(defn- has-expectations-dependency? []
  (try (require 'expectations)
       true
       (catch Throwable _
         false)))

(when (has-expectations-dependency?)
  (defmacro expect-with-temp
    "Combines `expect` with a `with-temp*` form. The temporary objects established by `with-temp*` are available
     to both EXPECTED and ACTUAL.

     (expect-with-temp [Database [{database-id :id}]]
        database-id
        (get-most-recent-database-id))"
    {:style/indent 1}
    [with-temp*-form expected actual]
    ;; use `gensym` instead of auto gensym here so we can be sure it's a unique symbol every time. Otherwise since
    ;; expectations hashes its body to generate function names it will treat every usage of `expect-with-temp` as
    ;; the same test and only a single one will end up being ran
    (let [with-temp-form (gensym "with-temp-")]
      `(let [~with-temp-form (delay (with-temp* ~with-temp*-form
                                      [~expected ~actual]))]
         (expectations/expect
           ;; if dereferencing with-temp-form throws an exception then expect Exception <-> Exception will pass;
           ;; we don't want that, so make sure the expected is nil
           (try
             (first @~with-temp-form)
             (catch Throwable ~'_))
           (second @~with-temp-form))))))

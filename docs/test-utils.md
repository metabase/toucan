# Test Utilities

For your convenience Toucan makings testing easy with *Temporary Objects*.
A temporary object is created and made available to some body of code, and then wiped from that database via a `finally`
statement (i.e., whether the body completes successfully or not). This makes it easy to write tests that do not change
your test database when they are ran.

Here's an example of a unit test using a temporary object created via `with-temp`:

```clojure
;; Make sure newly created users aren't admins
(expect false
  (with-temp User [user {:first-name "Cam", :last-name "Saul"}]
    (is-admin? user)))
```

In this example, a new instance of `User` is created (via the normal `insert!` pathway), and bound to `user`; the body of
`with-temp` (the `test-something` fncall) is executed. Immediately after, the `user` is removed from the Database, but
the entire statement returns the results of the body (hopefully `false`).

Often a Model will require that many fields be `NOT NULL`, and specifying all of them in every test can get tedious. In the
example above, we don't care about the `:first-name` or `:last-name` of the user. We can provide default values for temporary
objects by implementing the `WithTempDefaults` protocol:

```clojure
(defn- random-name
  "Generate a random name of 10 uppercase characters"
  []
  (apply str (map char (repeatedly 10 #(rand-nth (range (int \A) (inc (int \Z))))))))

(extend-protocol WithTempDefaults
  (class User)
  (with-temp-defaults [_] {:first-name (random-name), :last-name (random-name)}))
```

Now whenever we use `with-temp` to create a temporary `User`, a random `:first-name` and `:last-name` will be provided.

```clojure
(with-temp User [user]
  user)
;; -> {:first-name "RIQGVIDTZN", :last-name "GMYROFEZYO", ...}
```

You can still override any of the defaults, however:

```clojure
(with-temp User [user {:first-name "Cam"}]
  user)
;; -> {:first-name "Cam", :last-name "OVTAAJBVOF"}
```

Finally, Toucan provides a couple more advanced versions of `with-temp`. The first, `with-temp*`, can be used to create
multiple objects at once:

```clojure
(with-temp* [User         [user]
             Conversation [convo {:user_id (:id user)}]]
  ...)
```

Each successive object can reference the temp object before it; the form is equivalent to writing multiple `with-temp` forms.

The last helper macro is available if you use the `expectations` unit test framework:

```clojure
;; Make sure our get-id function works on users
(expect-with-temp [User [user {:first-name "Cam"}]]
  (:id user)
  (get-id user))
```

This macro makes the temporary object available to both the "expected" and "actual" parts of the test. (PRs for similar macros
for other unit test frameworks are welcome!)

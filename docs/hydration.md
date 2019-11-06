# Hydration

If you're developing something like a REST API, there's a good chance at some point you'll want to *hydrate* some related objects
and return them in your response. For example, suppose we wanted to return a Venue with its Category hydrated:

```clojure
;; No hydration
{:name        "The Tempest"
 :category_id 120
 ...}

;; w/ hydrated :category
{:name        "The Tempest"
 :category_id 120
 :category    {:name "Dive Bar"
               ...}
 ...}
```

The code to do something like this is enormously hairy in vanilla JDBC + HoneySQL. Luckily, Toucan makes this kind of thing a piece
of cake:

```clojure
(hydrate (Venue 120) :category)
```

Toucan is clever enough to automatically figure out that Venue's `:category_id` corresponds to the `:id` of a Category, and constructs efficient queries
to fetch it. Toucan can hydrate single objects, sequences of objects, and even objects inside hydrated objects, all in an efficient way that minimizes
the number of DB calls. You can also define custom functions to hydrate different keys, and add additional mappings for automatic hydration, as
discussed below.

## Architecture

The following flowchart demonstrates how `hydrate` decides *how* to hydrate something:

```
                          hydrate <-------------+
                            |                   |
                        hydrate-many            |
                            | (for each form)   |
                        hydrate-1               | (recursively)
                            |                   |
                 keyword? --+-- vector?         |
                    |             |             |
               hydrate-kw    hydrate-vector ----+
                    |
           can-automagically-batched-hydrate?
                          |
         true ------------+----------------- false
          |                                    |
 automagically-batched-hydrate    can-fn-based-batched-hydrate?
                                              |
                            true -------------+------------- false
                             |                                 |
                  fn-based-batched-hydrate              simple-hydrate
```

The various methods of hydration are discussed in more detail below.


## Automagic Batched Hydration (via hydration-keys)

`hydrate` attempts to do a *batched hydration* where possible.
If the key being hydrated is defined as one of some model's `hydration-keys`,
`hydrate` will do a batched `db/select` if a corresponding key ending with `_id` or `-id`
is found in the objects being batch hydrated.

```clojure
(models/defmodel User :users
  models/IModel
  ;; tell Toucan to do batched hydration of the key `:user` by fetching instances of User with given `:id`s
  (hydration-keys [_]
    [:user]))

;; ... later, somewhere else ...

(hydrate [{:user_id 100}, {:user_id 101}] :user)

;; -> [{:user_id 100, :user {...}}
;;     {:user_id 101, :user {...}}]
;;
;; (the :user property is now hydrated by fetching the User with each :user_id)
```

Since `:user` is a hydration key for `User`, a single `db/select` will used to
fetch `Users`:

```clojure
(db/select User :id [:in #{100 101}])
```

The corresponding `Users` are then added under the key `:user`.


## Function-Based Batched Hydration (via functions marked ^:batched-hydrate)

If the key can't be hydrated auto-magically with the appropriate `:hydration-keys`,
`hydrate` will look for a function tagged with `:batched-hydrate` in its metadata, and
use that instead. If a matching function is found, it is called with a collection of objects,
e.g.

```clojure
(defn with-fields
  "Efficiently add `Fields` to a collection of `tables`."
  {:batched-hydrate :fields}
  [tables]
  ...)

(let [tables (get-some-tables)]
  (hydrate tables :fields))     ; uses with-fields
```

By default, the function will be used to hydrate keys that match its name; as in the example above,
you can specify a different key to hydrate for in the metadata instead.


## Simple Hydration (via functions marked ^:hydrate)

If the key is *not* eligible for batched hydration, `hydrate` will look for a function or method
tagged with `:hydrate` in its metadata, and use that instead; if a matching function
is found, it is called on the object being hydrated and the result is `assoc`ed:

```clojure
(defn ^:hydrate dashboard [{:keys [dashboard_id]}]
  (Dashboard dashboard_id))

(let [dc (DashboardCard ...)]
  (hydrate dc :dashboard))    ; roughly equivalent to (assoc dc :dashboard (dashboard dc))
```

As with `:batched-hydrate` functions, by default, the function will be used to hydrate keys that
match its name; you can specify a different key to hydrate instead as the metadata value of `:hydrate`:

```clojure
(defn ^{:hydrate :pk_field} pk-field-id [obj] ...) ; hydrate :pk_field with pk-field-id
```

Keep in mind that you can only define a single function/method to hydrate each key; move functions into the
`IModel` interface as needed.


## Hydrating Multiple Keys

You can hydrate several keys at one time:

```clojure
(hydrate {...} :a :b)
;; -> {:a 1, :b 2}
```

## Nested Hydration

You can do recursive hydration by listing keys inside a vector:

```clojure
(hydrate {...} [:a :b])
;; -> {:a {:b 1}}
```

The first key in a vector will be hydrated normally, and any subsequent keys
will be hydrated *inside* the corresponding values for that key.

```clojure
(hydrate {...}
  [:a [:b :c] :e])
;; -> {:a {:b {:c 1} :e 2}}
```

## Flushing the Hydration Key Caches for Interactive (REPL) Development

The functions that can be used to hydrate things are resolved and cached the first time you call `hydrate`. In an
interactive development environment (such as a REPL), you'll sometimes find yourself wanting to add new hydration
functions. Luckily, one simple call can flush the caches, allowing you to add new hydration functions as you please:

```clojure
(toucan.hydrate/flush-hydration-key-caches!)
```

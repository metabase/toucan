# Toucan DB Functions

Toucan provides a variety of DB functions that simplify common access patterns for querying the DB and inserting
or updating records using Toucan models. These functions can be found in the `toucan.db` namespace.

`toucan.db` functions work hand-in-hand with the [model functions found in `toucan.models`](defining-models.md). For example,
a model might define some custom behavior to take place whenever an instance is retrieved from the DB via the `select` family
of functions by providing an implementation for `toucan.models/post-select`.

## The core functions

The most important `toucan.db` functions are for the basic CRUD operations: the `select`, `insert!`, `update!`, and `delete!`
families of functions. Like their names imply, there are but simply sophisticated wrappers around the corresponding JDBC functions,
with built-in compilation of HoneySQL forms (and some syntactic sugar for creating them) and integration with the Toucan models and
their `IModel` method implementations.

### Fetching objects

The `select` family of functions is used to retrieve objects from the database.

```clojure
(db/select User) -> [...] ; return a sequence of Users
```

`select` returns a eagerly fetched sequence of model instances. You can restrict the set of values returned by passing key-value
pairs of columns and values:

##### Using key-value args to restrict results returned

```clojure
(db/select User :name "Cam") ; -> return a sequence of Users whose name is Cam
```

While it may seem magical, under the hood Toucan is just compiling these key-value arguments into HoneySQL forms and passing them
to `clojure.java.jdbc`. The example above gets compiled to function call like:

```clojure
(jdbc/query (db/connection)
  (sql/format {:select [:*], :from [:users], :where [:= :name "Cam"]}, :quoting (db/quoting-style)))
```

In turn, this generates SQL like:

```sql
SELECT *
FROM "users"
WHERE "name" = 'Cam'
```

But typing that all out would get repetitive, which is why Toucan simplifies things for you.


##### More advanced key-value args

Since it's all HoneySQL under the hood, you can also do things a bit more advanced when passing key-value arguments:

```clojure
(db/select User :name [:not= nil]) ; return a sequence of Users whose :name is non-nil
```

That gets compiled to a HoneySQL form with a where clause like:

```clojure
{:where [:not= :name nil], ...}
```

##### Selecting only certain fields

Nice. Now let's say you only want to select a certain group of fields from User:

```clojure
(db/select [User :first-name :last-name])
;; -> [{:first-name "Cam", :last-name "Saul"}
;;     {:first-name "Rasta", :last-name "Can"}]
```

By replacing the model with a vector of the form `[model & fields]` you can specify which fields you want Toucan to return. Often
DB queries return fields that aren't needed; Toucan makes it simple to specify exactly what you want, and no more, making it
easy to write performant code right from the beginning.

(The `default-fields` method of `IModel` can be used to define what is essentially a default `[model & fields]` vector
to be used whenever a vector isn't specified; e.g. you can tell Toucan to automatically treat calls like
`(db/select User)` as calls like `(db/select [User :first-name ...])`. Passing a vector will override `default-fields`.
See the [models guide](defining-models.md) for more details.)


##### Using HoneySQL for everything else

Restricting the results returned and selecting a subset of the available fields is nice, but what if you want to do other things
SQL lets you do, like specifying ordering or limiting the number of results?

Toucan tries to make such things easy by also accepting raw HoneySQL forms as arguments to the `select` family of functions.

```clojure
(db/select User :name [:not= nil] {:limit 2}) -> [...]
```

This is compiled to a `clojure.java.jdbc`/HoneySQL call like:

```clojure
(jdbc/query (db/connection)
  (sql/format {:select [:*], :from [:users], :where [:not= :name nil], :limit 2}, :quoting (db/quoting-style)))
```

The raw HoneySQL forms are merged directly into the map generated automatically from the other arguments.

### select variations

#### select-field

`select-field` is a convenience for selecting distinct values of a single field.

```clojure
(db/select-field :first-name User) ; -> #{"Cam", "Rasta", ...}
```

Values are returned as a set to facilitate things like calls to `contains?`. The example above is equivalent to:

```clojure
(set (map :first-name (db/select [User :first-name])))
```

#### select-one

`select-one` selects a single object; the object is returned directly, rather than an item in a sequence (as with `select`).

```clojure
(db/select-one User :id 1) ; -> {:id 1, :first-name "Cam", :last-name "Saul"}
```

The example above is equivalent to:

```clojure
(first (db/select User :id 1, {:limit 1}))
```

Keep in mind that the query has a `LIMIT 1` clause; if it would otherwise return more than one result, which one gets returned
is indeterminate. In that case, it may be prudent to include a HoneySQL `{:order-by ...}` clause or additional key-value (`:where`)
clauses.


#### select-one-field

`select-one-field` returns a single value of a single field from a single object. It's a combination of `select-one`
and `select-field`:

```clojure
(db/select-one-field :first-name User :id 1) ; -> "Cam"
```

The example above is equivalent to:

```clojure
(:first-name (db/select-one [User :first-name] :id 1))
```

#### select-one-id

Like `select-one-field`, but assumes the field in question is `:id`.

```clojure
(db/select-one-id User :first-name "Cam") ; -> 1
```

The example above is equivalent to:

```clojure
(:id (db/select-one [User :id] :first-name "Cam"))
```


#### select-ids

Select a set of IDs. Like `select-field`, but assumes the field in question is `:id`.

```clojure
;; return Users with no :first-name
(db/select-ids User :first-name nil) ; -> #{10 20}
```

The call above is equivalent to:

```clojure
(set (map :id (db/select [User :id] :first-name nil)))
```


#### count

Select the number of objects matching some criteria. Equivalent to a SQL `SELECT COUNT(*)` query.

```clojure
(db/count User :first-name nil) ; -> 2
```


#### select-field->field

A convenience for generating a map of values of one column to values of another. This is useful for things like
mapping IDs to names or vice versa:

```clojure
(db/select-field->field :id :name Venue) ; -> {1 "Tempest", 2 "Ho's Bootleg Tavern", 3 "Louie's", ...}
```

#### select-field->id

Like `select-field->field`, but assumes the second field is `:id`.

```clojure
(db/select-field->id :name Venue) ; -> {"Tempest", 1, "Ho's Bootleg Tavern" 2, "Louie's" 3, ...}
```

#### select-id->field

Like `select-field->field`, but assumes the *first* field is `:id`.

```clojure
(db/select-id->field :name Venue) ; -> {1 "Tempest", 2 "Ho's Bootleg Tavern", 3 "Louie's", ...}
```

### Checking whether something exists

The `exists?` function can be used to efficiently check whether a record matching certain constraints exists. It accepts
syntax similar to the `select` family of functions, but returns a Boolean value:

```clojure
(db/exists? User :first-name "Cam") ; -> true
(db/exists? User :first-name "Spam") ; -> false
```

### select-reducible

Using `select` will realize the full set of results in memory. For smaller sets of results this is fine but queries that
could potentially return many rows, this could cause memory issues. `select-reducible` is an similar function to `select`,
but will return a reducible sequence instead of a vector. Using this, it's possible to consume the query results as they
are streamed from the database. Using this, you can avoid fully realizing the set of results in memory.

```clj
;; Send every active user a push notification!
(run! send-push-notification!
      (db/select-reducible User :active true))

;; Select every active user, filter some out with complex-filter-logic-fn,
;; and serialize the rest to a streaming HTTP response
(transduce (filter complex-filter-logic-fn)
           serialize-to-http-response
           (db/select-reducible User :active true))
```

With `select-reducible`, rows are processed as they are streamed from the database.

### Inserting objects

The `toucan.db/insert` familiy of functions makes it easy to insert new objects into the database while taking advantage of
Toucan's customizable behavior for different models. Toucan makes it easy to automatically add new values to objects before they're
inserted into the database, check preconditions, or perform automatic type conversion.

As the names imply, the `insert!` family of functions are wrappers around `clojure.java.jdbc/insert!` functions, which in turn
perform the equivalent of a SQL `INSERT`.

#### insert!

`insert!` is the primary Toucan function for adding new records to your DB. For convenience, `insert!` accepts either a single
object or key-value arg pairs:

```clojure
(db/insert! Label {:name "Toucan Friendly"})

;; same as
(db/insert! 'Label :name "Toucan Friendly")
```

Before `insert!` inserts an object into the database, it goes through several steps. First, it calls the model's implementation
of `pre-insert`, if it has one; this is a good opportunity to check preconditions or modify the incoming values of the object in ways
that aren't feasible by defining `types` or `properties`. Next, it performs any type conversions specified by the model's `types`,
such as converting keywords to strings or serializing objects as JSON. Finally, it applies any property functions for the model,
for example adding `created_at` or `updated-at` values. Finally, the new value is inserted into the DB.

After the new record is inserted into the DB, `insert!` returns the newly created object, and goes through the same steps
as the `select` family of functions (e.g., performing type conversions, calling property functions, and `post-select` methods
as appropriate).

Finally, the model's implementation of `post-insert`, if any, is called with the newly inserted object. This can be a good place
to do things like trigger asynchronous tasks (perhaps sending welcome emails after a new User is added) or adding newly created
Photos to a moderation queue.

Most of the examples above are explored in more detail in the [guide to defining models](defining-models.md).

#### insert-many!

`insert-many!` is a variation of `insert!` that can be used to insert many objects at once.

```clojure
(db/insert-many! Label [{:name "Toucan Friendly"}
                        {:name "Bird Approved"}]) ;; -> [38 39]
```

`insert-many!` returns a sequence of the IDs of the newly created objects.

**Note**: Unlike `insert!`, `insert-many!` does *not* currently call `post-insert` on the newly created objects. If you need
`post-insert` behavior, be sure to use `insert!` for the time being. This is subject to change in the future; there is an
[open issue to consider it](https://github.com/metabase/toucan/issues/4).


#### simple-insert! and simple-insert-many!

There are a few occasions where it might be desirable to avoid calls to `pre-insert`, `post-insert`, `post-select`, and
the various type conversion and property functions, perhaps for testing or performance reasons. `simple-insert!` and
`simple-insert-many!`, variations of `insert!` and `insert-many!`, respectively, skip these calls.

Rather than returning the newly created object itself, `simple-insert!` returns the ID of the newly created object.


### Updating objects

The `update!` family of functions, which predictably correspond to SQL `UPDATE` commands, update records in the database.

#### update!

`update!` updates values for a single object with a given `:id`. Like `insert!`, `update!` accepts either a map of new values
or key-value argument pairs:

```clojure
(db/update! Label 11 :name "ToucanFriendly")

;; same as
(db/update! Label 11 {:name "ToucanFriendly"})
```

In the examples above, `update!` changes the `:name` of a Label with `:id` 11. No other values of Label 11 are affected.

Before making updates, the normal type conversions and property functions are applied, and the model's implementation of
`pre-update`, if any. As with `insert!`, these methods can be implemented to check preconditions or add values to records
(such as an updated `:updated-at` timestamp) when objects are updated.

`update!` returns `true` if a row was affected, and `false` otherwise (i.e., if the `:id` did not match any rows).

**NOTE** As with some other functions, Toucan assumes your models have primary keys named `:id`; this limitation
will be addressed in a future release . See this [GitHub issue](https://github.com/metabase/toucan/issues/3).
If your models do not have a primary key named `:id`, you can use `update-where!` instead; see below.


#### update-where!

`update-where!` can be used to update one or more objects that match a map of conditions at the same time. Note that this
can also be used to update objects with no `:id` primary key, unlike `update!`.

```clojure
;; set the `:email` of users named Rasta Toucan to "rasta@toucanworld.com"
(db/update-where! User {:first-name "Rasta"
                        :last-name  "Toucan"}
  :email "rasta@toucanworld.com"}
```

Most conditions that you can pass to the `select` family of functions work here as well.

Like `update!`, `update-where!` returns `true` if any objects were affected, `false` otherwise.

**NOTE** Unlike `update!`, `update-where!` *does not* perform automatic type conversions or invoke property functions or `pre-update`
implementations on the objects that will be updated. This is subject to change in the future;
[this GitHub issue](https://github.com/metabase/toucan/issues/5) is open to consider changing this behavior. Because this is desirable
in some situations, we'll likely add a `simple-update-where!` function that maintains the original functionality.


### Deleting objects

Deleting objects with Toucan follows similar patterns to the other CRUD operations.

#### delete!

The basic delete function in Toucan is predictably called `delete!`:

```clojure
(delete! User :id 1)
```

`delete!` takes a model and a key-value map of conditions and deletes all objects that match. Arguments are passed directly to `select`,
which means anything that words there will work with `delete!`. Before deleting objects, the model's implementation of `pre-delete`, if any,
is called on each object about to be deleted. This is a good opportunity to check preconditions (and thus abort the delete if needed) or
delete dependent or child objects. The output of `pre-delete` is ignored.

**NOTE**: Like a few other Toucan functions, `delete!` is designed to work with objects that have a primary key named `:id`. If your models
don't follow this pattern, you can use `simple-delete!` instead. This is something we plan to address in a future release of Toucan.

#### simple-delete!

`simple-delete!` is similar to `delete!`, but doesn't pass objects about to be deleted to `pre-delete`.

```clojure
;; delete labels where :name == "Cam"
(db/simple-delete! Label :name "Cam")

;; for flexibility either a single map or kwargs are accepted
(db/simple-delete! Label {:name "Cam"})
```

`simple-delete!` *doesn't* assume objects have a primary key named `:id`, so it's useful in situations where that's not the case.


## Automatic model resolution

As mentioned in the [guide to defining models](defining-models.md), each Toucan model lives in its own namespace; this can sometimes
lead to circular dependencies between namespaces. As a convenience, the various functions in `toucan.db` can automatically resolve
quoted models:

```clojure
(db/select User ...)

;; ...is the same as...

(db/select 'User ...)
```

Such resolution automatically `require`s the correct namespace as needed. (Part of the reason each Toucan model must live in its
own namespace is to facilitate the automatic model resolution).

Automatic model resolution is also *extremely* handy when developing from the REPL; `toucan.db` functions lend themselves well to
such REPL-driven development.


## Classes of fetched objects

As mentioned in the [models guide](defining-models.md), `select` returns instances of the record type associated with a given model.
In other words, if you've defined a User model:

```clojure
(defmodel User :users) ;; User model corresponds to the :users table
```

then things returned by `select` will have class of `UserInstance`:

```clojure
(db/select User)
;; -> [#models.user_instance.UserInstance{:id 1. :first-name "Cam", :last-name "Saul", ...}
;;     ...]
```

This is handy for several reasons.

### Each fetched object is an instance of its own class

You can easily define additional protocols and provide individual
implementations for different models, or otherwise implement different behavior based on object classes. For example, let's
say we wanted to implement a permissions checking system in a protocol called `IPermissions` that checks whether the current user
(e.g., one associated with some action such as an API request) has permissions to update a given object. For a User, you could
say admins have permissions to modify any User, and non-admins only have permissions to modify themselves:

```clojure
(defprotocol IPermissions
  (can-write? [this]))

(defmodel User :user_table
  IPermissions
  (can-write? [user]
    (or (current-user-is-admin?)
        (is-current-user? user))))
```

This could be taken a step further, and you could implement automatic permissions checking by defining a custom property:

```clojure
(models/add-property! :write-check?
  :update (fn [obj]
            (assert (can-write? obj)
                    "You don't have permissions to do that.")
            obj))

(defmodel User :user_table
  models/IModel
  (properties [_]
    {:write-check? true}))
```

Now whenever you try to `update!` an object with the `:write-check?` property (such as User), `can-write?` must return a truthy
value, or the update will be aborted. (Properties are discussed in detail in the [models guide](defining-models.md); the example
above is provided to give you a sense of what you can do with Toucan).

### Instances of models implement the `IModel` interface

There's more benefits to having record-typed objects. You can also call any `IModel` method on any instance of a model:

```clojure
(models/default-fields some-user) ; -> [:id :first-name :last-name]
```


## Advanced Functionality

### Transactions

Toucan makes it easy to run queries inside transactions with the `transaction` macro.

```clojure
(require '[honeysql.core :as hsql])

;; send some money from User 1 to User 2.
(db/transaction
  (db/update! User 1 :account_balance (hsql/call :- :account_balance 100)  ; SET account_balance = account_balance - 100
  (db/update! User 2 :account_balance (hsql/call :+ :account_balance 100)) ; SET account_balance = account_balance + 100
```

### Raw HoneySQL Queries with query and execute!

Toucan is powerful and has helper functions for doing most kinds of simple queries, but there are a few that it can't do just
yet (`JOIN`s come to mind). Toucan offers a pair of low-level functions for these situatuions.

#### query

`query` takes a raw HoneySQL form, compiles it to SQL, and passes it to `jdbc/query` along with the current DB connection.

```clojure
(db/query {:select [:%count.*]
           :from   [:users]
           :where  [:= :first-name "Cam"]})
;; -> [{:count 1}]
```

#### execute!

Similiarly, `execute!` can be used in cases where you'd use `jdbc/execute!` instead of `jdbc/query` (i.e., for queries that return
no results):

```clojure
(db/execute! {:delete-from :users
              :where       [:not= :name "Cam"]})
```

### Debugging Queries

Sometimes Toucan queries don't work the way you'd expect. Luckily, Toucan makes it easy to see exactly what's going on under the
hood with the `debug-print-queries` macro. `debug-print-queries` will print (to `stdout`) the compiled HoneySQL form and the
compiled SQL (along with prepared statement arguments), all while returning results as normal:

```clojure
(debug-print-queries
  (select-one User :id 10))
```

In stdout (the REPL or console terminal), you'll see:

```clojure
{:select [:*],
 :from [{:table :users, :name "User", :toucan.models/model true}],
 :where [:= :id 10],
 :limit 1}
nil
SELECT *
FROM "users"
WHERE "id" = ?
LIMIT ?
(10 1)
```

This makes debugging fast and easy.

### Counting DB Calls

Similarly, the `debug-count-calls` macro can be used to count the number of calls made to `toucan.db` functions.

```clojure
(debug-count-calls
  (select-one 'User)
  (select-one 'User))
```

in stdout you'll see:

```clojure
DB Calls: 2
```

This is useful for performance tuning. If you'd like to use this number programatically, the `with-call-counting` macro
lets you provide a binding for a function that can be used to fetch the current call count; refer to the source for more details.

Note that this information is more of a rough estimate than a scientifically accurate count of DB calls; some Toucan functions
aren't yet set up to count calls, and non-Toucan DB calls (i.e., DB calls that bypass Toucan and hit `clojure.java.jdbc` directly)
are not counted. Nonetheless, the entire `select` family of functions is counted, and that is often enough to point you in the right
direction when performance tuning an application.

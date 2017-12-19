# Toucan Models

In Toucan, you define the various *models* used in your application with `defmodel`.
Models encapsulate information and behaviors related to a specific table in the application DB,
and have their own unique record type.

Generally, a single Toucan model corresponds to a single table in your database. Common models might
be things like `User` or `Venue`.

Model-specific macros, protocols, and functions can be found in the `toucan.models` namespace.


## defmodel

The `defmodel` macro is used to define a new model:

```clojure
(defmodel User :user_table)  ; creates class `UserInstance` and DB model `User`

(db/select User, ...)  ; use with `toucan.db` functions. All results are instances of `UserInstance`
```

`defmodel` defines a backing record type following the
format `<model>Instance`. For example, the class associated with the `User` model is `UserInstance`.

This class is used for both the titular model (e.g. `User`) and for instances of it that are fetched from the DB.
This means they can share the `IModel` protocol (discussed in more detail below) and simplifies the interface somewhat;
functions like `types` work on either the model entity itself (e.g., `User`) or instances fetched from the DB.

In other words, both of the following work:

```clojure
;; call on the model definition
(models/types User) ; -> {:status :keyword}

;; call on an instance of the model
(models/types (User 1)) ; -> {:status :keyword}
```


## The IModel Interface

The `IModel` protocol defines the various methods that are used to provide custom behavior for various models.
All models defined by `defmodel` automatically implement this protocol using the default definitions in `IModelDefaults`,
but you can override one or more of the methods to suit your needs.

To override one or more implementations, use `extend` and `merge` your custom implementations with `IModelDefaults`:

```clojure
(extend (class User)             ; it's somewhat more readable to write `(class User)` instead of `UserInstance`
  IModel (merge IModelDefaults
                {:types (constantly {:status :keyword}))
```

Note that `User` itself is not a class, but rather a definition of the `User` model, and is itself an instance of `UserInstance`;
hence the call to `extend` `(class User)`.

Refer to the documentation below for an overview of the various methods provided by `IModel` and how to use them to customize
the behavior of your models.


## Root Model Namespace

Toucan knows how to automatically load namespaces where models live, which is handy for avoiding circular references;
to facilitate this, Toucan models need to live in places that match an expected pattern.

```clojure
;; select Users. The namespace the model lives in doesn't need to be loaded yet; Toucan will take care of loading it automatically
(db/select 'User)
```

This is discussed in more detail in the documentation about [DB functions](db-functions.md).

To facilitate this behavior, all Toucan models are expected to live in their own namespace, and these namespace must follow a
certain pattern. For example, a model named `UserFollow` must live in the namespace `<root-model-namespace>.user-follow`.

The root model namespace defaults to `models`; in the example above, `UserFollow` would live in `models.user-follow`.

This is almost certainly not what you want; set your own value by calling `set-root-namespace!`:

```clojure
(models/set-root-namespace! 'my-project.models)
```

After setting the default model root namespace as in the example above, Toucan will look for `UserFollow`
in `my-project.models.user-follow`.

Setting the root model namespace needs to be done only once, and can be done as part of your normal app setup (in an entrypoint
function such as `-main`) or simply as a top-level function call anywhere in your codebase in any namespace that will get loaded at
launch time.


## Invoking a Model

Models implement [`clojure.lang.IFn`](https://github.com/clojure/clojure/blob/master/src/jvm/clojure/lang/IFn.java), and thus their
definitions can be invoked directly.

Invoking a model definition with no arguments returns a sequence of all instances of that model:

```clojure
(User)
;; -> [{:id 1, :first-name "Cam", :last-name "Saul"}
       {:id 2, :first-name "Rasta", :last-name "Toucan"}
       ...]
```

This call is equivalent to a simple `select` call:

```clojure
(db/select User)
```

Note that these are not guaranteed to be returned in any specific order.

Invoking a model definition with a single argument returns a single instance matching a given ID:

```clojure
(User 1) ; -> {:id 1, :first-name "Cam", :last-name "Saul"}
```

This is equivalent to a `select-one` call specifying ID:

```clojure
(db/select-one User :id 1)
```

Finally, invoking a model definition with multiple arguments can be used to fetch a single instance matching one or
more conditions.

```clojure
(User :first-name "Cam") ; -> {:id 1, :first-name "Cam", :last-name "Saul"}

;; returns nil if no records match
(User :first-name "Cam", :last-name [:not= "Saul"]) ;-> nil
```

This is equivalent to a `select-one` call with the key-value arguments passed along as-is:

```clojure
(db/select-one User :first-name "Cam)
```

## Default Fields

There are many cases when it is prudent to default to returning some subset of the columns of an object. A great example
is a User object with a `:password` field; even though you've salted it encrypted it using a secure algorithm `bcrypt`, you don't
want to expose it in things like REST API endpoints.

Hence the `default-fields` method of `IModels`. `default-fields` lets you defie a set of fields to return by default when
fetching instances of a model. Without `default-fields`, fetching a User might look like this:

```clojure
(User 1) ; -> {:id 1, :first-name "Cam", :last-name "Saul", :password "$2a$06$.iPkvIWwe.meXN5s2l2ClOZDvOmuaMhwrzoEu1XuVqANeYOnNRF9W"}
```

Not ideal! Let's define some `:default-fields` for User:

```clojure
(defmodel User :user_table
  IModel
  (default_fields [_]
    [:id :first-name :last-name]))
```

Now, when we fetch a User, we'll only see the `default-fields`:

```clojure
(User 1) ; -> {:id 1, :first-name "Cam", :last-name "Saul"}
```

Sometimes, you'll still want to see those fields; in the example above, we'll need them when checking passwords to authenticate User 1.
You can fetch non-default fields using functions like `select-field`, `select-one-field`, or the `select` fields vector syntax:

```clojure
(db/select-one-field :password User :id 1) ; -> "$2a$06$.iPkvIWwe.meXN5s2l2ClOZDvOmuaMhwrzoEu1XuVqANeYOnNRF9W"

;; or
(db/select [User :password] :id 1) ; -> {:password "$2a$06$.iPkvIWwe.meXN5s2l2ClOZDvOmuaMhwrzoEu1XuVqANeYOnNRF9W"}
```

This is the Toucan approach to things: default to doing things the simple (and secure) way you'll want most of the time, but
make it possible to avoid this behavior in the few cases where you need to. Or, in other words,

> Make the easy things easy, and make the hard things possible. -- Larry Wall


## Types

Model types are a easy way to define functions that should be used to transform values of a certain column
when they come out from or go into the database.

For example, suppose you had a `Venue` model, and wanted the value of its `:category` column to automatically
be converted to a Keyword when it comes out of the DB, and back into a string when put in. You could let Toucan
know to take care of this by defining the model as follows:

```clojure
(defmodel Venue :my_venue_table
  IModel
  (types [_]
    {:category :keyword}))
```

Whenever you fetch a Venue, Toucan will automatically apply the appropriate `:out` function for values of `:category`:

```clojure
(db/select-one Venue) ; -> {:id 1, :category :bar, ...}
```

In the other direction, `insert!` and `update!` will automatically do the reverse, and call the appropriate `:in` function.

`:keyword` is the only Toucan type defined by default, but adding more is simple.

You can add a new type by calling `add-type!`:

```clojure
;; add a :json type (using Cheshire) will serialize objects as JSON
;; going into the DB, and deserialize JSON coming out from the DB
(add-type! :json
  :in  json/generate-string
  :out #(json/parse-string % keyword))
```

In the example above, values of any columns marked as `:json` would be serialized as JSON before going into the DB,
and deserialized *from* JSON when coming out of the DB.


## Properties

Model properties are a powerful way to extend the functionality of Toucan models.

With properties, you can define custom functions that can modify the values (or even add new ones) of an object
before it is saved (via the `insert!` and `update!` family of functions) or when it comes out of the DB (via the
`select` family of functions).

Properties are global, which lets you define a single set of functions that can be applied to multiple models
that have the same property, without having to define repetitive code in model methods such as `pre-insert!`.

For example, suppose you have several models with `:created-at` and `:updated-at` columns. Whenever a new instance
of these models is inserted, you want to set `:created-at` and `:updated-at` to be the current time; whenever an instance
is updated, you want to update `:updated-at`.

You *could* handle this behavior by defining custom implementations for `pre-insert` and `pre-update` (discussed in more
detail below) for each of these models, but that gets repetitive quickly. Instead, you can simplfy this behavior by
defining a new *property* that can be shared by multiple models:

```clojure
(add-property! :timestamped?
  :insert (fn [obj _]
            (let [now (java.sql.Timestamp. (System/currentTimeMillis))]
              (assoc obj :created-at now, :updated-at now)))
  :update (fn [obj _]
            (assoc obj :updated-at (java.sql.Timestamp. (System/currentTimeMillis)))))

(defmodel Venue :my_venue_table
  IModel
  (properties [_]
    {:timestamped? true}))
```

In this example, before a Venue is inserted, a new value for `:created-at` and `:updated-at` will be added; before
one is updated, a new value for `:updated-at` will be added.

Property functions can be defined for any combination of `:insert`, `:update`, and `:select`.
If these functions are defined, they will be called as such:

```clojure
(fn [object property-value])
```

where `property-value` is the value for the key in question returned by the model's implementation of `properties`.

In the example above, `:timestamped?` is set to `true` for `Venue`; since we're not interested in the value in the
example above we simply ignore it (by binding it to `_`).

You can set the value to any truthy value you'd like, which can be used to customize behavior for different models,
making properties even more flexible.


## Lifecycle Methods

When `types` and `properties` aren't enough to implement the custom behavior for models, Toucan provides a menagerire of additional
methods that can be used to transform objects as they come out of or go into a database.

### pre-insert

`pre-insert` Gets called by `insert!` immediately before inserting a new object. It should return the object as-is, or with
any desired changes.

This provides an opportunity to do things like encode JSON or provide default values for certain fields.

```clojure
(defmodel User :user_table
  IModel
  (pre-insert [user]
    (let [defaults {:version 1}]
      (merge defaults user)))) ; set some default values"

```

`pre-insert` is a good opportunity to set default values for things, as shown above, or do constraint checking that would otherwise
be hard to do directly in the DB:

```clojure
(defn- pre-insert [user]
  ;; make sure if there's already a superuser we don't allow a second one
  (when (:is-superuser? user)
    (assert (not (db/exists? User :is-superuser? true))
      "There is already a superuser!")))
```

In the example above, if the assertion fails, the object won't get inserted into the DB.


### post-insert

`post-insert` gets called by `insert!` with an object that was newly inserted into the database.
This provides an opportunity to trigger specific logic that should occur when an object is inserted or
modify the object that is returned. The value returned by this method is returned to the caller of `insert!`.
The default implementation is `identity`.

```clojure
(defn- post-insert [user]
  (assoc user :newly-created true))
```

Or you can do something like kick off a background process to do something with the new object, or perhaps add
 newly created objects to a moderation queue:

```clojure

(defn- post-insert [new-venue]
  (add-venue-to-moderation-queue! new-venue))
```

The possibilities are endless.


### pre-update

Called by `update!` before DB operations happen. A good place to set updated values for certain fields, or check preconditions.
This method is exactly list `pre-insert`, but is invoked when calling `db/update!` and `db/update-where!` instead of the `insert!`
family of functions.

### post-update

Gets called by `update!` with an object that was successfully updated in the database.
This provides an opportunity to trigger specific logic that should occur when an object is updated.
The value returned by this method is not returned to the caller of `update!`. The default
implementation is `nil` (not invoked).

Note: This method is *not* invoked when calling `update!` with a `honeysql-form` form.

```clojure
(defn- post-update [user]
  (audit-user-updated! user))
```

### post-select

Called on the results from a call to `select` and similar functions. Default implementation doesn't do anything, but
you can provide custom implementations to do things like remove sensitive fields or add dynamic new ones.

For example, let's say we want to add a `:name` field to Users that combines their `:first-name` and `:last-name`:

```clojure
(defn- post-select [user]
  (assoc user :name (str (:first-name user) " " (:last-name user))))
```

Then, when we select a User:

```clojure
(User 1) ; -> {:id 1, :first-name "Cam", :last-name "Saul", :name "Cam Saul"}
```


### pre-delete

Called by `delete!` for each matching object that is about to be deleted.
Implementations can delete any objects related to this object by recursively calling `delete!`, or do any other cleanup needed,
or check some preconditions that must be fulfilled before deleting an object.

The output of this function is ignored.

```clojure
;; delete related objects
(pre-delete [user]
  (delete! Checkin :user-id (:id user))
  ...)
```

```clojure
;; check some precondition
(pre-delete [user]
  (assert (not (:is-superuser? user))
    "You cannot delete the superuser!"))
```

## Hydration Keys

The `hydration-keys` method can be overrode to specify the keyword field names that should be hydrated as instances of this model.
For example, `User` might inclide `:creator`, which means `hydrate` will look for `:creator_id` or `:creator-id` in other objects
to find the User ID, and fetch the `Users` corresponding to those values.

```clojure
;; tell hydrate to fetch Users when hydrating :creator
(defmodel User :user_table
  IModel
  (hydration-keys [_]
    [:creator]))
```

e.g.

```clojure
(hydrate {:creator-id 1} :creator)
;; -> {:creator-id 1,
       :creator    (User 1)}
```

Hydration is discussed in more detail [here](hydration.md).


## NOTES

### Models implement `honeysql.format/ToSql`, and can be compiled directly to HoneySQL

Models can be compiled directly to HoneySQL, and can thus be used in places where you'd otherwise use an identifier (i.e. the
table name of the model):

```clojure
(sql/format {:select [:*], :from [User]})
;; -> ["SELECT * FROM "users"]
```

### Functions that assume the PK is called :id

In several places, Toucan assumes models have a primary key, and that it is called `:id`. While Toucan can be used with models where this
is not the case, several functions won't work. For the time being, it is strongly recommended you follow this pattern
(usually a good idea anyway); we have an open issue to remedy this situation [here](https://github.com/metabase/toucan/issues/3).

# Setting Up Toucan

Toucan is fairly straightforward to configure. In most situations, you only need to provide default DB connection details
and specify the root namespace where all your Toucan models live.

## Configuring the DB

To get started with Toucan, all you need to do is tell it how to connect to your DB by calling `set-default-db-connection!`:

```clojure
(require '[toucan.db :as db])

(db/set-default-db-connection!
  {:classname   "org.postgresql.Driver"
   :subprotocol "postgresql"
   :subname     "//localhost:5432/my_db"
   :user        "cam"})
```

Pass it the same connection details that you'd pass to any `clojure.java.jdbc` function; it can be a simple connection details
map like the example above or some sort of connection pool. This function only needs to be called once, and can done in your app's normal
entry point (such as `-main`) or just as a top-level function call outside any other function in a namespace that will get loaded at launch.

### Using a Connection Pool

It's a good idea to use a connection pool if you're making more than a tiny amount of DB calls. Check out our [guide to
setting up a connection pool](connection-pools.md).


### Configuring Quoting-Style

Toucan automatically tells HoneySQL to wrap all identifiers in quotes so Lisp-case column names like `first-name` (and other
SQL-unfriendly column names) work without a hitch. By default Toucan uses ANSI SQL quoting (double-quotes around identifiers):

```clojure
(db/select-one-field :first-name User)
```

becomes:

```sql
SELECT "first-name"
FROM "users"
LIMIT 1
```

If you're using MySQL, you'll want to let Toucan know instead to use `:mysql` quoting by calling `db/set-default-quoting-style!`:

```clojure
(db/set-default-quoting-style! :mysql)
```

After setting this, the SQL generated will use backticks instead:
```sql
SELECT `first-name`
FROM `users`
LIMIT 1
```

The quoting style is passed directly to HoneySQL and can be anything it supports. At the time of this writing, it supports `:ansi` (Toucan's default), `:mysql`, or [legacy] `:sqlserver` (i.e., square brackets around identifiers).

Note that you can also temporarily change the value by binding `db/*quoting-style*`.

### Automatically Converting Dashes and Underscores

Toucan by default does not do any special transformations to identifiers for either queries going in to the database or for results coming out of the database. For example, suppose we have a model
named `Address` with a column `street_name`. Normal usage with this model will look something like:

```clojure
;; with default behavior (automatically-convert-dashes-and-underscores = false)
(db/select-one [Address :street_name]) ; Query looks like 'SELECT "street_name" FROM address'
;; -> {:street_name "1 Toucan Drive"}  ; no transformation is done to result row keys
```

Note that you cannot use dashed keywords in this case:

```clojure
;; Query looks like 'SELECT "street-name" FROM address'; this fails because column name is wrong
(db/select-one [Address :street-name])
```

Since `snake_case` names aren't particularly Lispy, you can have Toucan automatically replace dashes with underscores in queries going in to the DB, *and* replace underscores in query result row keys
with dashes coming out of the database:

```clojure
(db/select-one [Address :street-name]) ; Query still looks like 'SELECT "street_name" FROM address'
;; -> {:street-name "1 Toucan Drive"}  ; keys in result rows are transformed from snake_case to lisp-case
```

This behavior can be enabled either by binding `db/*automatically-convert-dashes-and-underscores*` or by calling `(db/set-default-automatically-convert-dashes-and-underscores! true)`.

Note that enabling this behavior will render you unable to access any columns in your database that have dashed names, since HoneySQL will assume dashes in identifiers represent underscores and convert
them accordingly.

For the curious: under the hood, this is done by a combination of setting HoneySQL's [`:allow-dashed-names?` option](https://github.com/jkk/honeysql/blob/master/README.md#usage) to `false`, and by
walking the results of queries to transform keys in a map.


## Configuring the Root Model Namespace

This is discussed in detail in the [models guide](defining-models.md), but for the sake of a having a convenient setup reference
we'll include a brief example here as well. Toucan requires that all models live in certain predictable namespaces; for example
a model named `UserInvite` must live in a namespace matching `<root-model-namespace>.user-invite`, which will probably end up being
something like `my-project.models.user-invite`. Set the root model namespace by calling `set-root-namespace!`:

```clojure
(models/set-root-namespace! 'my-project.models)
```

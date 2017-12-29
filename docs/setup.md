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

### Configuring Allowing Dashed Names

Toucan by default tells HoneySQL to leave dashes in field names as-is by passing [the `:allow-dashed-names` option](https://github.com/jkk/honeysql/blob/master/README.md#usage)to HoneySQL. This means keywords with dashes used as identifiers for tables or fields are passed to the database as-is; in other words, 

```clojure
(db/select [Address :street-name])
``` 

will generate a query like:

```sql
SELECT "street-name" FROM "address"
```

If you'd rather HoneySQL automatically assume dashes should be replaced with underscores in the query, you can configure Toucan by either binding `db/*allow-dashes-names*` or by calling `(db/set-default-allow-dashed-names! false)` to disable allowing dashed names. The example `select` call above will then generate a query like:

```sql
SELECT "street_name" FROM "address"
```

When disabled, field names are converted to contain underscores instead of dashes:

```clojure
;; database column is address.street_name

;; with default behavior (allow-dashed-names = true)
(db/select-one [Address :street_name]) 
;; -> {:street_name "1 Toucan Drive"}

(db/set-default-allow-dashed-names! false)

;; with allow-dashed-names = false
(db/select-one [Address :street-name])
;; -> {:street-name "1 Toucan Drive"}
```

## Configuring the Root Model Namespace

This is discussed in detail in the [models guide](defining-models.md), but for the sake of a having a convenient setup reference
we'll include a brief example here as well. Toucan requires that all models live in certain predictable namespaces; for example
a model named `UserInvite` must live in a namespace matching `<root-model-namespace>.user-invite`, which will probably end up being
something like `my-project.models.user-invite`. Set the root model namespace by calling `set-root-namespace!`:

```clojure
(models/set-root-namespace! 'my-project.models)
```

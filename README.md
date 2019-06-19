[![Downloads](https://versions.deps.co/metabase/toucan/downloads.svg)](https://versions.deps.co/metabase/toucan)
[![Dependencies Status](https://versions.deps.co/metabase/toucan/status.svg)](https://versions.deps.co/metabase/toucan)
[![Circle CI](https://circleci.com/gh/metabase/toucan.svg?style=svg)](https://circleci.com/gh/metabase/toucan)
[![License](https://img.shields.io/badge/license-Eclipse%20Public%20License-blue.svg)](https://raw.githubusercontent.com/metabase/toucan/master/LICENSE.txt)
[![cljdoc badge](https://cljdoc.org/badge/toucan/toucan)](https://cljdoc.org/d/toucan/toucan/CURRENT)

[![Clojars Project](https://clojars.org/toucan/latest-version.svg)](http://clojars.org/toucan)

# Toucan

![Toucan](https://github.com/metabase/toucan/blob/master/assets/toucan-logo.png)

## Overview

> There are no SQL/Relational DB ORMs for Clojure for obvious reasons. -- Andrew Brehaut

Toucan provides the better parts of an ORM for Clojure, like simple DB queries, flexible custom behavior
when inserting or retrieving objects, and easy *hydration* of related objects, all in a powerful and classy way.

Toucan builds on top of [clojure.java.jdbc](https://github.com/clojure/java.jdbc) and the excellent
[HoneySQL](https://github.com/jkk/honeysql). The code that inspired this library was originally written to bring some of the
sorely missed conveniences of [Korma](https://github.com/korma/Korma) to HoneySQL when we transitioned from the former to the latter
at [Metabase](http://metabase.com). Over the last few years, I've continued to build upon and refine the interface of Toucan,
making it simpler, more powerful, and more flexible, all while maintaining the function-based approach of HoneySQL.

View the complete documentation [here](docs/table-of-contents.md), or continue below for a brief tour.

### Simple Queries:

Toucan greatly simplifies the most common queries without limiting your ability to express more complicated ones. Even something
relatively simple can take quite a lot of code to accomplish in HoneySQL:


#### clojure.java.jdbc + HoneySQL

```clojure
;; select the :name of the User with ID 100
(-> (jdbc/query my-db-details
       (sql/format
         {:select [:name]
          :from   [:user]
          :where  [:= :id 100]
          :limit  1}
         :quoting :ansi))
    first
    :name)
```

#### Toucan

```clojure
;; select the :name of the User with ID 100
(db/select-one-field :name User :id 100)
```

Toucan keeps the simple things simple. Read more about Toucan's database functions [here](docs/db-functions.md).

### Flexible custom behavior when inserting and retrieving objects:

Toucan makes it easy to define custom behavior for inserting, retrieving, updating, and deleting objects on a model-by-model basis.
For example, suppose you want to convert the `:status` of a User to a keyword whenever it comes out of the database, and back into
a string when it goes back in:

#### clojure.java.jdbc + HoneySQL

```clojure
;; insert new-user into the DB, converting the value of :status to a String first
(let [new-user {:name "Cam", :status :new}]
  (jdbc/insert! my-db-details :user (update new-user :status name)))

;; fetch a user, converting :status to a Keyword
(-> (jdbc/query my-db-details
      (sql/format
        {:select [:*]
         :from   [:user]
         :where  [:= :id 200]}))
    first
    (update :status keyword))
```

#### Toucan

With Toucan, you just need to define the model, and tell that you want `:status` automatically converted:

```clojure
;; define the User model
(defmodel User :user
  IModel
  (types [this] ;; tell Toucan to automatically do Keyword <-> String conversion for :status
    {:status :keyword}))
```

After that, whenever you fetch, insert, or update a User, `:status` will automatically be converted appropriately:

```clojure
;; Insert a new User
(db/insert! User :name "Cam", :status :new) ; :status gets stored in the DB as "new"

;; Fetch User 200
(User 200) ; :status is converted to a keyword when User is fetched
```

Read more about defining and customizing the behavior of models [here](docs/defining-models.md).

### Easy hydration of related objects

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
the number of DB calls. You can also define custom functions to hydrate different keys, and add additional mappings for automatic hydration. Read more
about hydration [here](docs/hydration.md).


## Getting Started

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

Read more about setting up the DB and configuring options such as identifier quoting style [here](docs/setup.md).


## Test Utilities

Toucan provides several utility macros that making writing tests easy. For example, you can easily create temporary objects so your tests don't affect the state of your test DB:

```clojure
(require '[toucan.util.test :as tt])

;; create a temporary Venue with the supplied values for use in a test.
;; the object will be removed from the database afterwards (even if the macro body throws an Exception)
;; which makes it easy to write tests that don't change the state of the DB
(expect
  "hos_bootleg_tavern"
  (tt/with-temp Venue [venue {:name "Ho's Bootleg Tavern"}]
    (venue-slug venue))
```

Read more about Toucan test utilities [here](docs/test-utils.md).


## Annotated Source Code

[View the annotated source code here](https://rawgit.com/metabase/toucan/master/docs/uberdoc.html),
generated with [Marginalia](https://github.com/gdeer81/marginalia).


## Contributing

Pull requests for bugfixes, improvements, more documentaton, and other enhancements are always welcome.
Toucan code is written to the strict standards of the [Metabase Clojure Style Guide](https://github.com/metabase/metabase/wiki/Metabase-Clojure-Style-Guide),
so take a moment to familiarize yourself with the style guidelines before submitting a PR.

If you're interested in contributing, be sure to check out [issues tagged "help wanted"](https://github.com/metabase/toucan/issues?q=is%3Aopen+is%3Aissue+label%3A%22help+wanted%22).
There's lots of ways Toucan can be improved and we need people like you to make it even better.


### Tests & Linting

Before submitting a PR, you should also make sure tests and the linters pass. You can run tests and linters as follows:

```bash
lein test && lein lint
```

Tests assume you have Postgres running locally and have a test DB set up with read/write permissions. Toucan will populate this database with appropriate test data.

If you don't have Postgres running locally, you can instead the provided shell script to run Postgres via docker. Use `lein start-db` to start the database and `lein stop-db` to stop it.
Note the script is a Bash script and won't work on Windows unless you're using the Windows Subsystem for Linux.

To configure access to this database, set the following env vars as needed:

| Env Var | Default Value | Notes |
| --- | --- | --- |
| `TOUCAN_TEST_DB_HOST` | `localhost` | |
| `TOUCAN_TEST_DB_PORT` | `5432` | |
| `TOUCAN_TEST_DB_NAME` | `toucan_test` | |
| `TOUCAN_TEST_DB_USER` | | *Optional when running Postgres locally* |
| `TOUCAN_TEST_DB_PASS` | | *Optional when running Postgres locally* |

These tests and linters also run on [CircleCI](https://circleci.com/) for all commits and PRs.

We try to keep Toucan well-tested, so new features should include new tests for them; bugfixes should include failing tests.
Make sure to properly document new features as well! :yum:

A few more things: please carefully review your own changes and revert any superfluous ones. (A good example would be moving
words in the Markdown documentation to different lines in a way that wouldn't change how the rendered
page itself would appear. These sorts of changes make a PR bigger than it needs to be, and, thus, harder
to review.) And please include a detailed explanation of what changes you're making and why you've made them. This will help us understand what's going on while we review it. Thanks! :heart_eyes_cat:

### YourKit

![YourKit](https://www.yourkit.com/images/yklogo.png)

YourKit has kindly given us an open source license for their profiler, helping us profile and improve Toucan performance.

YourKit supports open source projects with innovative and intelligent tools
for monitoring and profiling Java and .NET applications.
YourKit is the creator of [YourKit Java Profiler](https://www.yourkit.com/java/profiler/),
[YourKit .NET Profiler](https://www.yourkit.com/.net/profiler/),
and [YourKit YouMonitor](https://www.yourkit.com/youmonitor/).

### License

Code and documentation copyright © 2018-2019 Metabase, Inc. Artwork copyright © 2018-2019 Cam Saul.

Distributed under the [Eclipse Public License](https://raw.githubusercontent.com/metabase/toucan/master/LICENSE.txt), same as Clojure.

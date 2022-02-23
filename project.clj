(defproject toucan "1.18.0"
  :description "Functionality for defining your application's models and querying the database."
  :url "https://github.com/metabase/toucan"
  :license {:name "Eclipse Public License"
            :url  "https://raw.githubusercontent.com/metabase/toucan/master/LICENSE.txt"}
  :min-lein-version "2.5.0"

  :aliases
  {"test"                  ["with-profile" "+expectations" "expectations"]
   "bikeshed"              ["with-profile" "+bikeshed" "bikeshed" "--max-line-length" "120"]
   "check-namespace-decls" ["with-profile" "+check-namespace-decls" "check-namespace-decls"]
   "eastwood"              ["with-profile" "+eastwood" "eastwood"]
   "docstring-checker"     ["with-profile" "+docstring-checker" "docstring-checker"]
   ;; `lein lint` will run all linters
   "lint"                  ["do" ["eastwood"] ["bikeshed"] ["check-namespace-decls"] ["docstring-checker"]]
   ;; `lein start-db` and stop-db are conveniences for running a test database via Docker
   "start-db"              ["shell" "./start-db"]
   "stop-db"               ["shell" "docker" "stop" "toucan_test"]}

  :dependencies
  [[org.clojure/java.classpath "0.3.0"]
   [org.clojure/java.jdbc "0.7.10"]
   [org.clojure/tools.logging "0.5.0"]
   [org.clojure/tools.namespace "0.3.1"]
   [honeysql "1.0.461"]
   [potemkin "0.4.5"]]

  :profiles
  {:dev
   {:dependencies
    [[org.clojure/clojure "1.10.1"]
     [expectations "2.2.0-beta2"]
     [org.postgresql/postgresql "42.2.8"]]

    :plugins
    [[lein-check-namespace-decls "1.0.2"]
     [lein-expectations "0.0.8"]
     [lein-shell "0.5.0"]]

    :injections
    [(require 'expectations)
     (#'expectations/disable-run-on-shutdown)]

    :jvm-opts
    ["-Xverify:none"]}

   :expectations
   {:plugins [[lein-expectations "0.0.8" :exclusions [expectations]]]}

   :eastwood
   {:plugins
    [[jonase/eastwood "0.3.6" :exclusions [org.clojure/clojure]]]

    :add-linters
    [:unused-private-vars
     :unused-namespaces
     :unused-fn-args
     :unused-locals]}

   :docstring-checker
   {:plugins
    [[docstring-checker "1.0.3"]]

    :docstring-checker
    {:exclude [#"test"]}}

   :bikeshed
   {:plugins
    [[lein-bikeshed "0.5.2"]]}

   :check-namespace-decls
   {:plugins               [[lein-check-namespace-decls "1.0.2"]]
    :check-namespace-decls {:prefix-rewriting true}}}

  :deploy-repositories
  [["clojars"
    {:url           "https://clojars.org/repo"
     :username      :env/clojars_username
     :password      :env/clojars_password
     :sign-releases false}]])

(defproject metabase/toucan "2.0.0-SNAPSHOT"
  :description ""
  :url "https://github.com/metabase/toucan"
  :min-lein-version "2.5.0"

  :license {:name "Eclipse Public License"
            :url "https://raw.githubusercontent.com/metabase/toucan/master/LICENSE.txt"}

  :aliases
  {"test"                      ["with-profile" "+expectations" "expectations"]
   "bikeshed"                  ["with-profile" "+bikeshed" "bikeshed" "--max-line-length" "120"]
   "check-namespace-decls"     ["with-profile" "+check-namespace-decls" "check-namespace-decls"]
   "eastwood"                  ["with-profile" "+eastwood" "eastwood"]
   "docstring-checker"         ["with-profile" "+docstring-checker" "docstring-checker"]
   ;; `lein lint` will run all linters
   "lint"                      ["do" ["eastwood"] ["bikeshed"] ["check-namespace-decls"] ["docstring-checker"]]}

  :dependencies
  [[org.clojure/java.jdbc "0.7.9"]
   [org.clojure/tools.logging "0.5.0-alpha.1"]
   #_[backtick "0.3.4"]
   [camsaul/pretty "1.0.0"]
   [honeysql "0.9.4"]
   [org.flatland/ordered "1.5.7"]
   [potemkin "0.4.5"]
   [vvvvalvalval/supdate "0.2.3"]]

  :profiles
  {:dev
   {:dependencies
    [[org.clojure/clojure "1.10.1"]
     [expectations "2.2.0-beta2"]
     [postgresql "9.3-1102.jdbc41"]]

    :injections
    [(require 'expectations)
     ((resolve 'expectations/disable-run-on-shutdown))]

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
     :unused-locals]

    :exclude-linters
    [:deprecations]}

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
    :source-paths          ["test"]
    :check-namespace-decls {:prefix-rewriting true}}}

  :deploy-repositories
  [["clojars"
    {:url           "https://clojars.org/repo"
     :username      :env/clojars_username
     :password      :env/clojars_password
     :sign-releases false}]])

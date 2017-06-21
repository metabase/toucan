(defproject toucan "1.1.0"
  :description "Functionality for defining your application's models and querying the database."
  :url "https://github.com/metabase/toucan"
  :license {:name "Eclipse Public License"
            :url "https://raw.githubusercontent.com/metabase/toucan/master/LICENSE.txt"}
  :min-lein-version "2.5.0"
  :dependencies [[org.clojure/java.classpath "0.2.3"]
                 [org.clojure/java.jdbc "0.6.1"]
                 [org.clojure/tools.logging "0.4.0"]
                 [org.clojure/tools.namespace "0.2.10"]
                 [honeysql "0.8.2"]]
  :javac-options ["-target" "1.7", "-source" "1.7"]
  :aliases {"bikeshed" ["bikeshed" "--max-line-length" "130"]
            "test" ["expectations"]}
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.8.0"]
                                  [expectations "2.1.9"]
                                  [postgresql "9.3-1102.jdbc41"]]
                   :plugins [[docstring-checker "1.0.0"]
                             [jonase/eastwood "0.2.4"
                              :exclusions [org.clojure/clojure]]
                             [lein-bikeshed "0.4.1"]
                             [lein-expectations "0.0.8"]]
                   :jvm-opts ["-Xverify:none"]
                   :eastwood {:add-linters [:unused-locals
                                            :unused-private-vars]
                              :exclude-namespaces [:test-paths]}}}
  :docstring-checker {:include [#"^toucan"]
                      :exclude [#"test"]}
  :deploy-repositories [["clojars" {:sign-releases false}]])

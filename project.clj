(defproject toucan "1.1.6.1"
  :description "Functionality for defining your application's models and querying the database."
  :url "https://github.com/metabase/toucan"
  :license {:name "Eclipse Public License"
            :url "https://raw.githubusercontent.com/metabase/toucan/master/LICENSE.txt"}
  :min-lein-version "2.5.0"
  :dependencies [[org.clojure/java.classpath "0.3.0"]
                 [org.clojure/java.jdbc "0.7.6"]
                 [org.clojure/tools.logging "0.4.1"]
                 [org.clojure/tools.namespace "0.2.10"]
                 [honeysql "0.9.2"]]
  :javac-options ["-target" "1.7", "-source" "1.7"]
  :aliases {"bikeshed" ["bikeshed" "--max-line-length" "118" "--var-redefs" "false"]
            "lint" ["do" ["eastwood"] "bikeshed" "docstring-checker"]
            "test" ["expectations"]
            "start-db" ["shell" "./start-db"] ; `lein start-db` and stop-db are conveniences for running a test database via Docker
            "stop-db" ["shell" "docker" "stop" "toucan_test"]}
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.9.0"]
                                  [expectations "2.1.9"]
                                  [postgresql "9.3-1102.jdbc41"]]
                   :plugins [[docstring-checker "1.0.2"]
                             [jonase/eastwood "0.2.5"
                              :exclusions [org.clojure/clojure]]
                             [lein-bikeshed "0.5.0"]
                             [lein-expectations "0.0.8"]
                             [org.clojure/tools.cli "0.3.5"]] ; Added because of https://github.com/dakrone/lein-bikeshed/issues/33
                   :jvm-opts ["-Xverify:none"]
                   :eastwood {:add-linters [:unused-locals
                                            :unused-private-vars]
                              :exclude-namespaces [:test-paths]}}}
  :docstring-checker {:include [#"^toucan"]
                      :exclude [#"test"]}
  :deploy-repositories [["clojars" {:sign-releases false}]])

(defproject nasa-cmr/cmr-index-set-app "0.1.0-SNAPSHOT"
  :description "index-set app is a microservice enabling CMR system create/maintain a logical set of indexes in Elasticsearch
               for indexing and searching for concepts."
  :url "***REMOVED***projects/CMR/repos/cmr/browse/index-set-app"
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [nasa-cmr/cmr-acl-lib "0.1.0-SNAPSHOT"]
                 [nasa-cmr/cmr-elastic-utils-lib "0.1.0-SNAPSHOT"]
                 [nasa-cmr/cmr-common-app-lib "0.1.0-SNAPSHOT"]
                 [org.clojure/tools.nrepl "0.2.11"]
                 [compojure "1.4.0"]
                 [ring/ring-core "1.4.0" :exclusions [clj-time]]
                 [ring/ring-json "0.4.0"]]
  :plugins [[lein-test-out "0.3.1"]]
  :repl-options {:init-ns user}
  :profiles
  {:dev {:dependencies [[org.clojure/tools.namespace "0.2.11"]
                        [org.clojars.gjahad/debug-repl "0.3.3"]
                        [clj-http "2.0.0"]]
         :source-paths ["src" "dev" "test" "int_test"]}
   :integration-test {:test-paths ["int_test"]
                      :dependencies [[clj-http "2.0.0"]]}
   :uberjar {:main cmr.index-set.runner
             :aot :all}}
  :aliases {;; Prints out documentation on configuration environment variables.
            "env-config-docs" ["exec" "-ep" "(do (use 'cmr.common.config) (print-all-configs-docs) (shutdown-agents))"]})



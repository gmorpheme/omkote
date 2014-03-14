(defproject omkote "0.1.0-SNAPSHOT"
  :description "Om-based knock-off of GlkOte."
  :url "http://github.com/gmorpheme/omkote"

  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/clojurescript "0.0-2156"]
                 [om "0.5.0"]
                 [org.clojure/core.async "0.1.267.0-0d7780-alpha"]
                 [com.cemerick/clojurescript.test "0.2.1"]]

  :repositories [["gmorpheme-snapshots" {:url "http://dev.gmorpheme.net/artifactory/libs-snapshot"
                                         :username :env/ARTIFACTORY_USERNAME
                                         :password :env/ARTIFACTORY_PASSWORD}]]
  :deploy-repositories [["gmorpheme-snapshots" {:url "http://dev.gmorpheme.net/artifactory/libs-snapshot-local"
                                                :username :env/ARTIFACTORY_USERNAME
                                                :password :env/ARTIFACTORY_PASSWORD}]]
  
  :plugins [[lein-cljsbuild "1.0.1"]]

  :source-paths ["src"]

  :cljsbuild { 
              :builds [{:id "omkote"
                        :source-paths ["src"]
                        :compiler {:output-to "omkote.js"
                                   :output-dir "out"
                                   :optimizations :none
                                   :source-map true}}
                       ;; samples
                       {:id "sample-demobase"
                        :source-paths ["src" "samples/demobase/src"]
                        :compiler {:output-to "samples/demobase/main.js"
                                   :output-dir "samples/demobase/out"
                                   :source-map true
                                   :optimizations :none}}

                       ;; tests
                       {:id "tests"
                        :source-paths ["src" "test"]
                        :compiler {:output-to "test-out/tests.js"
                                   :output-dir "test-out/out"
                                   :source-map true
                                   :optimizations :none}}
                       ]})

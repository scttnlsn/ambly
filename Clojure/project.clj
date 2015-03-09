(defproject org.omcljs/ambly "0.1.0-SNAPSHOT"
  :description "ClojureScript REPL into embedded JavaScriptCore."
  :url "https://github.com/omcljs/ambly"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "0.0-3053"]
                 [com.github.rickyclarkson/jmdns "3.4.2-r353-1"]]
  :source-paths ["src"]
  :compiler {
     :optimizations :none
     :cache-analysis true
     :source-map true})

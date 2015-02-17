(ns ambly.repl.jsc
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [cljs.analyzer :as ana]
            [cljs.util :as util]
            [cljs.compiler :as comp]
            [cljs.repl :as repl]
            [cljs.closure :as closure]
            [clojure.data.json :as json]
            [clojure.java.shell :as shell])
  (:import java.net.Socket
           java.lang.StringBuilder
           [java.io File BufferedReader BufferedWriter IOException]
           (javax.jmdns JmDNS ServiceListener)))

(defn set-logging-level [logger-name level]
  (.setLevel (java.util.logging.Logger/getLogger logger-name) level))

(defn service-name->display-name [service-name]
  (subs service-name (count "Ambly WebDAV Server on ")))

(defn service-map->choice-list [service-map]
  (map vector (iterate inc 1) service-map))

(defn print-services [service-map]
  (doseq [[choice-number [service-name _]] (service-map->choice-list service-map)]
    (println (str "[" choice-number "] " (service-name->display-name service-name)))))

(defn discover-and-pick-ambly-instance
  "Looks for Ambly WebDAV services advertised via Bonjour and presents
  a simple command-line UI letting user pick one."
  [choose-first-discovered?]
  (let [reg-type "_http._tcp.local."
        discovered-services (atom (sorted-map))
        mdns-service (JmDNS/create)
        service-listener
        (reify ServiceListener
          (serviceAdded [_ service-event]
            (let [name (.getName service-event)]
              (when (.startsWith name "Ambly WebDAV Server")
                (.requestServiceInfo mdns-service (.getType service-event) (.getName service-event) 1))))
          (serviceRemoved [_ service-event]
            (swap! discovered-services dissoc (.getName service-event)))
          (serviceResolved [_ service-event]
            (let [entry {(.getName service-event)
                         (let [info (.getInfo service-event)]
                           {:address (.getAddress info)
                            :port    (.getPort info)})}]
              (swap! discovered-services merge entry))))]
    (try
      (.addServiceListener mdns-service reg-type service-listener)
      (loop [count 0]
        (when (empty? @discovered-services)
          (Thread/sleep 100)
          (when (= 1000 count)
            (println "\nSearching ..."))
          (recur (inc count))))
      (Thread/sleep 500)                                    ;; Sleep a little more to catch stragglers
      (loop [current-discovered-services @discovered-services]
        (println)
        (print-services current-discovered-services)
        (when-not choose-first-discovered?
          (println "\n[r] Refresh\n")
          (print "Choice: ")
          (flush))
        (let [choice (if choose-first-discovered? "1" (read-line))]
          (if (= "r" choice)
            (recur @discovered-services)
            (let [choices (service-map->choice-list current-discovered-services)
                  choice-ndx (try (dec (Long/parseLong choice)) (catch NumberFormatException _ nil))]
              (if (< -1 choice-ndx (count choices))
                (second (nth choices choice-ndx))
                (recur current-discovered-services))))))
      (finally
        (future
          (.removeServiceListener mdns-service reg-type service-listener)
          (.close mdns-service))))))

(defn socket [host port]
  (let [socket (Socket. host port)
        in     (io/reader socket)
        out    (io/writer socket)]
    {:socket socket :in in :out out}))

(defn close-socket [s]
  (.close (:socket s)))

(defn write [^BufferedWriter out ^String js]
  (.write out js)
  (.write out (int 0)) ;; terminator
  (.flush out))

(defn read-messages [^BufferedReader in response-promise]
  (loop [sb (StringBuilder.) c (.read in)]
    (cond
      (= c -1) (do
                 (if-let [resp-promise @response-promise]
                   (deliver resp-promise :eof))
                 :eof)
      (= c 1) (do
                (print (str sb))
                (flush)
                (recur (StringBuilder.) (.read in)))
      (= c 0) (do
                (deliver @response-promise (str sb))
                (recur (StringBuilder.) (.read in)))
      :else (do
              (.append sb (char c))
              (recur sb (.read in))))))

(defn start-reading-messages
  "Starts a thread reading inbound messages."
  [repl-env]
  (.start
        (Thread.
          #(try
            (let [rv (read-messages (:in @(:socket repl-env)) (:response-promise repl-env))]
              (when (= :eof rv)
                (close-socket @(:socket repl-env))))
            (catch IOException e
              (when-not (.isClosed (:socket @(:socket repl-env)))
                (.printStackTrace e)))))))

(defn stack-line->canonical-frame
  "Parses a stack line into a frame representation, returning nil
  if parse failed."
  [stack-line opts]
  (let [[function file line column]
        (rest (re-matches #"(.*)@file:///(.*):([0-9]+):([0-9]+)"
                stack-line))]
    (if (and file function line column)
      {:file     (str (io/file (util/output-directory opts) file))
       :function function
       :line     (Long/parseLong line)
       :column   (Long/parseLong column)})))

(defn raw-stacktrace->canonical-stacktrace
  "Parse a raw JSC stack representation, parsing it into stack frames.
  The canonical stacktrace must be a vector of maps of the form
  {:file <string> :function <string> :line <integer> :column <integer>}."
  [raw-stacktrace opts]
  (->> raw-stacktrace
    string/split-lines
    (map #(stack-line->canonical-frame % opts))
    (remove nil?)
    vec))

(defn jsc-eval
  "Evaluate a JavaScript string in the JSC REPL process."
  [repl-env js]
  (let [{:keys [out]} @(:socket repl-env)
        response-promise (promise)]
    (reset! (:response-promise repl-env) response-promise)
    (write out js)
    (let [response @response-promise]
      (if (= :eof response)
        {:status :error
         :value  "Connection to JavaScriptCore closed."}
        (let [result (json/read-str response
                       :key-fn keyword)]
          (merge
            {:status (keyword (:status result))
             :value  (:value result)}
            (when-let [raw-stacktrace (:stacktrace result)]
              {:stacktrace raw-stacktrace})))))))

(defn load-javascript
  "Load a Closure JavaScript file into the JSC REPL process."
  [repl-env provides url]
  (jsc-eval repl-env
    (str "goog.require('" (comp/munge (first provides)) "')")))

(defn form-require-expr-js
  "Takes a JavaScript path expression anf forms a `require` command."
  [path-expr]
  {:pre [(string? path-expr)]}
  (str "require(" path-expr ");"))

(defn form-require-path-js
  "Takes a path and forms a JavaScript `require` command."
  [path]
  {:pre [(or (string? path) (instance? File path))]}
  (form-require-expr-js (str "'" path "'")))

(defn setup
  [repl-env opts]
  (let [_ (set-logging-level "javax.jmdns" java.util.logging.Level/SEVERE)
        [webdav-endpoint-name webdav-endpoint] (discover-and-pick-ambly-instance (:choose-first-discovered repl-env))
        _ (println "\nConnecting to" (service-name->display-name webdav-endpoint-name) "...\n")
        ; Assuming IPv4 for now
        endpoint-address (.getHostAddress (:address webdav-endpoint))
        endpoint-port (:port webdav-endpoint)
        webdav-mount-point (str "/Volumes/Ambly-" endpoint-address)
        output-dir (io/file webdav-mount-point)
        _ (.mkdirs output-dir)
        env (ana/empty-env)
        core (io/resource "cljs/core.cljs")]
    (reset! (:webdav-mount-point repl-env) webdav-mount-point)
    (shell/sh "mount_webdav" (str "http://" endpoint-address ":" endpoint-port) webdav-mount-point)
    (reset! (:socket repl-env)
      (socket endpoint-address (:port repl-env)))
    ;; Start dedicated thread to read messages from socket
    (start-reading-messages repl-env)
    ;; compile cljs.core & its dependencies, goog/base.js must be available
    ;; for bootstrap to load, use new closure/compile as it can handle
    ;; resources in JARs
    (let [core-js (closure/compile core
                    (assoc opts
                      :output-dir webdav-mount-point
                      :output-file
                      (closure/src-file->target-file core)))
          deps (closure/add-dependencies opts core-js)]
      ;; output unoptimized code and the deps file
      ;; for all compiled namespaces
      (apply closure/output-unoptimized
        (assoc opts
          :output-dir webdav-mount-point
          :output-to (.getPath (io/file output-dir "ambly_repl_deps.js")))
        deps))
    ;; Set up CLOSURE_IMPORT_SCRIPT function, injecting path
    (jsc-eval repl-env
      (str "CLOSURE_IMPORT_SCRIPT = function(src) {"
        (form-require-expr-js
          (str "'goog" File/separator "' + src"))
        "return true; };"))
    ;; bootstrap
    (jsc-eval repl-env
      (form-require-path-js (io/file "goog" "base.js")))
    ;; load the deps file so we can goog.require cljs.core etc.
    (jsc-eval repl-env
      (form-require-path-js (io/file "ambly_repl_deps.js")))
    ;; monkey-patch isProvided_ to avoid useless warnings - David
    (jsc-eval repl-env
      (str "goog.isProvided_ = function(x) { return false; };"))
    ;; monkey-patch goog.require, skip all the loaded checks
    (repl/evaluate-form repl-env env "<cljs repl>"
      '(set! (.-require js/goog)
         (fn [name]
           (js/CLOSURE_IMPORT_SCRIPT
             (aget (.. js/goog -dependencies_ -nameToPath) name)))))
    ;; load cljs.core, setup printing
    (repl/evaluate-form repl-env env "<cljs repl>"
      '(do
         (.require js/goog "cljs.core")
         (set-print-fn! js/out.write)))
    ;; redef goog.require to track loaded libs
    (repl/evaluate-form repl-env env "<cljs repl>"
      '(do
         (set! *loaded-libs* #{"cljs.core"})
         (set! (.-require js/goog)
           (fn [name reload]
             (when (or (not (contains? *loaded-libs* name)) reload)
               (set! *loaded-libs* (conj (or *loaded-libs* #{}) name))
               (js/CLOSURE_IMPORT_SCRIPT
                 (aget (.. js/goog -dependencies_ -nameToPath) name)))))))
    {:merge-opts {:output-dir webdav-mount-point}}))

(defrecord JscEnv [host port socket response-promise webdav-mount-point choose-first-discovered]
  repl/IParseStacktrace
  (-parse-stacktrace [this stacktrace error opts]
    (raw-stacktrace->canonical-stacktrace stacktrace opts))
  repl/IPrintStacktrace
  (-print-stacktrace [repl-env stacktrace error build-options]
    (doseq [{:keys [function file url line column]}
            (cljs.repl/mapped-stacktrace stacktrace build-options)]
      (println "\t" (str function " (" (str (or url file)) ":" line ":" column ")"))))
  repl/IJavaScriptEnv
  (-setup [this opts]
    (setup this opts))
  (-evaluate [this filename line js]
    (jsc-eval this js))
  (-load [this provides url]
    (load-javascript this provides url))
  (-tear-down [this]
    (shell/sh "umount" @webdav-mount-point)
    (close-socket @socket)
    (shutdown-agents)))

(defn repl-env* [options]
  (let [{:keys [host port choose-first-discovered]}
        (merge
          {:host "localhost"
           :port 50505}
          options)]
    (JscEnv. host port (atom nil) (atom nil) (atom nil) choose-first-discovered)))

(defn repl-env
  [& {:as options}]
  (repl-env* options))

(comment

  (require
    '[cljs.repl :as repl]
    '[ambly.repl.jsc :as jsc])

  (repl/repl* (jsc/repl-env)
    {:output-dir "out"
     :cache-analysis true
     :source-map true})

  )

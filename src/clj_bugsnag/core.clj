(ns clj-bugsnag.core
  (:require [clj-http.client :as http]
            [clj-stacktrace
             [core :refer [parse-exception]]
             [repl :refer [method-str]]]
            [clojure
             [repl :as repl]
             [string :as string]
             [walk :as walk]]
            [clojure.core.cache.wrapped :as cache]
            [clojure.java.shell :refer [sh]]
            [environ.core :refer [env]]))

(def git-rev
  (delay
   (try
     (string/trim (:out (sh "git" "rev-parse" "HEAD")))
     (catch Exception _ "git revision not available"))))

(defn- find-source-snippet
  [around, function-name]
  (try
    (let [fn-sym        (symbol function-name)
          fn-var        (find-var fn-sym)
          source        (repl/source-fn fn-sym)
          start         (-> fn-var meta :line)
          indexed-lines (map-indexed (fn [i, line]
                                       [(+ i start) (string/trimr line)])
                                     (string/split-lines source))]
      (into {} (filter #(<= (- around 3) (first %) (+ around 3)) indexed-lines)))
    (catch Exception _
      nil)))

(defn- transform-stacktrace
  [trace-elems project-ns include-src?]
  (try
    (vec (for [{:keys [file line ns]
                :as   elem} trace-elems
               :let         [project? (string/starts-with? (or ns "_") project-ns)
                             method   (method-str elem)
                             code     (when (and include-src? (string/ends-with? (or file "") ".clj"))
                                        (find-source-snippet line (string/replace (or method "") "[fn]" "")))]]
           {:file       file
            :lineNumber line
            :method     method
            :inProject  project?
            :code       code}))
    (catch Exception ex
      [{:file       "clj-bugsnag/core.clj"
        :lineNumber 1
        :code       {1 (str ex)
                     2 "thrown while building stack trace."}}])))

(def serializable?
  (some-fn map? string? number? nil? true? false? sequential?))

(defn- stringify [thing]
  (if (serializable? thing)
    thing
    (str thing)))

(defn- unroll [ex project-ns include-src?]
  (loop [collected []
         current   ex]
    (let [class-name (.getName ^Class (:class current))
          stacktrace (transform-stacktrace (:trace-elems current) project-ns include-src?)
          new-item   {:errorClass class-name
                      :message    (:message current)
                      :stacktrace stacktrace}
          collected  (conj collected new-item)]
      (if-let [next (:cause current)]
        (recur collected next)
        collected))))

(def unrolled-exception-cache (cache/lru-cache-factory {}))

(defn exception-cache-key [ex project-ns include-src?]
  (hash [ex project-ns include-src?]))

(defn- unroll-cached [ex project-ns include-src?]
  (let [k (exception-cache-key ex project-ns include-src?)]
    (get (cache/through-cache unrolled-exception-cache
                              k
                              (fn [_] (unroll ex project-ns include-src?)))
         k)))

(defn truncated-seq [xs max-length]
  (if (seq (drop max-length xs))
    (concat (take max-length xs) ['...])
    xs))

(defn trimmed-data [data {:keys [truncate-data-seqs-to]}]
  (cond->> data
    (pos-int? truncate-data-seqs-to)
    (walk/prewalk
     #(cond-> % (seq? %) (truncated-seq truncate-data-seqs-to)))))

(defn exception->json
  [exception {:keys [api-key project-ns context group group-fn user
                     severity version environment meta include-src? use-exception-cache?]
              :or   {api-key              (env :bugsnag-key)
                     project-ns           "\000"
                     severity             "error"
                     version              @git-rev
                     environment          "production"
                     include-src?         true
                     use-exception-cache? true}
              :as   options}]
  (let [ex         (parse-exception exception)
        cached-ex? (and use-exception-cache?
                        (cache/has? unrolled-exception-cache (exception-cache-key ex project-ns include-src?)))
        base-meta  (merge {"cached?" cached-ex?}
                          (when-let [d (ex-data exception)]
                            {"ex–data" (trimmed-data d options)}))]
    {:apiKey   api-key
     :notifier {:name    "clj-bugsnag"
                :version "0.5.0"
                :url     "https://github.com/ekataglobal/clj-bugsnag"}
     :events   [{:payloadVersion "2"
                 :exceptions     ((if use-exception-cache?
                                    unroll-cached
                                    unroll) ex project-ns include-src?)
                 :context        context
                 :groupingHash   (if group-fn
                                   (group-fn ex)
                                   group)
                 :severity       severity
                 :user           user
                 :app            {:version      version
                                  :releaseStage environment}
                 :device         {:hostname (.. java.net.InetAddress getLocalHost getHostName)}
                 :metaData       (walk/postwalk stringify (merge base-meta meta))}]}))

(defn notify
  "Main interface for manually reporting exceptions.
   When not :api-key is provided in options,
   tries to load BUGSNAG_KEY var from enviroment."
  ([exception]
   (notify exception nil))
  ([exception options]
   (let [params (exception->json exception options)
         url    "https://notify.bugsnag.com/"]
     (http/post url {:form-params  params
                     :content-type :json}))))

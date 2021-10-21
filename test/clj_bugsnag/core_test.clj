(ns clj-bugsnag.core-test
  (:require [clj-bugsnag.core :as core]
            [environ.core :refer [env]]
            [midje.sweet :refer :all]))

(fact "includes ExceptionInfo's ex-data"
      (-> (core/exception->json (ex-info "BOOM" {:wat "?!"}) {})
          :events first (get-in [:metaData "ex–data" ":wat"]))
      => "?!")

(fact "converts metadata values to strings"
      (-> (core/exception->json (ex-info "BOOM" {}) {:meta {:reason println}})
          :events first (get-in [:metaData ":reason"]))
      => (has-prefix "clojure.core$println@"))

(defn make-crash
  "A function that will crash"
  []
  (let [closure (fn []
                  (.crash nil))]

  ;;
  ;; /end to check for 3 lines before and after

    (closure)))

(defn make-crash-exception []
  (try
    (make-crash)
    (catch Exception ex
      ex)))

(fact "includes source in stack traces"
      (let [ex (make-crash-exception)]
        (-> (core/exception->json ex nil) :events first :exceptions first :stacktrace second :code)
        => {17 "  \"A function that will crash\""
            18 "  []"
            19 "  (let [closure (fn []"
            20 "                  (.crash nil))]"
            21 ""
            22 "  ;;"
            23 "  ;; /end to check for 3 lines before and after"}
        (-> (core/exception->json ex nil) :events first :exceptions first :stacktrace (nth 2) :code)
        => {22 "  ;;"
            23 "  ;; /end to check for 3 lines before and after"
            24 ""
            25 "    (closure)))"}))

(fact "does not include source in stack traces when option `include-src?` is false"
      (let [ex (make-crash-exception)]
        (-> (core/exception->json ex {:include-src? false}) :events first :exceptions first :stacktrace second :code)
        => nil
        (-> (core/exception->json ex {:include-src? false}) :events first :exceptions first :stacktrace (nth 2) :code)
        => nil))

(fact "caches exception unrolling by default"
      (let [[ex-1 ex-2]  ((fn uncached-fn [] [(make-crash-exception) (make-crash-exception)]))
            ex-3 (make-crash-exception)
            cache-size-before (count (keys @core/unrolled-exception-cache))
            opts              {:project-ns "test"}]
        (core/exception->json ex-1 opts)
        (core/exception->json ex-2 opts)
        (core/exception->json ex-3 opts)
        (- (count (keys @core/unrolled-exception-cache)) cache-size-before)
        => 2))

(fact "doesn't use cache with cache option set to false"
      (let [ex                ((fn uncached-fn-2 [] (make-crash-exception)))
            cache-size-before (count (keys @core/unrolled-exception-cache))
            opts              {:project-ns           "test"
                               :use-exception-cache? false}]
        (core/exception->json ex opts)
        (- (count (keys @core/unrolled-exception-cache)) cache-size-before)
        => 0))

(fact "falls back to BUGSNAG_KEY environment var for :apiKey"
      (-> (core/exception->json (ex-info "BOOM" {}) {}) :apiKey) => ..bugsnag-key..
      (provided
       (env :bugsnag-key) => ..bugsnag-key..))

(fact "includes nested exceptions"
      (let [e1 (Exception. "Inner")
            e2 (Exception. "Middle" e1)
            e3 (Exception. "Outer"  e2)]
        (->> (core/exception->json e3 {}) :events first :exceptions (map :message))
        => ["Outer" "Middle" "Inner"]))

(fact "uses :group-fn to create custom groupingHash"
      (let [ex (Exception. "message")]
        (-> (core/exception->json ex {:group-fn :message}) :events first :groupingHash)
        => "message"))

(fact "uses :group for groupingHash if :group-fn is not provided"
      (let [ex (Exception. "message")]
        (-> (core/exception->json ex {:group "some group"}) :events first :groupingHash)
        => "some group"))

(def example-meta
  {"map"       {"foo" "bar"}
   "string"    "foo"
   "number"    1
   "nil"       nil
   "true"      true
   "false"     false
   "sequental" [1 2 3]})

(fact "metadata JSON"
      (-> (core/exception->json (ex-info "BOOM" {}) {:meta example-meta})
          :events first (get :metaData) (select-keys (keys example-meta)))
      => example-meta)

(fact "can trim sequences in ex-data"
      (-> (ex-info "BOOM"
                   {:infinite-seq (range)
                    :short-seq    (range 5)
                    :vec          [1 2 3]})
          (core/exception->json
           {:truncate-data-seqs-to 5})
          :events first (get-in [:metaData "ex–data"]))
      => {":infinite-seq" [0 1 2 3 4 "..."]
          ":short-seq"    (range 5)
          ":vec"          [1 2 3]})

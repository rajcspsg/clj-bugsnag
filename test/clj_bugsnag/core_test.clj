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

(fact "includes source in stack traces"
      (try
        (make-crash)
        (catch Exception ex
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
              25 "    (closure)))"})))

(fact "falls back to BUGSNAG_KEY environment var for :apiKey"
      (-> (core/exception->json (ex-info "BOOM" {}) {}) :apiKey) => ..bugsnag-key..
      (provided
       (env :bugsnag-key) => ..bugsnag-key..))

(fact "includes nested exceptions"
      (let [e1 (Exception. "Inner")
            e2 (Exception. "Middle" e1)
            e3 (Exception. "Outer"  e2)]
        (->> (core/exception->json e3 {}) :events first :exceptions (map :message))
        => ["Inner" "Middle" "Outer"]))

(fact "uses :group-fn to create custom groupingHash"
      (let [ex (Exception. "message")]
        (-> (core/exception->json ex {:group-fn :message}) :events first :groupingHash)
        => "message"))

(fact "uses :group for groupingHash if :group-fn is not provided"
      (let [ex (Exception. "message")]
        (-> (core/exception->json ex {:group "some group"}) :events first :groupingHash)
        => "some group"))

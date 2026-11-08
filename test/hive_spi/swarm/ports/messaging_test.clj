(ns hive-spi.swarm.ports.messaging-test
  "deliver! routes an envelope over the registered sinks: priority order,
   first success wins, a failing or throwing sink falls through, and the
   port's own refusals (:invalid-envelope, :no-sink) are values. Stub sinks
   record what they were handed; no transport is involved."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [malli.core :as m]
            [malli.generator :as mg]
            [hive-test.golden :refer [deftest-golden]]
            [hive-spi.swarm.ports.messaging :as msg]))

;; SPDX-License-Identifier: MIT

(def ^:private probe-ids [:a-specific :b-fallback :c-broken :d-thrower])

(defn- clear-probes! [] (run! msg/unregister-sink! probe-ids))

(use-fixtures :each
  (fn [f]
    (let [prior (when (msg/messaging-set?) (msg/get-messaging))]
      (clear-probes!)
      (msg/clear-messaging!)
      (try (f) (finally (clear-probes!) (msg/clear-messaging!)
                        (when prior (msg/set-messaging! prior)))))))

(defn- sink
  "A stub sink: accepts recipients in ACCEPTS (or every recipient for :all),
   answers OUTCOME (:ok, :fail or :throw)."
  [id priority accepts outcome]
  (with-meta
    (reify msg/IInboxSink
      (sink-id [_] id)
      (accepts? [_ to] (or (= :all accepts) (contains? accepts to)))
      (-deliver! [_ env]
        (case outcome
          :ok    {:delivered? true :sink id :receipt (:inbox/to env)}
          :fail  {:delivered? false :sink id :reason :refused}
          :throw (throw (ex-info "transport down" {})))))
    {:inbox/priority priority}))

(def ^:private envelope
  {:inbox/to "coordinator:1" :inbox/from "ling-1" :inbox/kind :shout :inbox/body "done"})

(deftest-golden deliver-routing-golden
  "test/golden/hive_spi/swarm/ports/messaging/deliver.edn"
  (let [before  (msg/deliver! envelope)
        invalid (msg/deliver! (dissoc envelope :inbox/body))]
    (msg/register-sink! (sink :b-fallback 100 :all :ok))
    (msg/register-sink! (sink :a-specific 10 #{"coordinator:1"} :ok))
    (let [specific (msg/deliver! envelope)
          other    (msg/deliver! (assoc envelope :inbox/to "ling-2"))]
      (msg/register-sink! (sink :c-broken 1 :all :fail))
      (msg/register-sink! (sink :d-thrower 0 :all :throw))
      (let [fallthrough (msg/deliver! envelope)]
        (msg/unregister-sink! :a-specific)
        (msg/unregister-sink! :b-fallback)
        {:no-sinks     before
         :invalid      invalid
         :specific     specific
         :other        other
         :fall-through fallthrough
         :last-failure (msg/deliver! envelope)
         :sink-ids     (vec (sort (map msg/sink-id (msg/sinks))))}))))

(def ^:private GenEnvelope
  [:map
   [:inbox/to [:enum "coordinator:1" "ling-2" ""]]
   [:inbox/from [:enum "ling-1" ""]]
   [:inbox/body [:enum "done" ""]]])

(defspec deliver-always-answers-a-delivery-result 100
  (prop/for-all [env (mg/generator GenEnvelope)]
    (clear-probes!)
    (msg/register-sink! (sink :c-broken 1 :all :fail))
    (msg/register-sink! (sink :a-specific 10 #{"coordinator:1"} :ok))
    (let [out    (msg/deliver! env)
          valid? (every? seq (vals (select-keys env [:inbox/to :inbox/from :inbox/body])))]
      (clear-probes!)
      (and (m/validate msg/DeliveryResult out)
           (= (:delivered? out) (and valid? (= "coordinator:1" (:inbox/to env))))
           (= (= :invalid-envelope (:reason out)) (not valid?))))))

(deftest the-noop-degrades-every-port-test
  (let [p (msg/get-messaging)]
    (is (false? (msg/messaging-set?)))
    (is (nil? (msg/subscribe! p :x)))
    (is (= [] (msg/fetch-conversation p "ctx" {})))
    (is (= {:text "abcd…"} (msg/elide! p "abcdefgh" 5 (constantly "id"))))
    (is (= {:text "short"} (msg/elide! p "short" (constantly "id"))))
    (is (= {:keep 1} (msg/apply-policy p {:keep 1 :broadcast? true :broadcast-reason :halt} nil)))
    (is (= 0 (msg/spent-recently p "hive")))
    (is (= () (msg/delivery-channels p)))))

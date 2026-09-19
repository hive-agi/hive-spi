(ns hive-spi.swarm.dispatch-context-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-spi.swarm.dispatch-context :as dc]))

;; SPDX-License-Identifier: MIT

(deftest a-text-context-is-its-prompt
  (let [ctx (dc/->text-context "do the task")]
    (is (= {:prompt "do the task"} (dc/resolve-context ctx)))
    (is (= :text (dc/context-type ctx)))))

(deftest a-ref-context-with-no-reconstruction-carries-its-references-unexpanded
  (let [ctx (dc/->ref-context "do the task" {:ctx-refs {:axioms "ctx-1"}
                                             :kg-node-ids ["n1" "n2"]
                                             :scope "hive"})]
    (is (= :ref (dc/context-type ctx)))
    (is (= {:prompt "do the task" :refs ["ctx-1"] :kg-nodes ["n1" "n2"]}
           (dc/resolve-context ctx))))
  (testing "empty references leave no empty keys behind"
    (is (= {:prompt "do the task"}
           (dc/resolve-context (dc/->ref-context "do the task" {}))))))

(deftest the-injected-reconstruction-is-asked-once-with-the-references
  (let [asked (atom [])
        ctx (dc/->ref-context "do the task"
                              {:ctx-refs {:axioms "ctx-1"}
                               :kg-node-ids ["n1"]
                               :scope "hive"
                               :reconstruct-fn (fn [refs nodes scope]
                                                 (swap! asked conj [refs nodes scope])
                                                 "AXIOMS")})
        resolved (dc/resolve-context ctx)]
    (is (= [[{:axioms "ctx-1"} ["n1"] "hive"]] @asked))
    (is (= "AXIOMS" (:reconstructed resolved)))
    (is (= "AXIOMS\n\n---\n\ndo the task" (:prompt resolved)))))

(deftest a-reconstruction-that-answers-nil-changes-nothing
  (is (= {:prompt "do the task" :refs ["ctx-1"]}
         (dc/resolve-context
          (dc/->ref-context "do the task" {:ctx-refs {:axioms "ctx-1"}
                                           :reconstruct-fn (constantly nil)})))))

(deftest a-throwing-reconstruction-is-reported-and-the-task-still-arrives
  (let [resolved (dc/resolve-context
                  (dc/->ref-context "do the task"
                                    {:reconstruct-fn (fn [_ _ _]
                                                       (throw (ex-info "boom" {})))}))]
    (is (= "Context reconstruction failed: boom" (:reconstructed resolved)))
    (is (str/ends-with? (:prompt resolved) "do the task"))))

(deftest ensure-context-coerces-and-never-rewraps
  (let [text (dc/->text-context "p")
        ref (dc/->ref-context "p" {})]
    (is (identical? text (dc/ensure-context text)))
    (is (identical? ref (dc/ensure-context ref))))
  (is (= (dc/->text-context "p") (dc/ensure-context "p")))
  (testing "a non-string is carried as its string form; nil is the empty prompt"
    (is (= {:prompt "42"} (dc/resolve-context (dc/ensure-context 42))))
    (is (= {:prompt ""} (dc/resolve-context (dc/ensure-context nil))))))

(def ^:private gen-reconstruct-fn
  (gen/elements [nil
                 (constantly nil)
                 (constantly "RECONSTRUCTED")
                 (fn [_ _ _] (throw (ex-info "boom" {})))]))

(defspec whatever-the-references-the-agent-receives-its-task 100
  (prop/for-all [prompt gen/string-alphanumeric
                 ctx-refs (gen/map gen/keyword gen/string-alphanumeric {:max-elements 4})
                 kg-node-ids (gen/vector gen/string-alphanumeric 0 4)
                 reconstruct-fn gen-reconstruct-fn]
    (let [resolved (dc/resolve-context
                    (dc/->ref-context prompt {:ctx-refs ctx-refs
                                              :kg-node-ids kg-node-ids
                                              :reconstruct-fn reconstruct-fn}))]
      (and (str/ends-with? (:prompt resolved) prompt)
           (= (boolean (seq ctx-refs)) (contains? resolved :refs))
           (= (boolean (seq kg-node-ids)) (contains? resolved :kg-nodes))))))

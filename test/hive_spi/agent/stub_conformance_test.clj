(ns hive-spi.agent.stub-conformance-test
  "The reference stub runs the same run-control/transcript conformance suite
   every real adapter runs, and the published shapes reject what the contract
   rules out."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-dsl.result :as r]
            [hive-spi.agent.conformance :as conformance]
            [hive-spi.agent.run-control :as rc]
            [hive-spi.agent.stub :as stub]
            [hive-spi.agent.transcript :as tx]
            [malli.generator :as mg]))

(conformance/defconformance stub stub/harness)

(deftest run-conformance-reports-every-case
  (let [res (conformance/run-conformance stub/harness {})]
    (is (= conformance/case-ids (:ran res)))
    (is (empty? (:skipped res)))))

(deftest run-conformance-honours-skip
  (let [res (conformance/run-conformance stub/harness {:skip {:continue-one-successor "not offered"}})]
    (is (= "not offered" (get-in res [:skipped :continue-one-successor])))
    (is (not (some #{:continue-one-successor} (:ran res))))))

(deftest stub-satisfies-every-port
  (let [s (stub/create)]
    (doseq [p [rc/IRunSubmit rc/IRunContinue rc/IRunAwait rc/IRunObserve
               rc/IRunCancel rc/IRunCleanup tx/ITranscriptQuery]]
      (is (satisfies? p s) (str p)))))

(deftest a-provider-without-optional-ports-passes-vacuously
  (testing "an implementation offering only submit/observe/await is still conformant"
    (let [make (fn []
                 (let [s (stub/create)
                       ctl (reify
                             rc/IRunSubmit (submit! [_ spec] (rc/submit! s spec))
                             rc/IRunObserve (observe-run [_ h] (rc/observe-run s h))
                             rc/IRunAwait (await-run [_ h t] (rc/await-run s h t)))]
                   (assoc (stub/harness) :ctl ctl :transcripts s
                          :drive! #(stub/advance! s %)
                          :end! #(stub/finish! s %1 %2))))]
      (is (= conformance/case-ids (:ran (conformance/run-conformance make {})))))))

(deftest run-spec-refuses-what-the-contract-rules-out
  (is (not (rc/valid-run-spec? {:run/task "no agent id"})))
  (is (not (rc/valid-run-spec? {:run/agent-id "" :run/task "t"})) "an empty agent-id is no identity")
  (is (not (rc/valid-run-spec? {:run/agent-id "a" :run/task "t" :run/resume-from {:store :x}})))
  (is (not (rc/valid-run-spec? {:run/agent-id "a" :run/task "t" :run/attempt -1})))
  (is (rc/valid-run-spec? {:run/agent-id "a" :run/task "t" :run/resume-from {:turn 7}
                           :run/task-id "k" :run/attempt 0})))

(deftest the-hive-agent-shapes-still-validate
  (testing "a handle and outcome as hive-agent.ports.run-control produces them are valid here, so adoption changes no caller"
    (is (rc/valid-run-handle? {:run/id "run-0" :run/adapter :fake
                               :run/transcript-ref {:agent-id "a" :cursor {:turn 0}}}))
    (is (rc/valid-run-outcome? {:run/state :failed :run/cursor {:turn 2}
                                :run/final-cursor {:turn 2}
                                :run/error {:error/type :run/fake-failure}}))))

(deftest terminal-states-are-exactly-the-three
  (is (= #{:succeeded :failed :cancelled} rc/terminal-states))
  (is (every? rc/terminal? (map #(hash-map :run/state %) rc/terminal-states)))
  (is (not-any? rc/terminal? [{:run/state :queued} {:run/state :running} {} nil])))

(deftest generated-shapes-round-trip-their-validators
  (doseq [[schema valid?] [[rc/RunSpec rc/valid-run-spec?]
                           [rc/RunHandle rc/valid-run-handle?]
                           [rc/RunStatus rc/valid-run-status?]
                           [rc/RunOutcome rc/valid-run-outcome?]
                           [tx/Entries tx/valid-entries?]]]
    (is (every? valid? (mg/sample schema {:size 10 :seed 7})))))

(deftest truncated-entries-are-not-evidence
  (is (tx/evidence? {:transcript/agent-id "a" :transcript/turn 1}))
  (is (tx/evidence? {:transcript/agent-id "a" :transcript/turn 1 :transcript/truncated? false}))
  (is (not (tx/evidence? {:transcript/agent-id "a" :transcript/turn 1 :transcript/truncated? true}))))

(deftest last-cursor-never-moves-backwards
  (is (= {:turn 5} (tx/last-cursor {:turn 5} [])))
  (is (= {:turn 9} (tx/last-cursor {:turn 0} [{:transcript/agent-id "a" :transcript/turn 9}
                                              {:transcript/agent-id "a" :transcript/turn 3}])))
  (is (= {:turn 5} (tx/last-cursor {:turn 5} [{:transcript/agent-id "a" :transcript/turn 2}]))))

(deftest continue-refuses-an-unfinished-or-reused-parent
  (let [s (stub/create)
        p (:ok (rc/submit! s {:run/agent-id "p" :run/task "t"}))]
    (is (= :run/parent-not-completed (:error (rc/continue! s p {:run/agent-id "c" :run/task "t"}))))
    (stub/finish! s p :succeeded)
    (is (= :run/transcript-reused (:error (rc/continue! s p {:run/agent-id "p" :run/task "t"}))))
    (is (r/ok? (rc/continue! s p {:run/agent-id "c" :run/task "t"})))))

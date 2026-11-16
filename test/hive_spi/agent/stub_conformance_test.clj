(ns hive-spi.agent.stub-conformance-test
  "The reference stub runs the same run-control/transcript conformance suite
   every real adapter runs, and the published shapes reject what the contract
   rules out."
  (:require [clojure.test :as t :refer [deftest is testing]]
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

(def ^:private optional-case-ids
  [:cancel-flushes-before-cancelled :cleanup-keeps-transcript :continue-one-successor])

(defn- harness-over
  "A harness whose :ctl is (WRAP stub) and whose other parts are the stub's."
  [wrap]
  (let [s (stub/create)]
    {:ctl (wrap s) :transcripts s
     :drive! #(stub/advance! s %)
     :end! #(stub/finish! s %1 %2)}))

(defn- required-ports-only [s]
  (reify
    rc/IRunSubmit (submit! [_ spec] (rc/submit! s spec))
    rc/IRunObserve (observe-run [_ h] (rc/observe-run s h))
    rc/IRunAwait (await-run [_ h t] (rc/await-run s h t))))

(defn- reports-of
  "The clojure.test report events F emits, captured instead of counted."
  [f]
  (let [events (atom [])]
    (binding [t/report #(swap! events conj %)]
      (f))
    @events))

(deftest a-provider-without-optional-ports-has-them-skipped-not-ran
  (testing "an implementation offering only submit/observe/await is conformant, and its optional cases are not reported as run"
    (let [res (conformance/run-conformance #(harness-over required-ports-only) {})]
      (is (= (remove (set optional-case-ids) conformance/case-ids) (:ran res)))
      (is (= (set optional-case-ids) (set (keys (:skipped res)))))
      (is (= "optional port IRunCancel is not satisfied"
             (get-in res [:skipped :cancel-flushes-before-cancelled]))))))

(deftest a-final-cursor-past-the-last-entry-fails-the-suite
  (let [lying (fn [s]
                (reify
                  rc/IRunSubmit (submit! [_ spec] (rc/submit! s spec))
                  rc/IRunObserve (observe-run [_ h] (rc/observe-run s h))
                  rc/IRunAwait
                  (await-run [_ h t]
                    (let [res (rc/await-run s h t)]
                      (cond-> res (r/ok? res) (assoc-in [:ok :run/final-cursor] {:turn 999999}))))))
        failed? (fn [make] (some #(= :fail (:type %))
                                 (reports-of #(conformance/run-case make {} :await-flush-before-terminal))))]
    (is (failed? #(harness-over lying)) "a final cursor no entry carries is refused")
    (is (not (failed? stub/harness)) "the honest stub still passes the same case")))

(deftest await-ms-is-an-option-with-the-old-default
  (let [seen (atom [])
        recording (fn [s]
                    (reify
                      rc/IRunSubmit (submit! [_ spec] (rc/submit! s spec))
                      rc/IRunObserve (observe-run [_ h] (rc/observe-run s h))
                      rc/IRunCancel (cancel! [_ h] (rc/cancel! s h))
                      rc/IRunAwait (await-run [_ h t] (swap! seen conj t) (rc/await-run s h t))))
        make #(harness-over recording)]
    (is (= 1000 conformance/default-await-ms))
    (is (= :ran (conformance/run-case make {} :cancel-flushes-before-cancelled)))
    (is (= [1000] @seen) "the default is the value that used to be hard-coded")
    (reset! seen [])
    (is (= :ran (conformance/run-case make {:await-ms 250} :cancel-flushes-before-cancelled)))
    (is (= [250] @seen))))

(deftest concurrent-submits-yield-distinct-runs
  (let [n     64
        s     (stub/create)
        start (java.util.concurrent.CountDownLatch. 1)
        futs  (mapv (fn [i]
                      (future
                        (.await start)
                        (rc/submit! s {:run/agent-id (str "agent-" i) :run/task "t"})))
                    (range n))
        _     (.countDown start)
        hs    (mapv #(:ok (deref % 5000 nil)) futs)]
    (is (every? rc/valid-run-handle? hs))
    (is (= n (count (set (map :run/id hs)))) "no two submits share a run id")
    (is (= (set (map #(str "agent-" %) (range n)))
           (set (map #(get-in % [:run/transcript-ref :agent-id]) hs)))
        "no run was overwritten by another")
    (is (every? #(r/ok? (rc/observe-run s %)) hs))))

(deftest finish-never-rewrites-a-terminal-state
  (let [s (stub/create)
        h (:ok (rc/submit! s {:run/agent-id "a" :run/task "t"}))]
    (stub/advance! s h)
    (rc/cancel! s h)
    (stub/finish! s h :succeeded)
    (is (= :cancelled (get-in (rc/observe-run s h) [:ok :run/state])))
    (stub/finish! s h :failed)
    (is (= :cancelled (:run/state (:ok (rc/await-run s h 50)))))))

(deftest finish-of-an-unknown-run-changes-nothing
  (let [s      (stub/create)
        before @(:state s)
        ghost  {:run/id "never-issued"}]
    (is (identical? s (stub/finish! s ghost :succeeded)))
    (is (= before @(:state s)) "no run is filed under the unknown id")
    (is (= :run/unknown (:error (rc/observe-run s ghost))))))

(deftest await-with-a-non-numeric-timeout-is-an-err
  (let [s (stub/create)
        h (:ok (rc/submit! s {:run/agent-id "a" :run/task "t"}))]
    (is (= :run/invalid-timeout (:error (rc/await-run s h nil))))
    (is (= :run/invalid-timeout (:error (rc/await-run s h "soon"))))
    (is (= :run/timeout (:error (rc/await-run s h 0))) "a numeric timeout still times out")))

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

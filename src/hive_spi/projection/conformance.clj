(ns hive-spi.projection.conformance
  "Conformance suite for FloorProjection. It ships in src so an implementation
   outside this repo can put its projection through it.

   `cases` is a vector of {:id :doc :run}; each :run is a (fn [harness])
   asserting with clojure.test/is. MAKE-HARNESS is a zero-arg fn returning a
   FRESH harness {:projection impl}, optionally with :entries, extra sample
   entries appended to `samples`. Two ways to run it:

     (defconformance my-projection make-harness)
       one deftest per case, named my-projection-<case-id>.

     (run-conformance make-harness)
       runs every case in the current clojure.test context, answers the ids.

   The suite checks the LAWS of the port, not a particular judgement: which
   sections an implementation withholds is its own business."
  (:require [clojure.test :as t :refer [is testing]]
            [hive-spi.projection.ports :as p]
            [malli.core :as m]
            [hive-spi.schema.typed-contract :as tc]))

;; SPDX-License-Identifier: MIT

(def samples
  "Floor entries shaped like the ones a drain carries: a bare rule, a rule with
   rationale and examples, a fenced guard source, a pinned convention, an
   empty body, a missing body, and a string-typed one (the spelling the
   production drain sends)."
  [{:id "s-bare" :T :axiom :C "# Rule\nDo the thing.\n"}
   {:id "s-why" :T :axiom
    :C (str "# AXIOM: inviolable\n\nOpening statement.\n\n## The rule\n\nNever do X.\n\n"
            "## Why\n\nA long story about the incident.\nMore of the story.\n\n"
            "## Examples\n\n- one\n- two\n")}
   {:id "s-guard" :T "axiom"
    :C (str "# Rule\n\n## Enforcement\n\n```guard-rule\n{:rule/id :guard/conformance-probe}\n"
            "(lots of source)\n```\nafter\n")}
   {:id "s-pinned" :T :convention :tags ["pinned"] :C "# Convention\n\n## History\n\nold\n"}
   {:id "s-empty" :T :axiom :C ""}
   {:id "s-none" :T :axiom}
   {:id "s-tiny" :T :axiom :C "## Why\n\nx\n"}])

(defn- entries [harness]
  (into samples (:entries harness)))

(defn- clen [e] (count (str (:C e))))

(def cases
  "Every conformance case, in run order."
  [{:id :answers-a-map
    :doc "project-floor answers a map for every sample, including one with no :C."
    :run (fn [{:keys [projection] :as h}]
           (doseq [e (entries h)]
             (is (map? (p/project-floor projection e)) (pr-str e))))}

   {:id :keeps-the-address
    :doc "The projected entry keeps :id and :T, so it stays pullable and keeps its lane."
    :run (fn [{:keys [projection] :as h}]
           (doseq [e (entries h)
                   :let [out (p/project-floor projection e)]]
             (is (= (:id e) (:id out)) (pr-str e))
             (is (= (:T e) (:T out)) (pr-str e))))}

   {:id :never-a-pointer
    :doc "A floor entry is never turned into a pointer: no :ref is introduced."
    :run (fn [{:keys [projection] :as h}]
           (doseq [e (entries h)]
             (is (not (:ref (p/project-floor projection e))) (pr-str e))))}

   {:id :never-grows
    :doc "The projected :C is never longer than the entry's, so a pointer never costs more than what it replaced."
    :run (fn [{:keys [projection] :as h}]
           (doseq [e (entries h)]
             (is (<= (clen (p/project-floor projection e)) (clen e)) (pr-str e))))}

   {:id :idempotent
    :doc "Projecting a projected entry changes nothing: the drain writes its buffer back and projects it again."
    :run (fn [{:keys [projection] :as h}]
           (doseq [e (entries h)
                   :let [once (p/project-floor projection e)]]
             (is (= once (p/project-floor projection once)) (pr-str e))))}

   {:id :installs-in-the-slot
    :doc "The projection installs in the port's slot and the host call site uses it."
    :run (fn [{:keys [projection] :as h}]
           (let [prior (p/get-projection)]
             (try
               (is (identical? projection (p/set-projection! projection)))
               (is (true? (p/projection-set?)))
               (doseq [e (entries h)]
                 (is (= (p/project-floor projection e) (p/project-or-identity e))))
               (finally
                 (if prior (p/set-projection! prior) (p/clear-projection!))))))}])

(def case-ids
  "The case ids, in run order."
  (mapv :id cases))

(defn run-case
  "Run case ID against a fresh (MAKE-HARNESS) in the current clojure.test
   context. Answers ID."
  [make-harness id]
  (let [{:keys [run doc]} (or (some #(when (= id (:id %)) %) cases)
                              (throw (ex-info "unknown conformance case"
                                              {:id id :known case-ids})))]
    (testing (str (name id) ": " doc)
      (run (make-harness)))
    id))

(defn run-conformance
  "Run every case against fresh harnesses. Answers the ids that ran."
  [make-harness]
  (mapv #(run-case make-harness %) case-ids))

(def MakeHarness
  "A zero-arg fn returning a fresh harness. Generates one over the identity
   projection."
  [:fn {:error/message "a zero-arg harness constructor"
        :gen/schema    [:enum :identity]
        :gen/fmap      (fn [_] (requiring-resolve 'hive-spi.projection.stub/harness))}
   ifn?])

(def CaseId (into [:enum] case-ids))

(m/=> run-case [:=> [:cat MakeHarness CaseId] CaseId])
(tc/ann-op run-case)
(m/=> run-conformance [:=> [:cat MakeHarness] [:vector CaseId]])
(tc/ann-op run-conformance)

(defmacro defconformance
  "Emit one deftest per case, named PREFIX-<case-id>, each running that case
   against a fresh (MAKE-HARNESS)."
  [prefix make-harness]
  `(do ~@(for [id case-ids]
           `(t/deftest ~(symbol (str (name prefix) "-" (name id)))
              (run-case ~make-harness ~id)))))

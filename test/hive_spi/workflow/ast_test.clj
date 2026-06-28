(ns hive-spi.workflow.ast-test
  "Contract + property tests for hive-spi.workflow.ast — malli schema,
   smart constructors, structural-path :wf/id, and ->edn/edn->ast round-trip."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-spi.workflow.ast :as ast]))

;; ---------------------------------------------------------------------------
;; Schema + smart-constructor surface
;; ---------------------------------------------------------------------------

(deftest ops-set-is-canonical
  (testing "Exactly the 7 design-locked ops are present"
    (is (= #{:wf.op/seq :wf.op/par :wf.op/gate :wf.op/loop
             :wf.op/let :wf.op/call :wf.op/pure}
           ast/ops))))

(deftest leaf-nodes-validate
  (testing "call* with a verb validates"
    (let [n (ast/call* {:verb :fx/log :args {:msg "hi"}})]
      (is (ast/valid? n))
      (is (= :wf.op/call (:wf/op n)))
      (is (= [] (:wf/id n)))))
  (testing "pure* validates"
    (is (ast/valid? (ast/pure* {:value 42})))))

(deftest composite-nodes-validate
  (testing "seq* with mixed children validates"
    (let [n (ast/seq* [(ast/call* {:verb :fx/a})
                       (ast/par* [(ast/call* {:verb :fx/b})
                                  (ast/call* {:verb :fx/c})])
                       (ast/pure* {:value :done})])]
      (is (ast/valid? n))
      (is (nil? (ast/explain n))))))

(deftest structural-paths-are-assigned
  (testing ":wf/id is the index path from root"
    (let [n (ast/seq* [(ast/call* {:verb :fx/a})
                       (ast/par* [(ast/call* {:verb :fx/b})
                                  (ast/call* {:verb :fx/c})])])]
      (is (= [] (:wf/id n)))
      (is (= [0] (-> n :wf/children (nth 0) :wf/id)))
      (is (= [1] (-> n :wf/children (nth 1) :wf/id)))
      (is (= [1 0] (-> n :wf/children (nth 1) :wf/children (nth 0) :wf/id)))
      (is (= [1 1] (-> n :wf/children (nth 1) :wf/children (nth 1) :wf/id))))))

(deftest gate-loop-let-shapes
  (testing "gate* loop* let* validate with their typical args"
    (is (ast/valid? (ast/gate* {:when :pred/ready?}
                               [(ast/call* {:verb :fx/then})
                                (ast/call* {:verb :fx/else})])))
    (is (ast/valid? (ast/loop* {:until :pred/done? :max-iters 3}
                               [(ast/call* {:verb :fx/body})])))
    (is (ast/valid? (ast/let* {:bindings [:x :wf.ref/input]}
                              [(ast/call* {:verb :fx/use-x})])))))

;; ---------------------------------------------------------------------------
;; Round-trip property test
;; ---------------------------------------------------------------------------

(defn- gen-leaf
  "Generator for a leaf node (:call or :pure)."
  []
  (gen/one-of
   [(gen/fmap (fn [v] (ast/call* {:verb v})) gen/keyword-ns)
    (gen/fmap (fn [v] (ast/pure* {:value v})) gen/small-integer)]))

(defn- gen-node
  "Bounded recursive generator for AST nodes (max depth ~3)."
  [depth]
  (if (zero? depth)
    (gen-leaf)
    (gen/one-of
     [(gen-leaf)
      (gen/fmap (fn [cs] (ast/seq* (vec cs)))
                (gen/vector (gen-node (dec depth)) 1 3))
      (gen/fmap (fn [cs] (ast/par* (vec cs)))
                (gen/vector (gen-node (dec depth)) 1 3))])))

(def gen-ast (gen-node 3))

(defspec edn-round-trip-preserves-ast 50
  (prop/for-all [ast-node gen-ast]
                (let [edn (ast/->edn ast-node)
                      back (ast/edn->ast edn)]
                  (and (ast/valid? back)
                       (= ast-node back)))))

(deftest round-trip-direct
  (testing "Hand-written AST is unchanged by ->edn / edn->ast"
    (let [n (ast/seq* [(ast/call* {:verb :fx/a})
                       (ast/par* [(ast/call* {:verb :fx/b})
                                  (ast/call* {:verb :fx/c})])
                       (ast/pure* {:value :done})])]
      (is (= n (ast/edn->ast (ast/->edn n))))))
  (testing "edn->ast NORMALISES (reassigns :wf/id) on surgically edited subtrees"
    (let [raw {:wf/op :wf.op/seq
               :wf/args {}
               :wf/children [{:wf/op :wf.op/call
                              :wf/args {:verb :fx/a}
                              :wf/children []
                              :wf/id [99]      ; wrong by construction
                              :wf/meta {}}]
               :wf/id []
               :wf/meta {}}
          normalised (ast/edn->ast raw)]
      (is (= [0] (-> normalised :wf/children (nth 0) :wf/id))
          "edn->ast reassigns structural paths"))))

(deftest ->edn-rejects-malformed
  (testing "->edn FAILS LOUD on a non-conforming map"
    (is (thrown? Exception
                 (ast/->edn {:wf/op :wf.op/nope
                             :wf/args {}
                             :wf/children []
                             :wf/id []
                             :wf/meta {}})))))

(ns hive-spi.memory.semantic-negative-test
  "The negative branches of the search-similar conformance cases. A passing
   store proves little unless a store that breaks a bound is seen to fail, so
   each case is run against a deliberately wrong store and must report a
   failure (or an error, for a store that throws), and against the correct
   reference stub, with and without an embedder, where it must pass."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is testing]]
            [hive-spi.memory.conformance :as conformance]
            [hive-spi.memory.ports :as ports]
            [hive-spi.memory.stub :as stub]))

;; SPDX-License-Identifier: MIT

(defn- reported
  "Run F with clojure.test reporting captured; answers the :pass, :fail and
   :error events it raised, so a case that must fail can be asserted on
   without failing this suite. An exception escaping F (a store that throws
   where the contract forbids it) is recorded as an :error event."
  [f]
  (let [events (atom [])]
    (binding [t/report             #(swap! events conj %)
              t/*report-counters*  (ref t/*initial-report-counters*)
              t/*testing-contexts* (list)]
      (try (f)
           (catch Throwable ex
             (swap! events conj {:type :error :actual ex}))))
    (group-by :type @events)))

(defn- run-on
  "Case ID against stores from MAKE-STORE under OPTS, as grouped events."
  [make-store opts id]
  (reported #(conformance/run-case make-store opts id)))

(defn- failed? [ev] (boolean (or (seq (:fail ev)) (seq (:error ev)))))

(defn- clean? [ev] (and (empty? (:fail ev)) (empty? (:error ev)) (pos? (count (:pass ev)))))

(defn- bag-embedder [text]
  (let [v (double-array 8)]
    (doseq [c (str text)]
      (let [i (mod (int c) 8)]
        (aset v i (inc (aget v i)))))
    (vec v)))

(defn- wrong-store
  "A store whose writes behave (backed by a real stub) but whose search-similar
   is SEARCH-FN, a (fn [live-entries query opts]); SUPPORTS? is what
   supports-semantic-search? answers. DELETES? false makes delete-entry! leave
   the entry in what search sees."
  [{:keys [search-fn supports? deletes?] :or {supports? true deletes? true}}]
  (let [real (stub/create-store {})
        seen (atom {})]
    (reify ports/IMemoryStore
      (add-entry! [_ e]
        (let [id (ports/add-entry! real e)]
          (swap! seen assoc id (ports/get-entry real id))
          id))
      (get-entry [_ id] (ports/get-entry real id))
      (delete-entry! [_ id]
        (when deletes? (swap! seen dissoc id))
        (ports/delete-entry! real id))
      (reset-store! [_] (reset! seen {}) (ports/reset-store! real))
      (supports-semantic-search? [_] supports?)
      (search-similar [_ q opts] (search-fn (vec (vals @seen)) q opts)))))

(def ^:private everything (fn [live _q _opts] live))

(def ^:private empty-vec (fn [_live _q _opts] []))

;;; ---- the correct stub passes every new case ------------------------------

(def ^:private search-cases
  [:search-similar-empty-store
   :search-similar-blank-query
   :search-similar-honours-limit
   :search-similar-honours-filters
   :search-similar-skips-deleted
   :search-similar-degraded-reason])

(deftest reference-stub-passes-the-negative-cases
  (doseq [[label make] [["degraded (no embedder)" #(stub/create-store {})]
                        ["positive (embedder)" #(stub/create-store {:embedder bag-embedder})]]
          id search-cases]
    (testing (str label " " id)
      (is (clean? (run-on make {:degraded-reason? true} id))))))

(deftest negative-cases-are-registered
  (is (every? (set conformance/case-ids) search-cases)))

;;; ---- a wrong store fails the case that names its fault -------------------

(deftest store-that-throws-on-an-empty-store-fails
  (is (failed? (run-on #(wrong-store {:search-fn (fn [_ _ _] (throw (ex-info "boom" {})))})
                       {} :search-similar-empty-store))))

(deftest store-that-fabricates-hits-on-an-empty-store-fails
  (is (failed? (run-on #(wrong-store {:search-fn (fn [_ _ _] [{:id "ghost" :score 1.0}])})
                       {} :search-similar-empty-store))))

(deftest store-that-throws-on-a-blank-query-fails
  (is (failed? (run-on #(wrong-store {:search-fn (fn [live q _]
                                                  (if (= "" (str/trim q))
                                                    (throw (ex-info "blank" {}))
                                                    live))})
                       {} :search-similar-blank-query))))

(deftest store-that-ignores-limit-fails
  (is (failed? (run-on #(wrong-store {:search-fn everything})
                       {} :search-similar-honours-limit))))

(deftest store-that-ignores-filters-fails
  (is (failed? (run-on #(wrong-store {:search-fn everything})
                       {} :search-similar-honours-filters))))

(deftest store-that-returns-deleted-entries-fails
  (is (failed? (run-on #(wrong-store {:search-fn everything :deletes? false})
                       {} :search-similar-skips-deleted))))

(deftest unsupported-store-that-omits-the-degraded-reason-fails
  (testing "a plain empty vector carries no :hive-spi.memory/degraded metadata"
    (is (failed? (run-on #(wrong-store {:supports? false :search-fn empty-vec})
                         {:degraded-reason? true} :search-similar-degraded-reason)))))

(deftest unsupported-store-that-names-no-reason-fails
  (is (failed? (run-on #(wrong-store {:supports? false
                                      :search-fn (fn [_ _ _] (ports/degraded-search-result {}))})
                       {:degraded-reason? true} :search-similar-degraded-reason))))

(deftest supported-store-that-claims-degraded-fails
  (is (failed? (run-on #(wrong-store {:supports? true
                                      :search-fn (fn [_ _ _]
                                                   (ports/degraded-search-result {:reason :x}))})
                       {:degraded-reason? true} :search-similar-degraded-reason))))

(deftest degraded-reason-case-is-opt-in
  (let [r (conformance/run-conformance #(wrong-store {:supports? false :search-fn empty-vec})
                                       {:skip (zipmap (remove #{:search-similar-degraded-reason}
                                                              conformance/case-ids)
                                                      (repeat "wrong store"))})]
    (is (contains? (:skipped r) :search-similar-degraded-reason))
    (is (empty? (:ran r)))))

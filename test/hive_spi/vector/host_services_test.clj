(ns hive-spi.vector.host-services-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-spi.vector.host-services :as hs]))

;; SPDX-License-Identifier: MIT

(use-fixtures :each (fn [t] (hs/unregister-services!) (try (t) (finally (hs/unregister-services!)))))

(deftest registration-merges-and-validates
  (is (= #{:embedding/configured?}
         (set (keys (hs/register-services! {:embedding/configured? (constantly true)})))))
  (hs/register-services! {:config/get-in (fn [path] [:cfg path])})
  (is (= #{:embedding/configured? :config/get-in} (hs/capabilities)))
  (is (hs/available? :config/get-in))
  (is (= [:cfg [:a]] (hs/invoke :config/get-in [:a])))
  (testing "a malformed registration fails at the registrar"
    (is (thrown? clojure.lang.ExceptionInfo (hs/register-services! {:x 1})))
    (is (thrown? clojure.lang.ExceptionInfo (hs/register-services! {"x" identity}))))
  (hs/unregister-services! [:config/get-in])
  (is (= #{:embedding/configured?} (hs/capabilities))))

(deftest invoke-on-an-absent-capability-throws-with-a-reason
  (let [d (ex-data (try (hs/invoke :routing/default-space {})
                        (catch clojure.lang.ExceptionInfo e e)))]
    (is (= :host-services/unavailable (:error d)))
    (is (= :routing/default-space (:capability d)))))

(deftest soft-resolves-at-call-time
  (let [f (hs/soft :query/shape (fn [_ text] text))]
    (is (= "q" (f "tmpl" "q")) "absent: the declared fallback")
    (hs/register-services! {:query/shape (fn [t text] (str t ":" text))})
    (is (= "tmpl:q" (f "tmpl" "q")) "registered after the soft fn was built")
    (hs/unregister-services! [:query/shape])
    (is (= "q" (f "tmpl" "q")) "absence is never permanent either way"))
  (testing "no fallback throws like invoke"
    (is (thrown? clojure.lang.ExceptionInfo ((hs/soft :gate/lane))))))

(deftest catalog-documents-every-capability
  (is (every? keyword? (keys hs/catalog)))
  (is (every? string? (vals hs/catalog)))
  (is (contains? hs/catalog :vector/port)))

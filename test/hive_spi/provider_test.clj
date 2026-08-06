(ns hive-spi.provider-test
  "A registry is where dependency inversion is either enforced or quietly lost.
   Every test here is one of the three rules the namespace claims to enforce:
   validation happens at registration, capability comes off the profile, and
   the subject selects its provider."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-spi.provider :as provider]))

(defprotocol IRail
  (charge! [this amount])
  (refund! [this amount]))

(defn- rail
  [label]
  (reify IRail
    (charge! [_ amount] [label :charged amount])
    (refund! [_ amount] [label :refunded amount])))

(def RailProfile
  (provider/profile-schema [[:provider/currency :keyword]
                            [:provider/min-confirmations {:optional true} :int]]))

(def chain
  (provider/entry #:provider{:id :chain
                             :currency :xmr
                             :min-confirmations 10
                             :capabilities #{:poll :refund}}
                  (rail :chain)))

(def cards
  (provider/entry #:provider{:id :cards
                             :currency :usd
                             :capabilities #{:refund}}
                  (rail :cards)))

(def manual
  (provider/entry #:provider{:id :manual :currency :usd}
                  (rail :manual)))

(def rails
  (provider/registry [chain cards manual]
                     {:schema RailProfile
                      :satisfies-port? #(satisfies? IRail %)
                      :label "rails"}))

;; =============================================================================
;; registration is where a bad provider dies
;; =============================================================================

(deftest a-profile-that-fails-its-schema-is-refused-at-registration-test
  (testing "so an unusable provider is a boot failure, not a first-caller surprise"
    (is (thrown? clojure.lang.ExceptionInfo
                 (provider/registry [(provider/entry #:provider{:id :broken} (rail :broken))]
                                    {:schema RailProfile})))))

(deftest an-implementation-that-does-not-implement-the-port-is-refused-test
  (is (thrown? clojure.lang.ExceptionInfo
               (provider/registry [(provider/entry #:provider{:id :nope :currency :usd}
                                                   "not a rail")]
                                  {:schema RailProfile
                                   :satisfies-port? #(satisfies? IRail %)}))))

(deftest a-profile-without-an-id-is-refused-test
  (is (thrown? clojure.lang.ExceptionInfo
               (provider/registry [(provider/entry {:provider/currency :usd} (rail :x))] {}))))

(deftest registration-is-a-value-transformation-test
  (let [extended (provider/add rails
                               (provider/entry #:provider{:id :voucher :currency :usd}
                                               (rail :voucher))
                               {:schema RailProfile})]
    (is (= #{:chain :cards :manual :voucher} (provider/ids extended)))
    (testing "the original registry is untouched — two of them can coexist"
      (is (= #{:chain :cards :manual} (provider/ids rails))))
    (is (= #{:chain :cards} (provider/ids (-> extended
                                              (provider/without :manual)
                                              (provider/without :voucher)))))))

;; =============================================================================
;; behaviour is read, never inferred
;; =============================================================================

(deftest a-component-asks-what-a-provider-does-not-which-one-it-is-test
  (is (= :xmr (provider/behaviour rails :chain :provider/currency)))
  (is (= 10 (provider/behaviour rails :chain :provider/min-confirmations)))
  (testing "a provider that declares no threshold gets the caller's default"
    (is (= 0 (provider/behaviour rails :cards :provider/min-confirmations 0))))
  (testing "an unregistered provider is not an exception, it is an absence"
    (is (nil? (provider/behaviour rails :ghost :provider/currency)))
    (is (false? (provider/registered? rails :ghost)))))

(deftest capability-comes-off-the-profile-test
  (is (true? (provider/capable? rails :chain :poll)))
  (is (false? (provider/capable? rails :cards :poll)))
  (testing "a provider that declares nothing is capable of nothing"
    (is (= #{} (provider/capabilities rails :manual)))
    (is (false? (provider/capable? rails :manual :refund))))
  (testing "and an absent provider is not capable, so callers need not check twice"
    (is (false? (provider/capable? rails :ghost :poll))))
  (is (= #{:chain :cards} (provider/with-capability rails :refund)))
  (is (= #{:chain} (provider/with-capability rails :poll))))

(deftest profiles-can-be-grouped-by-any-declared-key-test
  (is (= #{:xmr :usd} (set (keys (provider/profiles-by rails :provider/currency)))))
  (is (= 2 (count (get (provider/profiles-by rails :provider/currency) :usd)))))

;; =============================================================================
;; the subject selects
;; =============================================================================

(def invoice {:invoice/id 1 :invoice/provider :chain})

(deftest the-subject-carries-the-provider-id-test
  (is (= :chain (provider/subject-id invoice :invoice/provider)))
  (is (= chain (provider/for-subject rails invoice :invoice/provider))))

(deftest via-applies-to-whatever-the-subject-selected-test
  (is (= [:chain :charged 500]
         (provider/via rails invoice :invoice/provider #(charge! % 500))))

  (testing "a subject naming an unregistered provider selects nothing"
    (is (nil? (provider/via rails {:invoice/provider :ghost} :invoice/provider
                            #(charge! % 500))))))

(deftest a-provider-is-never-asked-to-do-what-its-profile-forbids-test
  (testing "the profile is the permission"
    (is (= [:chain :refunded 500]
           (provider/via-capable rails invoice :invoice/provider :refund
                                 #(refund! % 500))))
    (is (nil? (provider/via-capable rails {:invoice/provider :manual} :invoice/provider
                                    :refund #(refund! % 500))))))

(deftest selection-reads-the-subject-through-any-accessor-test
  (let [nested {:order {:rail :cards}}]
    (is (= :cards (provider/subject-id nested (comp :rail :order))))
    (is (= [:cards :charged 1]
           (provider/via rails nested (comp :rail :order) #(charge! % 1))))))

;; =============================================================================
;; substitutability
;; =============================================================================

(deftest every-registered-provider-is-substitutable-for-the-port-test
  (let [opts {:schema RailProfile :satisfies-port? #(satisfies? IRail %)}]
    (is (true? (provider/conforming? rails opts)))
    (is (every? (fn [[_ v]] (and (:profile-valid? v) (:implements-port? v)))
                (provider/conformance rails opts)))

    (testing "and the check is real — a registry built without validation fails it"
      (let [loose (provider/registry [(provider/entry #:provider{:id :loose} (rail :loose))] {})]
        (is (false? (provider/conforming? loose opts)))))))

(deftest a-registry-with-no-declared-port-conforms-vacuously-test
  (is (true? (provider/conforming? rails {:schema RailProfile}))))

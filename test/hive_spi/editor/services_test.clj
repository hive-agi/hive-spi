(ns hive-spi.editor.services-test
  "Capability-as-data has one failure mode that matters: a missing capability
   must become an explicit unavailable RESULT, never nil-as-data and never an
   exception. Registration must reject malformed maps at the registrar."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [hive-spi.editor.services :as svc]
            [hive-schemas.test :as hst]))

;; SPDX-License-Identifier: MIT

(use-fixtures :each (fn [f] (svc/reset-services!) (f) (svc/reset-services!)))

(deftest register-and-invoke
  (svc/register-services! {:eval-elisp (fn [code] {:success true :result code})})
  (is (= #{:eval-elisp} (svc/capabilities)))
  (is (svc/available? :eval-elisp))
  (is (= {:success true :result "(+ 1 2)"}
         (svc/invoke-default :eval-elisp "(+ 1 2)"))))

(deftest registration-merges-in-stages
  (svc/register-services! {:eval-elisp identity})
  (svc/register-services! {:current-buffer (constantly "*scratch*")})
  (is (= #{:eval-elisp :current-buffer} (svc/capabilities))))

(deftest missing-capability-is-an-explicit-result
  (svc/register-services! {:eval-elisp identity})
  (let [r (svc/invoke-default :teleport "there")]
    (is (false? (:success r)))
    (is (= :editor/capability-unavailable (:error r)))
    (is (= :teleport (:capability r)))
    (is (= [:eval-elisp] (:available r)))))

(deftest unknown-registry-key-is-unavailable-not-a-throw
  (let [r (svc/invoke :nobody :eval-elisp "x")]
    (is (false? (:success r)))
    (is (= [] (:available r)))))

(deftest keys-are-isolated
  (svc/register-services! :a {:eval-elisp (constantly :from-a)})
  (svc/register-services! :b {:eval-elisp (constantly :from-b)})
  (is (= :from-a (svc/invoke :a :eval-elisp)))
  (is (= :from-b (svc/invoke :b :eval-elisp)))
  (is (empty? (svc/capabilities))))

(deftest unregister-drops-only-its-key
  (svc/register-services! :a {:eval-elisp identity})
  (svc/register-services! :b {:eval-elisp identity})
  (svc/unregister-services! :a)
  (is (not (svc/available? :a :eval-elisp)))
  (is (svc/available? :b :eval-elisp)))

(deftest malformed-registration-throws-at-the-registrar
  (is (thrown? clojure.lang.ExceptionInfo (svc/register-services! [:not "a map"])))
  (is (thrown? clojure.lang.ExceptionInfo (svc/register-services! {"eval" identity})))
  (is (thrown? clojure.lang.ExceptionInfo (svc/register-services! {:eval "not a fn"}))))

(hst/deftrifecta-from-schema unavailable-names-what-is-available
  hive-spi.editor.services/unavailable
  {:in  [:cat [:enum :default :other] :keyword]
   :out svc/Unavailable
   :rel (fn [[k cap] out]
          (and (= cap (:capability out))
               (= k (:registry-key out))
               (= (vec (sort (svc/capabilities k))) (:available out))))})

(hst/deftrifecta-from-schema register-services-merges-under-its-key
  hive-spi.editor.services/register-services!
  {:in  [:cat [:enum :covf-a :covf-b] svc/Capabilities]
   :out svc/Capabilities
   :rel (fn [[k caps] out]
          (and (= out (get (svc/registered) k))
               (every? #(svc/available? k %) (keys caps))))})

(ns covf-falsify
  "REPL helper for the cov-fleet wave (not loaded by any test or build):
   instrument every contracted public var of the given namespaces, throwing
   on the first violation, and run the given test namespaces under it."
  (:require [clojure.test :as t]
            [malli.core :as m]
            [malli.instrument :as mi]))

(defn falsify
  "Load NSS (no :reload, so defonce slots and guarded protocols stay intact),
   instrument their contracted vars, then load and run TEST-NSS."
  [nss test-nss]
  (run! require nss)
  (let [vars (set (for [n nss [s v] (ns-publics n) :when (get-in (m/function-schemas) [n s])] v))]
    (mi/unstrument!)
    (mi/instrument! {:filters [(mi/-filter-var vars)]
                     :report  (fn [type data]
                                (throw (ex-info (str type) (select-keys data [:fn-name :args :value]))))})
    (doseq [tn test-nss] (require tn :reload))
    (let [r (apply t/run-tests test-nss)]
      (mi/unstrument!)
      [(count vars) (select-keys r [:test :pass :fail :error])])))

(defn reruns
  "Run TEST-NSS N times (fresh seeds); the fail/error counts of each run."
  [test-nss n]
  (vec (for [_ (range n)] (select-keys (apply t/run-tests test-nss) [:fail :error]))))

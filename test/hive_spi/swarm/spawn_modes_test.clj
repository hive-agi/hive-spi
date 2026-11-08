(ns hive-spi.swarm.spawn-modes-test
  "Spawn modes: core modes baked in, addon modes registered at runtime. The
   trifectas relate each contracted lookup to the registry value; the golden
   pins what an addon registration changes and what deregistration restores."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [hive-schemas.test :as hst]
            [hive-test.golden :refer [deftest-golden]]
            [hive-spi.swarm.spawn-modes :as sm]))

;; SPDX-License-Identifier: MIT

(def ^:private probe-modes [:tmux :ghost])

(use-fixtures :each
  (fn [f]
    (run! sm/deregister-mode! probe-modes)
    (try (f) (finally (run! sm/deregister-mode! probe-modes)))))

(hst/deftrifecta-from-schema valid-mode-contract
  hive-spi.swarm.spawn-modes/valid-mode?
  {:in          sm/ModeRef
   :out         :boolean
   :rel         (fn [m out]
                  (= out (contains? (sm/registry) (if (keyword? m) m (keyword m)))))
   :golden-path "test/golden/hive_spi/swarm/spawn_modes/valid-mode.edn"
   :num-tests   200})

(hst/deftrifecta-from-schema capabilities-contract
  hive-spi.swarm.spawn-modes/capabilities
  {:in          sm/ModeRef
   :out         [:set :keyword]
   :rel         (fn [m out] (= out (get-in (sm/registry) [m :capabilities] #{})))
   :golden-path "test/golden/hive_spi/swarm/spawn_modes/capabilities.edn"
   :num-tests   200})

(hst/deftrifecta-from-schema requires-emacs-contract
  hive-spi.swarm.spawn-modes/requires-emacs?
  {:in          sm/ModeRef
   :out         :boolean
   :rel         (fn [m out] (= out (contains? sm/emacs-modes m)))
   :golden-path "test/golden/hive_spi/swarm/spawn_modes/requires-emacs.edn"
   :num-tests   200})

(deftest-golden addon-registration-golden
  "test/golden/hive_spi/swarm/spawn_modes/addon-registration.edn"
  (let [spec {:description "tmux pane" :requires-emacs? false :io-model :buffer
              :slot-limit 3 :mcp? true :capabilities #{:kill :dispatch}}
        before {:valid? (sm/valid-mode? :tmux) :enum (sm/mcp-enum)}
        ret    (sm/register-mode! :tmux spec)
        during {:valid?     (sm/valid-mode? "tmux")
                :slot-limit (sm/slot-limit :tmux)
                :io-model   (sm/io-model :tmux)
                :caps       (vec (sort (sm/capabilities :tmux)))
                :kill?      (sm/has-capability? :tmux :kill)
                :enum       (sm/mcp-enum)}
        gone   (sm/deregister-mode! :tmux)]
    {:before before :returned ret :during during :deregistered gone
     :after {:valid? (sm/valid-mode? :tmux) :enum (sm/mcp-enum)}}))

(deftest core-modes-are-not-aliases-test
  (testing ":headless is abstract: resolve-alias leaves it alone"
    (is (= :headless (sm/resolve-alias :headless)))
    (is (= :anything (sm/resolve-alias :anything))))
  (is (= 6 (sm/slot-limit :claude)))
  (is (nil? (sm/slot-limit :headless))))

(deftest deregistering-a-core-mode-is-a-no-op-test
  (is (= :claude (sm/deregister-mode! :claude)))
  (is (sm/valid-mode? :claude)))

(ns hive-spi.notify-test
  "Contract-scaffold tests for hive-spi.notify/INotify — protocol surface
   + satisfies?-probe on a stub backend. Behavioural tests land in
   hive-notify (M6) once concrete backends ship."
  (:require [clojure.test :refer [deftest is testing]]
            [hive-spi.notify :as n]))

(defn- protocol-method-names [pvar]
  (->> @pvar :sigs vals (map :name) set))

(defrecord NoopNotify []
  n/INotify
  (notify-id          [_]   :cli)
  (backend-available? [_]   true)
  (accepts?           [_ _] true)
  (notify!            [_ _] {:delivered? true :backend :cli :detail {}}))

(deftest inotify-protocol-surface
  (testing "INotify exposes exactly the 4 SPI methods"
    (is (= '#{notify-id backend-available? accepts? notify!}
           (protocol-method-names #'n/INotify)))))

(deftest inotify-stub-satisfies
  (testing "a noop record satisfies INotify"
    (is (satisfies? n/INotify (->NoopNotify))))
  (testing "notify-id returns one of the recognised backend kws"
    (is (contains? #{:desktop :sound :cli :widget}
                   (n/notify-id (->NoopNotify)))))
  (testing "notify! never throws and returns a {:delivered? ...} map"
    (let [result (n/notify! (->NoopNotify)
                            {:event-type :workflow/started
                             :summary "run started"
                             :body "ok"
                             :urgency :normal
                             :level :info})]
      (is (map? result))
      (is (contains? result :delivered?)))))

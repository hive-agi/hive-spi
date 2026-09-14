(ns hive-spi.vessel-test
  (:require [clojure.test :refer [deftest is]]
            [hive-spi.vessel :as vessel]))

(deftest renderer-has-no-ambient-editor-authority
  (let [received (atom [])
        renderer (reify vessel/IRenderer
                   (renderer-id [_] :test)
                   (render! [_ ops] (swap! received into ops) {:ok true}))
        ops [{:op :ui/show-panel :panel/id "operator"
              :doc {:doc/title "Room" :doc/blocks []}}]]
    (is (= :test (vessel/renderer-id renderer)))
    (is (= {:ok true} (vessel/render! renderer ops)))
    (is (= ops @received))
    (is (= #{:renderer-id :render!} (set (keys (:sigs vessel/IRenderer)))))))

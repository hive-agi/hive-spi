(ns hive-spi.kanban.registry-test
  "The kanban facade over the recording stub and the Noops: reads route to
   the installed provider, an empty registry degrades to empty reads and
   {:err} writes."
  (:require [clojure.test :refer [use-fixtures]]
            [hive-schemas.test :as hst]
            [hive-spi.kanban :as k]
            [hive-spi.kanban.registry :as r]
            [hive-spi.kanban.stub :as stub]
            [hive-test.golden :refer [deftest-golden]]))

;; SPDX-License-Identifier: MIT

(use-fixtures :each (fn [f] (r/reset-providers!) (try (f) (finally (r/reset-providers!)))))

(hst/deftrifecta-from-schema list-tasks-routes-to-the-installed-stub
  hive-spi.kanban.registry/list-tasks
  {:in  [:cat [:map [:status {:optional true} [:maybe k/Status]]]]
   :out [:sequential k/Task]
   :rel (fn [[q] out]
          ;; the degraded Noop board is empty whatever the query
          (= [] out))})

(hst/deftrifecta-from-schema noop?-names-only-the-defaults
  hive-spi.kanban.registry/noop?
  {:in  [:cat [:or r/Provider :nil :map]]
   :out :boolean
   :rel (fn [[x] out] (= out (contains? (set (vals r/noops)) x)))})

(deftest-golden facade-over-stub-and-noops
  "test/golden/hive_spi/kanban/registry.edn"
  (let [degraded {:list   (r/list-tasks {})
                  :get    (r/get-task "t1")
                  :create (r/create-task! {:title "x"})
                  :noop?  (r/noop? (r/provider :IKanbanRead))}
        s        (stub/install! (stub/recording-kanban [{:id "t1" :title "one" :status "todo"}]))]
    {:degraded   degraded
     :registered [(r/registered? :IKanbanRead) (r/registered? :IKanbanWrite)]
     :list       (r/list-tasks {:status "todo"})
     :move       (r/transition! {:task-id "t1" :new-status "done"})
     :miss       (r/transition! {:task-id "zz" :new-status "done"})
     :create     (r/create-task! {:title "two"})
     ;; refused by the register! contract when instrumented, else by the body
     :unknown    (try (r/register! :IKanbanNope s) :accepted (catch clojure.lang.ExceptionInfo _ :refused))
     :after      (do (r/unregister! :IKanbanRead) (r/list-tasks {}))}))

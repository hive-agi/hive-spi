(ns hive-spi.swarm.ports.memory-scope-propagation-test
  "IDiscPropagation is an OPTIONAL extension of the memory-scope port:
   `content-changed!` must answer nil for every host that lacks it, and must
   never throw."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [hive-spi.swarm.ports.memory-scope :as port]))

;; SPDX-License-Identifier: MIT

(defn- restoring-the-slot [f]
  (let [found (when (port/memory-scope-set?) (port/get-memory-scope))]
    (port/clear-memory-scope!)
    (try
      (f)
      (finally
        (if found
          (port/set-memory-scope! found)
          (port/clear-memory-scope!))))))

(use-fixtures :each restoring-the-slot)

(defn- scope-only-port []
  (reify port/IProjectScope
    (project-id-for-path [_ _] "p")
    (infer-scope-from-path [_ _] "p")))

(defn- propagating-port [on-change]
  (reify
    port/IProjectScope
    (project-id-for-path [_ _] "p")
    (infer-scope-from-path [_ _] "p")
    port/IDiscPropagation
    (-content-changed! [_ path cause] (on-change path cause))))

(deftest with-nothing-installed-there-is-nothing-to-tell
  (is (nil? (port/content-changed! "/p/a.clj" :hash-mismatch))))

(deftest a-host-without-the-extension-answers-nil
  (port/set-memory-scope! (scope-only-port))
  (is (nil? (port/content-changed! "/p/a.clj" :hash-mismatch))))

(deftest a-host-with-the-extension-is-told-and-its-summary-returned
  (let [told (atom [])]
    (port/set-memory-scope!
     (propagating-port (fn [path cause]
                         (swap! told conj [path cause])
                         {:propagated 2 :grounded 1})))
    (is (= {:propagated 2 :grounded 1}
           (port/content-changed! "/p/a.clj" :hash-mismatch)))
    (is (= [["/p/a.clj" :hash-mismatch]] @told))))

(deftest a-host-that-throws-does-not-fail-the-caller
  (port/set-memory-scope!
   (propagating-port (fn [_ _] (throw (ex-info "store down" {})))))
  (is (nil? (port/content-changed! "/p/a.clj" :hash-mismatch))))

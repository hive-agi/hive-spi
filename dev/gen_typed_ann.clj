(ns gen-typed-ann
  "Generator: lift every public defn that carries a top-level (m/=> f ..)
   contract to the typed rung by inserting `(tc/ann-op f)` right after the
   contract, and `[hive-spi.schema.typed-contract :as tc]` into the ns
   :require. Idempotent: a subject already annotated is left alone.

   run:  bb dev/gen_typed_ann.clj [--dry-run] [src-dir]

   The type itself is NOT written into source: tc/ann-op projects it from the
   registered contract when the namespace loads, so the two cannot drift."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rewrite-clj.node :as n]
            [rewrite-clj.zip :as z]))

;; SPDX-License-Identifier: MIT

(def skip
  "Namespaces hive-spi.schema.typed itself loads (a require back would cycle),
   and the annotation namespaces."
  #{"hive_spi/schema/typed.cljc" "hive_spi/schema/registry.cljc"
    "hive_spi/schema/typed_contract.cljc" "hive_spi/schema/typed_ann.clj"})

(def require-spec '[hive-spi.schema.typed-contract :as tc])

(defn- top-level [zloc]
  (->> zloc (iterate z/right) (take-while some?)))

(defn- head+subject [loc]
  (when (= :list (z/tag loc))
    (let [h (z/down loc)
          s (some-> h z/right)]
      [(when (= :token (some-> h z/tag)) (z/sexpr h))
       (some-> s z/sexpr)
       s])))

(defn- private-meta? [sloc]
  (= :meta (z/tag sloc)))

(defn public-defns
  "Names of top-level public `defn` forms in the file."
  [zloc]
  (into #{}
        (keep (fn [loc]
                (let [[h s sloc] (head+subject loc)]
                  (when (and (= 'defn h) (symbol? s) (not (private-meta? sloc))
                             (not (:private (meta s))))
                    s))))
        (top-level zloc)))

(defn- annotated [zloc]
  (into #{}
        (keep (fn [loc]
                (let [[h s] (head+subject loc)]
                  (when (= 'tc/ann-op h) s))))
        (top-level zloc)))

(defn- add-require
  "Append `require-spec` to the ns form's direct (:require ..). Nil when the
   ns has no direct :require list (e.g. it sits under a reader conditional)."
  [zloc]
  (let [ns-loc (z/find-value zloc z/next 'ns)
        req    (some-> ns-loc
                       (z/find (fn [l] (and (= :list (z/tag l))
                                            (= :require (some-> l z/down z/sexpr))))))]
    (when req
      (if (some #(= 'hive-spi.schema.typed-contract (first %))
                (filter vector? (rest (z/sexpr req))))
        zloc
        (let [first-spec (z/right (z/down req))
              indent     (dec (second (z/position first-spec)))]
          (-> (z/rightmost (z/down req))
              (z/insert-right* (n/coerce require-spec))
              (z/insert-right* (n/spaces indent))
              (z/insert-right* (n/newlines 1))
              z/up
              z/up
              z/leftmost))))))

(defn rewrite-source
  "[new-source annotated-names] for `src`, or nil when nothing changes or the
   ns :require cannot take the spec."
  [src]
  (let [zloc   (z/of-string src {:track-position? true})
        public (public-defns zloc)
        done   (annotated zloc)
        todo   (atom [])]
    (loop [loc zloc]
      (let [[h s] (head+subject loc)
            hit?  (and (= 'm/=> h) (symbol? s) (contains? public s) (not (contains? done s)))
            loc'  (if hit?
                    (do (swap! todo conj s)
                        (-> loc
                            (z/insert-right* (n/coerce (list 'tc/ann-op s)))
                            (z/insert-right* (n/newlines 1))
                            z/right))
                    loc)]
        (if-let [nxt (z/right loc')]
          (recur nxt)
          (when (seq @todo)
            (when-let [with-req (add-require (z/leftmost loc'))]
              [(z/root-string with-req) @todo])))))))

(defn run [{:keys [dir dry-run?]}]
  (let [files (->> (fs/glob dir "**.{clj,cljc}")
                   (map str)
                   (remove (fn [f] (some #(str/ends-with? f %) skip)))
                   sort)
        rows  (for [f files
                    :let [src (slurp f)
                          [out names] (rewrite-source src)]
                    :when out]
                (do (when-not dry-run? (spit f out))
                    {:file f :annotated (count names)}))
        rows  (vec rows)]
    (doseq [{:keys [file annotated]} rows]
      (println (format "%4d %s" annotated file)))
    (println "total" (reduce + 0 (map :annotated rows)) "in" (count rows) "files")))

(when (= *file* (System/getProperty "babashka.file"))
  (let [args     *command-line-args*
        dry-run? (some #{"--dry-run"} args)
        dir      (or (first (remove #{"--dry-run"} args)) "src")]
    (run {:dir dir :dry-run? dry-run?})))

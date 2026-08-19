(ns hive-spi.crypto.hash-test
  "The digest port is the seam every content-hash in the ecosystem crosses, so
   a hasher that diverges from the published SHA-256 vectors, or a slot that
   fails to route to an injected implementation, silently rewrites identity for
   every store and every candidate world."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [hive-spi.crypto.hash :as hash]))

;; =============================================================================
;; Fixtures
;; =============================================================================

(defn- restore-hasher
  "Snapshot the installed hasher, run F, restore what was installed."
  [f]
  (let [installed (when (hash/hasher-set?) (hash/get-hasher))]
    (try
      (f)
      (finally
        (hash/clear-hasher!)
        (when installed (hash/set-hasher! installed))))))

(use-fixtures :each restore-hasher)

;; =============================================================================
;; Contract: the published SHA-256 vectors
;; =============================================================================

(def ^:private nist-vectors
  "FIPS 180-4 / NIST CAVS known-answer pairs of {input -> lowercase hex}."
  {""    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
   "abc" "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
   "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
   "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"})

(deftest host-default-matches-the-published-vectors-test
  (doseq [[input expected] nist-vectors]
    (is (= expected (hash/sha256 input))
        (str "SHA-256 of " (pr-str (subs input 0 (min 12 (count input)))))))) 

(deftest utf8-is-the-encoding-crossing-the-boundary-test
  (testing "multi-byte text hashes as its UTF-8 bytes (c3a9), not as code points"
    (is (= "4a99557e4033c3539de2eb65472017cad5f9557f7a0625a09f1c3f6e2ba69c4c"
           (hash/sha256 "é")))))

(deftest algorithms-outside-the-contract-fail-loud-test
  (let [hasher (hash/get-hasher)]
    (is (some? hasher) "a host default is available on this runtime")
    (is (contains? (hash/hasher-algorithms hasher) :sha256))
    (is (= :hash/unsupported-algorithm
           (try (hash/digest-hex hasher :sha3-512 "x")
                (catch clojure.lang.ExceptionInfo e (:error (ex-data e))))))))

;; =============================================================================
;; Contract: the slot routes to an injected implementation
;; =============================================================================

(defrecord StubHasher [calls]
  hash/IHasher
  (digest-hex [_ algorithm text]
    (swap! calls conj [algorithm text])
    "stub-digest")
  (hasher-algorithms [_] #{:sha256}))

(defn- stub-hasher [] (->StubHasher (atom [])))

(deftest an-installed-hasher-supersedes-the-host-default-test
  (let [stub (stub-hasher)]
    (hash/set-hasher! stub)
    (is (true? (hash/hasher-set?)))
    (is (= "stub-digest" (hash/sha256 "abc")))
    (is (= [[:sha256 "abc"]] @(:calls stub))
        "the port passes the algorithm and the text through unchanged")))

(deftest clearing-falls-back-to-the-host-default-test
  (hash/set-hasher! (stub-hasher))
  (hash/clear-hasher!)
  (is (false? (hash/hasher-set?)))
  (is (= (get nist-vectors "abc") (hash/sha256 "abc"))))

(deftest an-implementation-that-is-not-a-hasher-is-rejected-test
  (is (thrown? AssertionError (hash/set-hasher! {:not :a-hasher})))
  (is (false? (hash/hasher-set?)) "a rejected install leaves the slot empty"))

;; =============================================================================
;; Properties
;; =============================================================================

(defspec digests-are-64-lowercase-hex-characters 200
  (prop/for-all [s gen/string]
    (re-matches #"[0-9a-f]{64}" (hash/sha256 s))))

(defspec digests-are-deterministic 200
  (prop/for-all [s gen/string]
    (= (hash/sha256 s) (hash/sha256 s))))

(defspec distinct-inputs-give-distinct-digests 200
  (prop/for-all [a gen/string
                 b gen/string]
    (or (= a b) (not= (hash/sha256 a) (hash/sha256 b)))))

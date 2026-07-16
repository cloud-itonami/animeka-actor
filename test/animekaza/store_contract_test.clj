(ns animekaza.store-contract-test
  "MemStore ≡ DatomicStore — the same clip + ledger facts committed to both
  backends must read back identically (the Store is a swap, not a rewrite)."
  (:require [clojure.test :refer [deftest is testing]]
            [animekaza.store :as store]))

(deftest mem-and-datomic-stores-agree
  (doseq [s [(store/seed-db) (store/datomic-store)]]
    (testing (str (type s))
      (store/commit-clip! s "c1" {:clip-id "c1" :title "t1" :shots 6})
      (store/commit-clip! s "c2" {:clip-id "c2" :title "t2" :shots 4})
      (store/append-ledger! s {:t :committed :clip "c1" :seq-hint 0})
      (store/append-ledger! s {:t :governor-hold :clip "c2" :seq-hint 1})
      (is (= "t1" (:title (store/clip s "c1"))))
      (is (nil? (store/clip s "nope")))
      (is (= ["c1" "c2"] (mapv :clip-id (store/all-clips s))))
      (is (= [:committed :governor-hold] (mapv :t (store/ledger s)))))))

(deftest ledger-is-append-only-ordered
  (doseq [s [(store/seed-db) (store/datomic-store)]]
    (dotimes [i 5] (store/append-ledger! s {:t :fact :i i}))
    (is (= [0 1 2 3 4] (mapv :i (store/ledger s))))))

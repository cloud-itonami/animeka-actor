(ns animekaza.run-tests
  "Test runner for com-etzhayyim-animeka (new actors ship run_tests.clj, not
  .sh — per etzhayyim/root CLAUDE.md). Canonical path: `clojure -M:dev:test`
  (cognitect test-runner). This runner: `clojure -M -m animekaza.run-tests`."
  (:require [clojure.test :refer [run-tests]]
            [animekaza.governor-contract-test]
            [animekaza.store-contract-test]
            [animekaza.operation-test]
            [animekaza.clip-designs-test])
  (:gen-class))

(defn -main [& _args]
  (let [res (run-tests
             'animekaza.governor-contract-test
             'animekaza.store-contract-test
             'animekaza.operation-test
             'animekaza.clip-designs-test)]
    (when (pos? (+ (:fail res 0) (:error res 0)))
      (System/exit 1))))

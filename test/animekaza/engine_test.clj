(ns animekaza.engine-test
  "Pure-function coverage for the ltx-2.3 engine unblock (docs/adr/0001
  addendum 2026-07-17). submit-clip! itself needs a live ComfyUI host so it
  is exercised as a manual live smoke test, not here — this covers what's
  deterministic: graph shape and the fail-closed opt-in gate."
  (:require [clojure.test :refer [deftest is testing]]
            [animekaza.engine :as engine]))

(deftest ltx-graph-shape
  (let [g (engine/ltx-graph {:prompt "anime style, a girl under cherry blossoms"})]
    (testing "checkpoint/text-encoder match cloud-murakumo's comfy-video-models entry"
      (is (= engine/ckpt (get-in g ["100" :inputs :ckpt_name])))
      (is (= engine/text-encoder (get-in g ["111" :inputs :text_encoder]))))
    (testing "prompt reaches the CLIP text encode node via the primitive string node"
      (is (= "anime style, a girl under cherry blossoms" (get-in g ["109" :inputs :value]))))
    (testing "single-stage output: SaveVideo is present, no second-stage upsampler node"
      (is (= "SaveVideo" (get-in g ["104" :class_type])))
      (is (nil? (get g "200")) "no upsampler stage — ADR-2607171100 single-stage is the verified shape"))))

(deftest clip-prompt-joins-shot-prompts
  (let [plan {:scenes [{:seq 0 :shots [{:prompt "anime style, shot one"}
                                       {:prompt "anime style, shot two"}]}
                       {:seq 1 :shots [{:prompt "anime style, shot three"}]}]}]
    (is (= "anime style, shot one. anime style, shot two. anime style, shot three"
           (#'animekaza.engine/clip-prompt plan)))))

(deftest engine-is-opt-in-fail-closed
  (testing "ANIMEKA_COMFY_URL unset -> submit-clip! throws, never silently no-ops or fabricates"
    (when-not (System/getenv "ANIMEKA_COMFY_URL")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ANIMEKA_COMFY_URL"
                            (engine/submit-clip! {:scenes []} "/tmp/should-not-be-written.mp4"))))))

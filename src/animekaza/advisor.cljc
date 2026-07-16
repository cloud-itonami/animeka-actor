(ns animekaza.advisor
  "AnimeLLM — the *contained intelligence node* for animeka (アニメ家).
  It takes a clip request (theme + duration target) and returns a
  PROPOSAL: a full production plan for a vertical (720x1280) AI anime clip —
  title / logline / scenes / shot list (per-shot anime-style prompt, duration,
  subtitle). It NEVER returns a committed record, NEVER fires a generation job
  and NEVER decides publication — the AnimekaGovernor censors every proposal
  downstream, and only :commit writes the SSoT (+ announces when the phase
  allows). Mirrors the `Advisor` protocol shape of minidrama.advisor /
  tashikame.factllm.

  Sealed by construction: the default `mock-advisor` is deterministic. The
  real advisor wires `langchain.model` against the Murakumo fleet
  (DEFAULT-PREFERRED per Rider v3.3 §2(i)) — still proposal-only, still
  governor-censored.

  Proposal shape:
    {:summary    str
     :rationale  str
     :clip       {:title str :logline str
                  :scenes [{:seq int :setting str
                            :shots [{:seq int :prompt str :duration sec
                                     :subtitle str}]}]}
     :effect     :production   ; animeka only ever plans production
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.model :as model]))

(defprotocol Advisor
  (-plan [advisor store request] "store + request → proposal map"))

(defn shot-total
  "Total duration (seconds) of every shot in a clip plan."
  [clip]
  (reduce + 0.0 (for [sc (:scenes clip) sh (:shots sc)]
                  (double (or (:duration sh) 0)))))

(defn shot-count [clip]
  (count (for [sc (:scenes clip) sh (:shots sc)] sh)))

(defn- plan* [{:keys [theme duration-target]}]
  (if (or (nil? theme) (str/blank? theme))
    {:summary "empty theme" :rationale "no theme text" :clip nil
     :effect :noop :confidence 0.0}
    (let [target (min 60 (or duration-target 45))
          ;; deterministic 3-scene / 2-shots-per-scene skeleton, evenly timed
          per-shot (double (/ target 6))
          scene (fn [i setting lines]
                  {:seq i :setting setting
                   :shots (vec (map-indexed
                                (fn [j line]
                                  {:seq j
                                   :prompt (str "anime style, vertical 9:16, "
                                                setting " — " line)
                                   :duration per-shot
                                   :subtitle line})
                                lines))})]
      {:summary (str "anime clip plan: " theme " (" target "s, 6 shots)")
       :rationale "mock heuristic: 3 scenes × 2 shots, even timing"
       :clip {:title (str theme "（アニメクリップ）")
              :logline (str theme " を描く縦型 AI アニメクリップ")
              :scenes [(scene 0 "導入" [(str theme "、はじまる") "日常の一コマ"])
                       (scene 1 "転換" ["予想外の出来事" "決断の瞬間"])
                       (scene 2 "結末" ["静かな余韻" "次回へつづく"])]}
       :effect :production :confidence 0.75})))

(defn mock-advisor
  "The deterministic advisor (default everywhere — no non-deterministic LLM
  free-write). Real-LLM wiring is a swap via `langchain.model` on Murakumo."
  []
  (reify Advisor (-plan [_ _store req] (plan* req))))

(defn trace
  "Decision-grounded audit record for the ledger."
  [request proposal]
  {:t          :animellm-proposal
   :op         (:op request)
   :clip-id    (:clip-id request)
   :summary    (:summary proposal)
   :shots      (some-> (:clip proposal) shot-count)
   :duration   (some-> (:clip proposal) shot-total)
   :confidence (:confidence proposal)})

;; ───────────────────── real-LLM advisor (Murakumo fleet) ─────────────────────
;; Sealed just like the mock: it returns a PROPOSAL only — the AnimekaGovernor
;; still censors every plan. The model is an INJECTED langchain.model/ChatModel.

(def allowed-infer-hosts
  "Murakumo-fleet inference hosts only (Rider §2(i))."
  #{"127.0.0.1:11434" "localhost:11434"
    "127.0.0.1:4000"  "localhost:4000"
    "192.168.1.70:4000"})

(defn- host-port [url]
  (when (string? url) (second (re-find #"(?i)^[a-z]+://([^/]+)" url))))

(defn assert-murakumo!
  "Throw if `ollama-url` is not a Murakumo-fleet inference host."
  [ollama-url]
  (let [hp (host-port ollama-url)]
    (when-not (contains? allowed-infer-hosts hp)
      (throw (ex-info (str "inference host " hp " is not Murakumo-fleet (Rider §2(i))")
                      {:host hp})))))

(def animeka-system-prompt
  "You are animeka (アニメ家), a vertical short-anime showrunner.
Plan a 30-60 second vertical (720x1280) AI anime clip for the user's theme.
Every shot :prompt MUST begin with \"anime style, \".
Respond with ONLY a single-line EDN map, no prose, no code fences:
  {:title \"...\" :logline \"...\"
   :scenes [{:seq 0 :setting \"...\"
             :shots [{:seq 0 :prompt \"anime style, ...\" :duration 8 :subtitle \"...\"}]}]}
Hard limits: total duration <= 120 seconds, <= 24 shots, each shot <= 10
seconds. Fictional characters only — no real-person likenesses, no brands.")

(defn- build-prompt [{:keys [theme duration-target]}]
  (str "Theme: " theme "\n"
       "Duration target (seconds): " (or duration-target 45) "\n\n"
       "Return ONLY the EDN map now."))

(defn parse-plan-edn
  "Defensively parse the LLM's EDN plan. Any parse failure → nil clip
  (the AnimekaGovernor then holds it; the system never breaks on malformed
  model output)."
  [content]
  (let [s (-> (str content)
              (str/replace #"(?s)```[a-zA-Z]*" "")
              (str/replace "```" ""))]
    (try
      (when-let [m (some-> (re-find #"(?s)\{.*\}" s) edn/read-string)]
        (when (and (string? (:title m)) (sequential? (:scenes m)))
          m))
      (catch #?(:clj Throwable :cljs :default) _ nil))))

(defn llm-advisor
  "Advisor backed by a langchain.model/ChatModel. Sealed: returns a PROPOSAL
  only; the AnimekaGovernor still censors. gen-opts → model/-generate opts."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-plan [_ _store request]
       (let [content (:content
                      (model/-generate chat-model
                        [{:role :system :content animeka-system-prompt}
                         {:role :user   :content (build-prompt request)}]
                        gen-opts)
                      {})
             clip (parse-plan-edn content)]
         (if clip
           {:summary (str "animellm plan: " (:title clip))
            :rationale "LLM plan (Murakumo); governor-censored downstream"
            :clip clip :effect :production :confidence 0.6}
           {:summary "animellm output unparseable"
            :rationale "malformed plan → no clip (governor holds)"
            :clip nil :effect :noop :confidence 0.1}))))))

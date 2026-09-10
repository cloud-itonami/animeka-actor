(ns animekaza.produce
  "Plan-production entrypoint — run ONE clip theme through the actor
  (AnimeLLM proposal → AnimekaGovernor → commit | hold) and, on :commit, emit
  the committed plan EDN to `.animeka/clips/<clip-id>.edn` — the work order
  the ai-gftd-animeka generation engine will consume once its plan-EDN→mp4
  path exists (currently HELD, docs/adr/0001-architecture.md — the work
  order is real, the engine leg is not fabricated in its absence).

  Governor doctrine is unchanged: a HOLD prints the violation basis and exits
  1 — no plan file is written for a rejected clip.

  Usage: clojure -M:dev -m animekaza.produce <theme> [clip-id] [duration]
         clojure -M:dev -m animekaza.produce --from clips/<slug>.edn
           (hand-authored design — STILL censored by the AnimekaGovernor via a
            design-advisor: 手書き絵コンテも同じ検閲を通る。clips/*.edn は
            Datomic/Datascript tx-data として保存されている (edn-datomize.bb
            wrap-map, ns=clip) — read-design が :clip/ 名前空間を剥がし
            blob 化された :clip/scenes を元の入れ子データへ戻す)
  Env:   ANIMEKA_USE_LLM=1     use the Murakumo LLM advisor (deploy's wiring)
         ANIMEKA_OLLAMA_URL / ANIMEKA_OLLAMA_MODEL   as in animekaza.deploy"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [langgraph.graph :as g]
            [animekaza.advisor :as advisor]
            [animekaza.deploy :as deploy]
            [animekaza.operation :as op]
            [animekaza.publisher :as publisher]
            [animekaza.store :as store])
  (:gen-class))

(defn- unblob [v]
  (if (string? v)
    (try (let [parsed (edn/read-string v)] (if (coll? parsed) parsed v))
         (catch Exception _ v))
    v))

(defn read-design
  "clips/<slug>.edn is a Datomic/Datascript tx-data vector
  ([{:db/id -1 :clip/... ...}], edn-datomize.bb wrap-map ns=clip) —
  reconstitute the original bare design map (strip :db/id + :clip/
  namespace, unblob nested collections like :scenes)."
  [path]
  (let [tx (edn/read-string (slurp path))]
    (into {} (map (fn [[k v]] [(keyword (name k)) (unblob v)]))
          (dissoc (first tx) :db/id))))

(defn design-advisor
  "Advisor that proposes a fixed hand-authored design (clips/*.edn) —
  the AnimekaGovernor censors it exactly like an AnimeLLM proposal."
  [design]
  (reify advisor/Advisor
    (-plan [_ _ _]
      {:summary (str "hand-authored design: " (:title design))
       :rationale "clips/ catalog design (operator-authored)"
       :clip (select-keys design [:title :logline :scenes])
       :effect :production
       :confidence 0.9})))

(defn produce-plan!
  "Run one theme through the actor. Returns
  {:disposition :commit|:hold :plan <record|nil> :basis [...]}."
  [{:keys [theme clip-id duration advisor phase]
    :or {phase 1}}]
  (let [s (store/seed-db)
        actor (op/build s (cond-> {:publisher (publisher/mock-publisher)}
                            advisor (assoc :advisor advisor)))
        r (g/run* actor {:request {:op :clip/plan :clip-id clip-id
                                   :theme theme :duration-target duration}
                         :context {:actor-id "animeka" :phase phase}}
                  {:thread-id clip-id})
        disposition (get-in r [:state :disposition])]
    {:disposition disposition
     :plan (store/clip s clip-id)
     :basis (when (= :hold disposition)
              (-> (store/ledger s) last :basis))}))

(defn -main [& [theme clip-id duration]]
  (when-not (and theme (seq theme))
    (binding [*out* *err*]
      (println "usage: clojure -M:dev -m animekaza.produce <theme>|--from <edn> [clip-id] [duration]"))
    (System/exit 1))
  (let [design (when (= "--from" theme)
                 (read-design clip-id))
        clip-id (if design
                  (:clip-id design)
                  (or clip-id (str "clip-" (System/currentTimeMillis))))
        adv (cond
              design (design-advisor design)
              (= "1" (System/getenv "ANIMEKA_USE_LLM"))
              (advisor/llm-advisor (deploy/ollama-chat-model) {:max-tokens 1024}))
        {:keys [disposition plan basis]}
        (produce-plan! {:theme (if design (:title design) theme)
                        :clip-id clip-id
                        :duration (some-> duration parse-long)
                        :advisor adv})]
    (if (= :commit disposition)
      (let [f (io/file ".animeka/clips" (str clip-id ".edn"))]
        (io/make-parents f)
        (spit f (pr-str plan))
        (println "disposition: commit")
        (println "plan       :" (str f))
        (println "title      :" (:title plan))
        (println "shots      :" (:shots plan) "duration:" (:duration plan) "s"))
      (do (println "disposition: hold")
          (println "basis      :" (pr-str basis))
          (System/exit 1)))))

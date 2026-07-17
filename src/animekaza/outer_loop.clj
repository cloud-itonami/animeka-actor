(ns animekaza.outer-loop
  "Durable outer loop (ADR-2607162200 Layer B, Phase C rollout): consume ONE
  production tick per run — 1 run = 1 operation, no unbounded inner loops.
  Same skeleton as minidrama.outer-loop.

  Tick source (Layer A): the aozora PDS cron emits `creatortick/<slug>/<date>/
  <slot>` datoms; this loop reads them via app.aozora.creator.getTicks. The
  actor NEVER writes the tick db — consumption is recorded as records in the
  actor's OWN repo (collection com.etzhayyim.apps.animeka.tick, rkey
  <date>-<slot>), which doubles as the lease: a parallel loop instance sees
  the record and skips, so consuming a tick is idempotent.

  ENGINE HOLD, PARTIALLY UNBLOCKED (docs/adr/0001-architecture.md addendum
  2026-07-17): ai-gftd-animeka still has NO plan-EDN→mp4 CLI path (that HOLD
  condition is unchanged — nothing here touches that repo). What changed:
  animekaza.engine can dispatch a clip directly to the murakumo fleet's own
  :video model (ltx-2.3 on gad, ADR-2607171330) via its ComfyUI HTTP surface,
  fed the clip's own \"anime style, ...\" shot prompts — verified 2026-07-17
  (90-docs/gen-quality ledger, composite 0.624). This is opt-in and
  fail-closed on ANIMEKA_COMFY_URL: unset (the default — the animeka registry
  cadence is also :active? false) → behavior UNCHANGED, a due tick is
  consumed as status \"held\"/engine-hold, an honest escalation record, never
  a fabricated clip. Set → run-once! drives produce → engine → announce
  (minidrama's run-chain! shape) behind the same :auto-publish grant +
  AnimekaGovernor escalation boundary; any engine error still HOLDs the tick
  (no silent fallback either direction).

  Usage: clojure -M:dev -m animekaza.outer-loop            run once
         clojure -M:dev -m animekaza.outer-loop status     ticks + consumption
  Env:   ANIMEKA_PHASE       0 draft / 1 unlisted / 2 public (default 2 —
                             ADR-2607162200 scheduled operation)
         ANIMEKA_COMFY_URL   gad ComfyUI base (e.g. http://<tailnet-ip>:8188)
                             — unset = engine-hold unchanged (opt-in)"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [animekaza.announce :as announce]
            [animekaza.aozora :as aozora]
            [animekaza.cacao :as cacao]
            [animekaza.engine :as engine]
            [animekaza.phase :as phase]
            [animekaza.produce :as produce]
            [animekaza.publisher :as publisher])
  (:gen-class))

(def tick-collection "com.etzhayyim.apps.animeka.tick")
(def actor-slug "animeka")

(defn- getx [url]
  (let [{:keys [status body]} (aozora/jvm-http-fn {:url url :method :get})]
    (when (= 200 status) (json/read-str body :key-fn keyword))))

(defn ticks
  "Ticks the PDS cadence cron has emitted for this actor (optionally one date).
  Registry cadence for animeka is currently :active? false, so an empty list
  is the normal state (ADR-2607162200 Phase C — flip is one registry line)."
  ([pds] (ticks pds nil))
  ([pds date]
   (:ticks (getx (str pds "/xrpc/app.aozora.creator.getTicks?actor=" actor-slug
                      (when date (str "&date=" date)))))))

(defn consumption
  "Tick-consumption records from the actor's OWN repo → {tick-id record-value}."
  [pds did]
  (let [rs (:records (getx (str pds "/xrpc/com.atproto.repo.listRecords?repo=" did
                                "&collection=" tick-collection "&limit=100")))]
    (into {} (keep (fn [{:keys [value]}]
                     (when-let [tid (:tick-id value)] [tid value]))
                   rs))))

(defn- record-consumption! [pub {:keys [tick clip-id status extra]}]
  (publisher/publish!
   pub (merge {:collection tick-collection
               :rkey (str (:date tick) "-" (:slot tick))
               :$type tick-collection
               :tick-id (:id tick)
               :clip-id clip-id
               :status status}
              extra)))

(defn catalog-designs
  "clips/*.edn catalog slugs (sorted) — the scheduled loop will drain these
  once the engine leg exists."
  []
  (->> (.listFiles (io/file "clips"))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".edn"))
       (map #(str/replace (.getName ^java.io.File %) #"\.edn$" ""))
       sort vec))

(defn next-design
  "First catalog design no consumption record has produced yet."
  [consumed]
  (let [used (set (keep :clip-id (vals consumed)))]
    (first (remove used (catalog-designs)))))

(defn- engine-configured? [] (boolean (System/getenv "ANIMEKA_COMFY_URL")))

(defn- produce-engine-announce!
  "design slug → {:disposition :commit|:hold ...}. Governor runs first
  (design-advisor still censors hand-authored designs); only a :commit plan
  reaches the engine. Any engine/announce exception is caught by the caller
  and turned into a held/engine-error record — never a silently-lost tick."
  [design-slug identity announce?]
  (let [design (produce/read-design (str "clips/" design-slug ".edn"))
        {:keys [disposition plan basis]}
        (produce/produce-plan! {:theme (:title design) :clip-id design-slug
                                :advisor (produce/design-advisor design)
                                :phase (or (some-> (System/getenv "ANIMEKA_PHASE") parse-long) 2)})]
    (if-not (= :commit disposition)
      {:disposition :hold :basis basis}
      (let [out-path (str ".animeka/clips/" design-slug "/" design-slug ".mp4")
            _ (engine/submit-clip! plan out-path)]
        (if announce?
          (let [pds aozora/default-pds
                r (announce/announce! {:pds pds :identity identity :path out-path
                                       :clip-id design-slug :title (:title design)
                                       :text (str "【アニメ家】『" (:title design) "』")})]
            {:disposition :commit :announced true :post (:post r)})
          {:disposition :commit :announced false :out-path out-path})))))

(defn run-once!
  "Consume at most one unconsumed tick for today (UTC). Returns a result map.
  ANIMEKA_COMFY_URL unset (default): engine is HELD, a due tick is consumed
  as \"held\"/engine-hold (escalation), never produced or announced. Set:
  drives produce → engine → announce for the next undone catalog design."
  []
  (let [pds aozora/default-pds
        id (cacao/load-or-create-identity! ".animeka/identity.edn")
        pub (aozora/aozora-publisher {:pds pds :identity id
                                      :json-write json/write-str
                                      :json-read json/read-str})
        today (subs (str (java.time.Instant/now)) 0 10)
        due (vec (ticks pds today))
        consumed (consumption pds (:did id))
        open (first (remove #(consumed (:id %)) due))
        ph (or (some-> (System/getenv "ANIMEKA_PHASE") parse-long) 2)
        announce? (phase/publish-allowed? ph #{:auto-publish})]
    (cond
      (nil? open)
      {:status :idle :due (count due) :consumed (count consumed)}

      (not (engine-configured?))
      (let [design (next-design consumed)]
        (record-consumption! pub {:tick open :clip-id design :status "held"
                                  :extra {:reason "engine-hold"
                                          :adr "docs/adr/0001-architecture.md"
                                          :phase ph
                                          :would-announce (boolean announce?)}})
        {:status :held :tick (:id open) :clip design :reason :engine-hold})

      :else
      (let [design (next-design consumed)]
        (if-not design
          (do (record-consumption! pub {:tick open :status "held"
                                        :extra {:reason "catalog-exhausted"}})
              {:status :held :tick (:id open) :reason :catalog-exhausted})
          (do (record-consumption! pub {:tick open :clip-id design :status "started"})
              (try
                (let [{:keys [disposition] :as r} (produce-engine-announce! design id announce?)]
                  (if (= :commit disposition)
                    (do (record-consumption! pub {:tick open :clip-id design :status "done"
                                                  :extra {:phase ph :grant "auto-publish"
                                                          :announced (boolean (:announced r))}})
                        {:status :done :tick (:id open) :clip design :announced (:announced r)})
                    (do (record-consumption! pub {:tick open :clip-id design :status "held"
                                                  :extra {:reason "governor-hold" :basis (:basis r)}})
                        {:status :held :tick (:id open) :clip design :reason :governor-hold})))
                (catch Exception e
                  (record-consumption! pub {:tick open :clip-id design :status "held"
                                            :extra {:reason "engine-error" :error (ex-message e)}})
                  {:status :held :tick (:id open) :clip design :reason :engine-error
                   :error (ex-message e)}))))))))

(defn -main [& [cmd]]
  (if (= cmd "status")
    (let [pds aozora/default-pds
          id (cacao/load-or-create-identity! ".animeka/identity.edn")]
      (println "ticks      :" (pr-str (ticks pds)))
      (println "consumption:" (pr-str (consumption pds (:did id)))))
    (println "run-once!  :" (pr-str (run-once!))))
  (System/exit 0))

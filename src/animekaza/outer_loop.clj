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

  ENGINE HOLD (docs/adr/0001-architecture.md): ai-gftd-animeka currently has
  NO plan-EDN→mp4 CLI path (its only -main is animeka.server; generate-keyframe
  renders single stills; assemble/publish nodes are metadata-only). Until that
  engine leg exists this loop therefore consumes a due tick as status \"held\"
  with reason \"engine-hold\" and its would-be catalog design — an honest
  escalation record, never a fabricated clip. The animeka registry cadence is
  :active? false, so in normal operation getTicks is empty and the loop is
  :idle. When the engine lands, run-once! grows the produce → engine →
  announce chain (minidrama's run-chain! shape) behind the same
  :auto-publish grant + AnimekaGovernor escalation boundary.

  Usage: clojure -M:dev -m animekaza.outer-loop            run once
         clojure -M:dev -m animekaza.outer-loop status     ticks + consumption
  Env:   ANIMEKA_PHASE   0 draft / 1 unlisted / 2 public (default 2 —
                         ADR-2607162200 scheduled operation)"
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [animekaza.aozora :as aozora]
            [animekaza.cacao :as cacao]
            [animekaza.phase :as phase]
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

(defn run-once!
  "Consume at most one unconsumed tick for today (UTC). Returns a result map.
  While the engine is HELD, a due tick is consumed as \"held\"/engine-hold
  (escalation), never produced or announced."
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

      :else
      (let [design (next-design consumed)]
        (record-consumption! pub {:tick open :clip-id design :status "held"
                                  :extra {:reason "engine-hold"
                                          :adr "docs/adr/0001-architecture.md"
                                          :phase ph
                                          :would-announce (boolean announce?)}})
        {:status :held :tick (:id open) :clip design :reason :engine-hold}))))

(defn -main [& [cmd]]
  (if (= cmd "status")
    (let [pds aozora/default-pds
          id (cacao/load-or-create-identity! ".animeka/identity.edn")]
      (println "ticks      :" (pr-str (ticks pds)))
      (println "consumption:" (pr-str (consumption pds (:did id)))))
    (println "run-once!  :" (pr-str (run-once!))))
  (System/exit 0))

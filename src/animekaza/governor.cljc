(ns animekaza.governor
  "AnimekaGovernor — the independent censor that earns an AnimeLLM proposal
  the right to be committed (and, phase permitting, announced). SEPARATE
  system from the AnimeLLM (defense in depth: never trust the generator to
  have been the only gate). Mirrors minidrama.governor's shape (HARD → HOLD,
  no override; SOFT → recorded warning, still commits).

  Gates (ADR-2607071300 shape, adopted for anime clips):
  HARD (never commit):
    :no-actuation         proposal :effect ≠ :production (animeka only plans)
    :over-duration        total shot duration > 120 s
    :too-many-shots       > 24 shots
    :overlong-shot        any shot > 10 s
    :content-veto         Rider §2 catastrophe-veto scan hits on plan text
    :likeness             real-person likeness / brand use (R0 marker heuristic)
    :unprovenanced-asset  a shot pulls an external asset without provenance
    :budget-exceeded      shots × cost-per-shot > clip budget (context)
    :rate-limited         published-today ≥ daily cap (context)
  SOFT (commit with tag):
    :low-confidence       overall confidence < floor"
  (:require [clojure.string :as str]
            [animekaza.advisor :as advisor]))

(def confidence-floor 0.4)
(def max-total-duration 120.0)
(def max-shots 24)
(def max-shot-duration 10.0)
(def default-daily-cap 3)
(def default-budget {:cost-per-shot 1 :episode-budget 24})

(def ^:private catastrophe-denylist
  "Rider §2 catastrophe-veto surface — illustrative denylist for R0. Production
  wires `etzhayyim_organism.sensors.charter_rider.scan` (the canonical §2
  scanner). The markers below drive the contract test."
  ["<CAT>" "<CSAM>" "<FORCE>" "<SURVEIL>"])

(defn- clip-blob [clip]
  (->> (concat [(:title clip) (:logline clip)]
               (for [sc (:scenes clip)] (:setting sc))
               (for [sc (:scenes clip) sh (:shots sc)]
                 (str (:prompt sh) " " (:subtitle sh))))
       (filter string?)
       (str/join " ")))

(defn- catastrophe? [clip]
  (let [blob (clip-blob clip)]
    (some #(str/includes? blob %) catastrophe-denylist)))

(defn- likeness? [clip]
  ;; R0 heuristic: the marker drives the contract test; production uses a
  ;; richer likeness/brand detector. Fictional characters only.
  (str/includes? (clip-blob clip) "<LIKENESS>"))

(defn- unprovenanced-asset? [clip]
  (some (fn [sh] (and (:asset-url sh) (not (:asset-provenance sh))))
        (for [sc (:scenes clip) sh (:shots sc)] sh)))

(defn check
  "Censors an AnimeLLM proposal. Returns {:ok? :violations [hard]
  :warnings [soft] :confidence c}. :ok? is true iff there are no HARD
  violations."
  [_request context proposal]
  (let [effect  (:effect proposal)
        clip    (:clip proposal)
        conf    (:confidence proposal 0.0)
        {:keys [cost-per-shot episode-budget]} (merge default-budget (:budget context))
        daily-cap (or (:daily-cap context) default-daily-cap)
        published-today (or (:published-today context) 0)
        total (when clip (advisor/shot-total clip))
        shots (when clip (advisor/shot-count clip))
        hard (cond-> []
               (not= :production effect)
               (conj {:rule :no-actuation
                      :detail "animeka only plans production; :effect must be :production"})
               (and total (> total max-total-duration))
               (conj {:rule :over-duration
                      :detail (str "total " total "s > " max-total-duration "s")})
               (and shots (> shots max-shots))
               (conj {:rule :too-many-shots
                      :detail (str shots " shots > " max-shots)})
               (and clip
                    (some (fn [sh] (> (double (or (:duration sh) 0)) max-shot-duration))
                          (for [sc (:scenes clip) sh (:shots sc)] sh)))
               (conj {:rule :overlong-shot
                      :detail (str "a shot exceeds " max-shot-duration "s")})
               (and clip (catastrophe? clip))
               (conj {:rule :content-veto
                      :detail "Rider §2 catastrophe-veto scan hit — never committed"})
               (and clip (likeness? clip))
               (conj {:rule :likeness
                      :detail "real-person likeness / brand use — fictional characters only"})
               (and clip (unprovenanced-asset? clip))
               (conj {:rule :unprovenanced-asset
                      :detail "external asset without provenance"})
               (and shots (> (* shots cost-per-shot) episode-budget))
               (conj {:rule :budget-exceeded
                      :detail (str shots " shots × " cost-per-shot " > budget " episode-budget
                                   " — propose a smaller clip")})
               (>= published-today daily-cap)
               (conj {:rule :rate-limited
                      :detail (str "published-today " published-today " ≥ daily cap " daily-cap)}))
        soft (cond-> []
               (< conf confidence-floor)
               (conj {:rule :low-confidence
                      :detail (str "confidence " conf " < floor " confidence-floor)}))]
    {:ok? (empty? hard) :violations hard :warnings soft :confidence conf}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t :governor-hold :op (:op request) :clip (:clip-id request)
   :actor (:actor-id context) :disposition :hold
   :basis (mapv :rule (:violations verdict)) :violations (:violations verdict)})

(defn verdict->disposition
  "Map an AnimekaGovernor verdict to a base disposition. HARD → :hold, else
  :commit."
  [verdict]
  (if (:ok? verdict) :commit :hold))

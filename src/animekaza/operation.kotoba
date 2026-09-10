(ns animekaza.operation
  "OperationActor — one clip plan = one supervised actor run, expressed as
  a langgraph-clj StateGraph. The AnimeLLM (contained intelligence node) is
  sealed into :advise; its proposal is ALWAYS routed through the
  AnimekaGovernor (:govern) before anything commits to the SSoT or announces
  on app-aozora. Mirrors the containment + independent-governor +
  append-only-ledger topology (minidrama.operation / tashikame.operation).

  Everything the actor depends on is injected (each a swap, not a rewrite):
    - the Store     (MemStore | DatomicStore | kotoba-server)  — `store` arg
    - the Advisor   (mock AnimeLLM | real-LLM on Murakumo)     — :advisor opt
    - the Publisher (Mock | real app-aozora createRecord)      — :publisher opt
    - the Phase     (0 draft → 1 unlisted → 2 public+approval) — :phase in ctx

  One run = intake → advise → govern → decide → commit | hold. NO unbounded
  inner loop. PUBLIC announcement (phase 2) additionally requires an explicit
  approval grant in the run context (:publish per-clip human sign-off, or
  :auto-publish scheduled-loop standing grant — ADR-2607162200 Layer D) —
  the phase gate withholds announcement, and an AnimekaGovernor HARD
  violation withholds even the commit. Generation / assembly of actual video
  is NOT in this graph: the committed plan is the work order for the
  ai-gftd-animeka generation engine (integration currently HELD, see
  docs/adr/0001-architecture.md)."
  (:require [langgraph.graph :as g]
            [langgraph.checkpoint :as cp]
            [animekaza.advisor :as advisor]
            [animekaza.governor :as governor]
            [animekaza.phase :as phase]
            [animekaza.publisher :as publisher]
            [animekaza.store :as store]))

(defn- post-body [clip]
  (str "【アニメ家】新作クリップ『" (:title clip) "』enqueue — " (:logline clip)))

(defn- clip-record [request context proposal visibility]
  (let [c (:clip proposal)]
    {:clip-id    (:clip-id request)
     :actor      (:actor-id context)
     :title      (:title c)
     :logline    (:logline c)
     :scenes     (:scenes c)
     :duration   (advisor/shot-total c)
     :shots      (advisor/shot-count c)
     :visibility visibility
     :collection publisher/collection
     :text       (post-body c)}))

(defn build
  "Compiles the animeka OperationActor graph bound to `store`. opts:
    :advisor      — an `animekaza.advisor/Advisor` (default: mock-advisor)
    :publisher    — an `animekaza.publisher/Publisher` (default: mock-publisher)
    :checkpointer — langgraph checkpointer (default: in-mem)"
  [store & [{:keys [advisor publisher checkpointer]
             :or   {advisor      (advisor/mock-advisor)
                    publisher    (publisher/mock-publisher)
                    checkpointer (cp/mem-checkpointer)}}]]
  (-> (g/state-graph
       {:channels
        {:request     {:default nil}
         :context     {:default nil}   ; actor-id / phase / approvals / budget
         :proposal    {:default nil}
         :verdict     {:default nil}   ; AnimekaGovernor result
         :disposition {:default nil}   ; :commit | :hold
         :record      {:default nil}   ; the clip plan to commit/announce
         :published   {:default nil}   ; {:uri :cid} when announced
         :audit       {:reducer into :default []}}})

      (g/add-node :intake (fn [s] s))

      ;; AnimeLLM (contained intelligence) — proposal only.
      (g/add-node :advise
        (fn [{:keys [request]}]
          (let [p (advisor/-plan advisor store request)]
            {:proposal p :audit [(advisor/trace request p)]})))

      ;; AnimekaGovernor — independent censor (separate system than AnimeLLM).
      (g/add-node :govern
        (fn [{:keys [request context proposal]}]
          {:verdict (governor/check request context proposal)}))

      ;; Decide: HARD violation → :hold; else :commit.
      (g/add-node :decide
        (fn [{:keys [request context proposal verdict]}]
          (case (governor/verdict->disposition verdict)
            :hold
            {:disposition :hold
             :audit [(governor/hold-fact request context verdict)]}
            :commit
            (let [ph (:phase context phase/default-phase)
                  visibility (case (long ph) 2 :public 1 :unlisted :draft)]
              {:disposition :commit
               :record (assoc (clip-record request context proposal visibility)
                              :warnings (:warnings verdict))}))))

      ;; Commit — the ONLY node that writes the SSoT + audit ledger, and (when
      ;; the phase/approval gate allows) announces on app-aozora.
      (g/add-node :commit
        (fn [{:keys [request context proposal record]}]
          (let [ph       (:phase context phase/default-phase)
                publish? (and (phase/publish-allowed? ph (:approvals context))
                              (= :production (:effect proposal)))
                ;; audit which grant satisfied phase 2 (ADR-2607162200):
                ;; :publish (human) beats :auto-publish in the record when both
                ;; are present — the more explicit sign-off is the basis.
                grant    (when publish?
                           (first (filter (set (:approvals context)) [:publish :auto-publish])))
                pub      (when publish? (publisher/publish! publisher record))
                f        {:t           :committed
                          :op          (:op request)
                          :actor       (:actor-id context)
                          :clip        (:clip-id request)
                          :disposition :commit
                          :phase       ph
                          :published?  publish?
                          :publish-grant grant
                          :pub         pub
                          :warnings    (:warnings record)
                          :shots       (:shots record)
                          :duration    (:duration record)}]
            (store/commit-clip! store (:clip-id request) (dissoc record :warnings))
            (store/append-ledger! store f)
            {:published pub :audit [f]})))

      ;; Hold — write the rejection to the ledger; no SSoT mutation, no announce.
      (g/add-node :hold
        (fn [{:keys [audit]}]
          (when-let [hf (last (filter #(= :governor-hold (:t %)) audit))]
            (store/append-ledger! store (assoc hf :disposition :hold)))
          {}))

      (g/set-entry-point :intake)
      (g/add-edge :intake :advise)
      (g/add-edge :advise :govern)
      (g/add-edge :govern :decide)
      (g/add-conditional-edges :decide
        (fn [{:keys [disposition]}]
          (case disposition :commit :commit :hold)))
      (g/set-finish-point :commit)
      (g/set-finish-point :hold)

      (g/compile-graph {:checkpointer checkpointer})))

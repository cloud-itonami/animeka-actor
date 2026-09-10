(ns animekaza.publisher
  "Publisher — the outbound surface for an animeka clip announcement,
  injected so the network is a swap (MockPublisher default ‖ real app-aozora
  createRecord, animekaza.aozora). The graph never reaches the network
  directly; :commit calls `(publish! publisher record)` only after the
  AnimekaGovernor passed AND the phase/approval gate allows announcement
  (animekaza.phase, ADR-2607071300 shape + ADR-2607162200 Layer D).

  record shape (what gets announced):
    {:clip-id :title :logline :text (social-post body)
     :visibility :unlisted|:public
     :collection \"com.etzhayyim.apps.animeka.clip\"}

  Once produced media exists, the aozora announcement carries the
  app.aozora.embed.video embed ({:src <getBlob URL>} for VOD,
  {:playlist … :live true} for a live premiere — ADR-2607071000/2607071100).
  NOTE: the generation-engine path is currently HELD (docs/adr/0001 — no
  plan-EDN→mp4 CLI in ai-gftd-animeka yet), so no mp4 announcements are made.")

(def collection "com.etzhayyim.apps.animeka.clip")

(defprotocol Publisher
  (publish! [p record] "announce one clip record → {:uri :cid}"))

(defrecord MockPublisher [a]
  Publisher
  (publish! [_ record]
    (swap! a conj record)
    {:uri (str "at://mock/animeka/" (:clip-id record))
     :cid (str "mock:" (:clip-id record))}))

(defn mock-publisher
  "Deterministic in-memory publisher (default — records would-be posts).
  Optional atom arg lets a test read back what would have been announced."
  ([] (->MockPublisher (atom [])))
  ([a] (->MockPublisher a)))

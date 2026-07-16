(ns animekaza.store
  "SSoT for the animeka (アニメ家) actor — the append-only clip ledger behind
  a `Store` protocol so the backend is a swap, not a rewrite (MemStore
  default ‖ DatomicStore via langchain.db, itself swappable to real Datomic
  Local / kotoba-server pod e.g. kotobase.net).

  Domain (ADR-2607071300 shape, anime clips): a clip theme → an
  AnimeLLM-proposed production plan (title / logline / scenes / shot list for
  a ≤120 s vertical AI anime clip) → AnimekaGovernor censoring → a committed
  clip plan, optionally announced on app-aozora /videos (collection
  com.etzhayyim.apps.animeka.clip). The append-only ledger is the production
  provenance — every decision (commit / hold / publish) is an immutable fact,
  never overwritten.

  The store talks to its backend ONLY through the langchain.db `:db-api` map
  {:q :transact! :db :pull :entid}. `langchain.db/api` (in-process EAVT) and
  `langchain.kotoba-db/kotoba-api` (kotoba-server XRPC) both implement it, so
  the same `DatomicStore` record runs on either by construction."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [langchain.db :as d]))

(defprotocol Store
  (clip [s id] "the committed clip plan for a clip-id, or nil")
  (all-clips [s])
  (ledger [s])
  (commit-clip! [s id payload] "commit one governor-passed clip plan")
  (append-ledger! [s fact] "append one immutable decision fact"))

;; ───────────────────────── MemStore (default) ─────────────────────────

(defrecord MemStore [a]
  Store
  (clip [_ id] (get-in @a [:clips id]))
  (all-clips [_] (sort-by :clip-id (vals (:clips @a))))
  (ledger [_] (:ledger @a))
  (commit-clip! [s id payload] (swap! a assoc-in [:clips id] payload) s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact))

(defn seed-db
  "An empty MemStore."
  []
  (->MemStore (atom {:clips {} :ledger []})))

;; ───────────────────────── DatomicStore (langchain.db) ─────────────────────

(def ^:private schema
  {:animekaza.clip/id   {:db/unique :db.unique/identity}
   :animekaza.ledger/seq {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- q* [{:keys [api conn]} query & inputs]
  (apply (:q api) query ((:db api) conn) inputs))
(defn- tx* [{:keys [api conn]} txd] ((:transact! api) conn txd))

(defrecord DatomicStore [api conn]
  Store
  (clip [this id]
    (dec* (q* this '[:find ?p . :in $ ?id :where
                     [?e :animekaza.clip/id ?id]
                     [?e :animekaza.clip/payload ?p]]
              id)))
  (all-clips [this]
    (->> (q* this '[:find [?id ...] :where [?e :animekaza.clip/id ?id]])
         (map #(clip this %)) (sort-by :clip-id)))
  (ledger [this]
    (->> (q* this '[:find ?s ?f :where
                    [?e :animekaza.ledger/seq ?s] [?e :animekaza.ledger/fact ?f]])
         (sort-by first) (mapv (comp dec* second))))
  (commit-clip! [s id payload]
    (tx* s [{:animekaza.clip/id id :animekaza.clip/payload (enc payload)}]) s)
  (append-ledger! [s fact]
    (tx* s [{:animekaza.ledger/seq (count (ledger s)) :animekaza.ledger/fact (enc fact)}]) fact))

(defn datomic-store
  "DatomicStore on the in-process langchain.db EAVT backend (verifiable
  offline, no network). For the kotoba-server pod (kotobase.net), bind the
  same record to langchain.kotoba-db/kotoba-api — same record, different
  :db-api (see docs/adr/0001-architecture.md)."
  []
  (->DatomicStore d/api (d/create-conn schema)))

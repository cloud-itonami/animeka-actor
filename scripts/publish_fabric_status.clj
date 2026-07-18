(require '[animekaza.aozora :as aozora]
         '[animekaza.cacao :as cacao]
         '[animekaza.publisher :as publisher]
         '[clojure.data.json :as json])

(let [identity (cacao/load-or-create-identity! ".animeka/identity.edn")
      client (aozora/aozora-publisher {:pds aozora/default-pds
                                       :identity identity
                                       :json-write json/write-str
                                       :json-read json/read-str})
      record {:$type "app.bsky.feed.post"
              :collection "app.bsky.feed.post"
              :rkey "fabric-live-260717"
              :text "アニメ家のKotoba organism fabric接続を確認しました。署名Git、Kotobase、Murakumo、Aozoraを安全境界付きで検証中です。自動公開・自動課金は有効化していません。"
              :langs ["ja"]
              :createdAt "2026-07-17T09:45:00.000Z"}
      result (publisher/publish! client record)]
  (println (pr-str result)))

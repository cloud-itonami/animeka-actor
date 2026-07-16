(ns animekaza.clip-designs-test
  "clips/ のアニメクリップ設計カタログを、AnimeLLM 提案と同一の検閲
  (AnimekaGovernor) + フォーマット不変条件で全数検証する。設計が governor を
  通らないなら、それは出荷できない設計である。

  clips/*.edn は Datomic/Datascript tx-data ([{:db/id -1 :clip/...}])
  として保存されている (edn-datomize.bb wrap-map, ns=clip)。design map
  として消費するには reconstitute-design で :db/id を落とし :clip/ 名前空間
  を剥がし、blob 化された :clip/scenes を元の入れ子データへ戻す。"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [animekaza.advisor :as advisor]
            [animekaza.governor :as governor]
            [animekaza.produce :as produce]))

(defn- unblob [v]
  (if (string? v)
    (try (let [parsed (edn/read-string v)] (if (coll? parsed) parsed v))
         (catch Exception _ v))
    v))

(defn- reconstitute-design [tx-data]
  (into {} (map (fn [[k v]] [(keyword (name k)) (unblob v)]))
        (dissoc (first tx-data) :db/id)))

(defn- designs []
  (->> (.listFiles (io/file "clips"))
       (filter #(str/ends-with? (.getName %) ".edn"))
       (map #(reconstitute-design (edn/read-string (slurp %))))
       (sort-by :clip-id)))

(deftest catalog-has-five-designs
  (is (<= 5 (count (designs)) 7)))

(deftest every-design-passes-the-animeka-governor
  (doseq [{:keys [clip-id] :as d} (designs)]
    (testing clip-id
      (let [{:keys [disposition basis]}
            (produce/produce-plan! {:theme (:title d)
                                    :clip-id clip-id
                                    :advisor (produce/design-advisor d)})]
        (is (= :commit disposition) (pr-str basis))))))

(deftest every-design-meets-format-invariants
  (doseq [{:keys [clip-id duration-target premise scenes] :as d} (designs)]
    (testing clip-id
      (let [clip (select-keys d [:title :logline :scenes])
            total (advisor/shot-total clip)
            shots (for [sc scenes sh (:shots sc)] sh)]
        (is (= :anime premise) "アニメ前提 (アニメ家はアニメクリップ専門)")
        (is (= (double duration-target) total)
            "shot durations は duration-target にぴったり一致")
        (is (<= 30 total 60) "縦型アニメクリップの尺帯 (30〜60s)")
        (is (<= (count shots) governor/max-shots))
        (is (every? #(<= (double (:duration %)) governor/max-shot-duration) shots))
        (is (every? #(str/starts-with? (:prompt %) "anime style") shots)
            "全 shot prompt が anime style 指定")
        (is (every? #(seq (str/trim (or (:subtitle %) ""))) shots)
            "全 shot に台詞/字幕")
        (is (every? #(keyword? (:speaker %)) shots)
            "話者ヒント (将来の voice レグ演じ分け)")))))

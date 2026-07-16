(ns animekaza.sim
  "Offline demo: drive two sample clip themes (one clean, one over-budget)
  through the animeka actor on a MemStore + mock advisor + mock publisher
  (no network). `clojure -M:dev:run`."
  (:require [langgraph.graph :as g]
            [animekaza.operation :as op]
            [animekaza.store :as store]
            [animekaza.advisor :as advisor]
            [animekaza.publisher :as publisher])
  (:gen-class))

(defn -main [& _args]
  (let [s   (store/seed-db)
        pub (publisher/mock-publisher)
        a   (op/build s {:advisor (advisor/mock-advisor) :publisher pub})]
    (doseq [[ctx req] [[{:actor-id "animeka" :phase 1}
                        {:op :clip/plan :clip-id "c1"
                         :theme "桜と始発電車" :duration-target 45}]
                       [{:actor-id "animeka" :phase 1
                         :budget {:cost-per-shot 10 :episode-budget 24}}
                        {:op :clip/plan :clip-id "c2"
                         :theme "ロボットと子猫" :duration-target 45}]]]
      (let [r (g/run* a {:request req :context ctx}
                      {:thread-id (:clip-id req)})]
        (println (get-in r [:state :disposition]) "←" (:clip-id req)
                 "published?" (some? (get-in r [:state :published])))))
    (println "--- would-be announced records ---")
    (doseq [p @(:a pub)] (println (:clip-id p) "→" (:title p) "|" (:text p)))
    (println "--- ledger ---")
    (doseq [f (store/ledger s)] (prn f))))

(require '[animekaza.aozora :as aozora]
         '[animekaza.cacao :as cacao]
         '[clojure.data.json :as json])

(let [identity (cacao/load-or-create-identity! ".animeka/identity.edn")
      handle (or (System/getenv "AOZORA_ACTOR_HANDLE") "animeka-organism.aozora.app")
      opts {:pds aozora/default-pds :identity identity :handle handle
            :json-write json/write-str :json-read json/read-str}]
  (println (pr-str {:account (aozora/create-account! opts)
                    :handle (aozora/register-handle! opts)
                    :did (:did identity)})))

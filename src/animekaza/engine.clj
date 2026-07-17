(ns animekaza.engine
  "Engine leg unblock (docs/adr/0001-architecture.md addendum, 2026-07-17):
  ai-gftd-animeka still has no plan-EDN→mp4 CLI (the HOLD condition is
  unchanged — this namespace does NOT touch that repo). Instead this calls
  out to the murakumo fleet's own general-purpose :video model (ltx-2.3,
  gftdcojp/cloud-murakumo resources/murakumo.edn, ADR-2607171330 owner
  decision: fleet self-hosted OSS is the first :video path) directly via its
  ComfyUI HTTP surface on gad — the SAME graph dougaka calls for live-action,
  here fed clip shot :prompt strings that already carry an \"anime style, ...\"
  prefix (clips/*.edn convention). This is calling an existing shared fleet
  service, not implementing generation inside the actor (CLAUDE.md invariant
  unaffected — no model code, no GPU code lives here).

  Verified 2026-07-17 (superproject 90-docs/gen-quality/gen-quality-ledger.edn,
  run gq-20260717-ltx-anime-prompt): the identical graph produced a real
  anime-style clip in 88s, composite quality score 0.624 (baseline placeholder
  0.502). Graph is the ADR-2607171100 LTX-2.3 single-stage text-to-video
  transcribed node-for-node from cloud-murakumo engine.cljc ltx-video-graph.

  Fail-closed / opt-in: ANIMEKA_COMFY_URL must be set (e.g.
  http://<gad-tailnet-ip>:8188) or `submit-clip!` throws — outer-loop only
  calls this when the env var is present; absent it, behavior is UNCHANGED
  (tick still consumed as \"held\"/engine-hold). No silent fallback either
  direction."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse$BodyHandlers]))

(def ckpt "ltx-2.3-22b-distilled-fp8.safetensors")
(def text-encoder "gemma_3_12B_it_fp4_mixed.safetensors")

(defn- http [{:keys [url method body]}]
  (let [b (HttpRequest/newBuilder (URI/create url))]
    (-> b (.header "content-type" "application/json")
        (.method (str/upper-case (name (or method :get)))
                 (if body (HttpRequest$BodyPublishers/ofString body)
                     (HttpRequest$BodyPublishers/noBody))))
    (let [resp (.send (HttpClient/newHttpClient) (.build b) (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode resp) :body (.body resp)})))

(defn- comfy-url []
  (or (System/getenv "ANIMEKA_COMFY_URL")
      (throw (ex-info "ANIMEKA_COMFY_URL not set — engine unblock is opt-in, fail-closed"
                      {:reason :engine-not-configured}))))

(defn- clip-prompt
  "Concatenate the plan's shot prompts into one text-to-video prompt (the
  single-stage LTX graph takes one prompt for the whole clip, not per-shot —
  same simplification dougaka's engine makes for its own short clips)."
  [plan]
  (let [shots (for [sc (:scenes plan) sh (:shots sc)] sh)]
    (str/join ". " (map :prompt shots))))

(defn ltx-graph
  "ADR-2607171100 LTX-2.3 single-stage t2v(+audio) graph, node-for-node from
  cloud-murakumo engine.cljc ltx-video-graph — kept byte-identical so the
  gen-quality ledger's scored samples stay representative of what this
  actually submits."
  [{:keys [prompt width height frames seed] :or {width 768 height 448 frames 49 seed 0}}]
  {"100" {:class_type "CheckpointLoaderSimple" :inputs {:ckpt_name ckpt}}
   "101" {:class_type "LTXVAudioVAELoader" :inputs {:ckpt_name ckpt}}
   "111" {:class_type "LTXAVTextEncoderLoader"
          :inputs {:text_encoder text-encoder :ckpt_name ckpt :device "default"}}
   "109" {:class_type "PrimitiveStringMultiline" :inputs {:value prompt}}
   "102" {:class_type "CLIPTextEncode" :inputs {:text ["109" 0] :clip ["111" 0]}}
   "112" {:class_type "PrimitiveFloat" :inputs {:value 24.0}}
   "103" {:class_type "LTXVConditioning"
          :inputs {:positive ["102" 0] :negative ["102" 0] :frame_rate ["112" 0]}}
   "113" {:class_type "PrimitiveInt" :inputs {:value frames}}
   "106" {:class_type "PrimitiveInt" :inputs {:value 24}}
   "115" {:class_type "LTXVEmptyLatentAudio"
          :inputs {:frames_number ["113" 0] :frame_rate ["106" 0]
                   :batch_size 1 :audio_vae ["101" 0]}}
   "131" {:class_type "EmptyImage" :inputs {:width width :height height :batch_size 1 :color 0}}
   "121" {:class_type "ImageScaleBy"
          :inputs {:image ["131" 0] :upscale_method "nearest-exact" :scale_by 1.0}}
   "122" {:class_type "GetImageSize" :inputs {:image ["121" 0]}}
   "116" {:class_type "EmptyLTXVLatentVideo"
          :inputs {:width ["122" 0] :height ["122" 1] :length ["113" 0] :batch_size 1}}
   "132" {:class_type "LTXVConcatAVLatent"
          :inputs {:video_latent ["116" 0] :audio_latent ["115" 0]}}
   "126" {:class_type "KSamplerSelect" :inputs {:sampler_name "euler_ancestral"}}
   "176" {:class_type "ManualSigmas"
          :inputs {:sigmas "1., 0.99375, 0.9875, 0.98125, 0.975, 0.909375, 0.725, 0.421875, 0.0"}}
   "123" {:class_type "RandomNoise" :inputs {:noise_seed seed}}
   "127" {:class_type "CFGGuider"
          :inputs {:model ["100" 0] :positive ["103" 0] :negative ["103" 1] :cfg 1.0}}
   "125" {:class_type "SamplerCustomAdvanced"
          :inputs {:noise ["123" 0] :guider ["127" 0] :sampler ["126" 0]
                   :sigmas ["176" 0] :latent_image ["132" 0]}}
   "133" {:class_type "LTXVSeparateAVLatent" :inputs {:av_latent ["125" 1]}}
   "119" {:class_type "VAEDecodeTiled"
          :inputs {:samples ["133" 0] :vae ["100" 2]
                   :tile_size 512 :overlap 64 :temporal_size 4096 :temporal_overlap 8}}
   "120" {:class_type "LTXVAudioVAEDecode" :inputs {:samples ["133" 1] :audio_vae ["101" 0]}}
   "114" {:class_type "CreateVideo" :inputs {:images ["119" 0] :fps ["112" 0] :audio ["120" 0]}}
   "104" {:class_type "SaveVideo"
          :inputs {:video ["114" 0] :filename_prefix "animeka-clip" :format "auto" :codec "auto"}}})

(defn- poll-result [base pid {:keys [timeout-ms poll-ms] :or {timeout-ms 300000 poll-ms 3000}}]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (if (> (System/currentTimeMillis) deadline)
        (throw (ex-info "engine poll timeout" {:prompt-id pid}))
        (let [{:keys [status body]} (http {:url (str base "/history/" pid)})
              h (when (= 200 status) (json/read-str body :key-fn keyword))
              entry (get h (keyword pid))]
          (cond
            (nil? entry) (do (Thread/sleep poll-ms) (recur))
            (get-in entry [:status :status_str])
            (let [st (get-in entry [:status :status_str])]
              (cond
                (= st "error")
                (throw (ex-info "engine generation error" {:status (:status entry)}))
                (or (get-in entry [:status :completed]) (= st "success"))
                (let [outs (:outputs entry)
                      file (some (fn [[_ o]]
                                   (some (fn [[_ v]]
                                           (when (and (vector? v) (map? (first v))
                                                      (:filename (first v)))
                                             (first v)))
                                         o))
                                 outs)]
                  (or file (throw (ex-info "engine completed with no output file" {:outputs outs}))))
                :else (do (Thread/sleep poll-ms) (recur))))
            :else (do (Thread/sleep poll-ms) (recur))))))))

(defn submit-clip!
  "plan (as read by animekaza.produce/read-design) → downloads the produced
  mp4 to `out-path`. Fail-closed if ANIMEKA_COMFY_URL unset or generation
  errors — never returns a fabricated path."
  [plan out-path]
  (let [base (comfy-url)
        prompt (clip-prompt plan)
        g (ltx-graph {:prompt prompt})
        {:keys [status body]} (http {:url (str base "/prompt") :method :post
                                     :body (json/write-str {:prompt g :client_id "animekaza"})})]
    (when-not (= 200 status)
      (throw (ex-info "engine submit failed" {:status status :body body})))
    (let [pid (get (json/read-str body :key-fn keyword) :prompt_id)
          {:keys [filename subfolder type]} (poll-result base pid {})
          url (str base "/view?filename=" (java.net.URLEncoder/encode filename "UTF-8")
                   "&subfolder=" (java.net.URLEncoder/encode (or subfolder "") "UTF-8")
                   "&type=" (or type "output"))
          resp (.send (HttpClient/newHttpClient)
                      (.build (HttpRequest/newBuilder (URI/create url)))
                      (HttpResponse$BodyHandlers/ofByteArray))]
      (io/make-parents out-path)
      (with-open [out (io/output-stream out-path)]
        (.write out ^bytes (.body resp)))
      out-path)))

# ADR 0001: animeka actor architecture (R0) + engine-integration HOLD

**Status**: accepted — 2026-07-16
**Deciders**: Jun Kawasaki（superproject ADR-2607162200 Phase C の実装分）
**Mirrors**: superproject `com-junkawasaki/root`
`90-docs/adr/2607162200-aozora-creator-scheduled-publishing-integration.md`
（4 層統合設計の正本。本 ADR はその animeka 実装分 + repo-local に固定した
判断）。actor family の原型は `90-docs/adr/2607071300-aozora-creator-actors-
minidrama.md`、テンプレートは `etzhayyim/com-etzhayyim-minidrama`（main、
2026-07-16 の ADR-0002 改訂済み状態）。

## Topology

containment + independent governor + append-only ledger
（minidrama / tashikame / tsumugu と同型）:

- **AnimeLLM**（`animekaza.advisor`、封じ込め）: theme → 縦型 AI アニメ
  クリップの production plan proposal（title/logline/scenes/shots、prompt は
  "anime style, …"）。proposal のみ — 生成 job も公開も決して自分では行わない。
- **AnimekaGovernor**（`animekaza.governor`、別系統）: HARD → HOLD（no
  override）、SOFT → タグ付き commit。gates は README 参照（minidrama と
  同一構成を意図的に踏襲 — 縦型ショート動画としての物理限界と Rider §2 は
  ドラマ/アニメで変わらない）。
- **台帳**（`animekaza.store`）: clip plan は `:animekaza.clip/id`、
  全 decision は `:animekaza.ledger/seq` の append-only fact。backend は
  langchain.db `:db-api` map 越しのみ（MemStore ≡ DatomicStore、contract
  test 保証）。

## R0 で固定した判断

1. **ns 接頭辞は `animekaza.*`（アニメ家座）**。生成エンジン repo
   `gftdcojp/ai-gftd-animeka` が `animeka.*` ns（`animeka.comfy` /
   `animeka.server` 等）を既に所有しており、actor と engine を同一 classpath
   に載せたときの ns 衝突を構造的に避ける。actor slug / handle / collection
   は "animeka" のまま（`animeka.aozora.app` / `com.etzhayyim.apps.animeka.*`）。
2. **phase gate は minidrama ADR-0002 改訂版をそのまま踏襲**（`:publish` +
   `:auto-publish` の 2 grant、台帳 `:publish-grant` 監査、governor HARD が
   不変の escalation 境界）。minidrama R0 の「human 承認のみ」期を経ずに
   最初から ADR-2607162200 Layer D 形で入る。
3. **エンジン統合は HOLD（本 ADR の主決定）** — 下記。
4. **HITL は approval-in-context、interrupt-before ではない**（minidrama
   ADR-0001 判断 1 の踏襲）。
5. **phase 既定は 0 (draft)**。unlisted (phase 1) は自動可。

## エンジン統合 HOLD — 調査結果 (2026-07-16、コード直読)

**判定: `ai-gftd-animeka` に「plan EDN → mp4 を offline で作れる CLI 経路」は
存在しない。** dougaka の `dougaka.pipeline`（`clj/src/dougaka/pipeline.clj`
の `-main`: committed plan → keyframes → ffmpeg → 縦 mp4 + SRT）に相当する
ものが無いため、minidrama の `produce → engine → announce` chain
（`scripts/produce-episode.bb`）を animeka では組めない。

何が**ある**か:
- `animeka.comfy/render-keyframe` — ComfyUI 経由の**静止画 keyframe 1 枚**
  の text2img。実経路は `COMFY_POD_URL`（cloud-murakumo fleet head `gad` の
  comfy-openai-bridge `:8189`、Tailscale 内限定）が必要で、無ければ
  placeholder SVG stub に degrade する。
- `animeka.graphs.generation` — keyframe 以外の layer
  （storyboard/layout/inbetween/background/composite/…）は **metadata-only**
  （record を書き cut stage を "done" にするだけ、ピクセルを作らない）。
  `make-assemble-episode` / `make-publish-episode` も **status 文字列の
  書き換えのみ**（`post_status "skipped"`）。
- 唯一の `-main` は `animeka.server`（http-kit HTTP サーバ）。`clj/src` に
  ffmpeg / mp4 出力 / フレーム→動画の組み立てはひとつも無い（grep 実測）。
- 動画の設計上の行き先は kami-cine bridge（`gftd:kami-cine@1.0.0`、
  stages 1–8 → EXR/mp4）だが、これは engine repo 側 CLAUDE.md の設計記述で
  あり animeka の clj runtime からの実行経路は未配線。

何が**無い**か（= HOLD の根拠）:
- plan EDN を入力に取る CLI entrypoint（`-main`）。
- keyframe 静止画列 → 動画（inbetween 補間・ffmpeg concat・エンコード）の
  実装。
- offline で degrade せずに実 mp4 を出せる経路（ComfyUI 実経路ですら
  静止画のみ、かつ tailnet 依存）。

**decision**: エンジン統合（produce → engine → announce chain、
`scripts/produce-clip.bb` 相当）は実装しない。**フェイク生成（単色フレーム・
placeholder 連結等）で mp4 を偽造して E2E を成立させることを明示的に禁止する**
（捏造ゼロ。superproject の standalone BMC ログ規約と同じ不変条件）。

**撤去条件（HOLD 解除）**: ai-gftd-animeka（または kami-cine bridge /
cloud-murakumo seedance 経路 ADR-2607170500）に「plan EDN（本 actor の
`.animeka/clips/<id>.edn` work order 互換）→ mp4」の実 CLI が landed し、
実データで E2E 検証されたとき。その時点で `animekaza.outer-loop/run-once!`
を minidrama の run-chain! 形（produce → engine → announce、`:auto-publish`
grant + governor escalation 境界）に拡張し、本 ADR に addendum を積む。

## outer loop（ADR-2607162200 Layer B、engine-hold 形）

- tick は PDS の `app.aozora.creator.getTicks?actor=animeka` から読む。
  actor は tick db に書かない。registry cadence は `:cadence {:per-day 1
  :active? false}` 登録済み（superproject ADR-2607162200 追記）なので
  **通常 tick は空 = `:idle` が正常**。
- 消費は自 repo の record（collection `com.etzhayyim.apps.animeka.tick`、
  rkey `<date>-<slot>`）で記録し、これが lease を兼ねる（並行インスタンスは
  record を見て skip、冪等）。
- **engine HOLD 中に tick が来た場合**: 次の未消費カタログ設計を
  clip-id に記した `"held"`/`:reason "engine-hold"` record を積んで
  escalate する。製造・announce は行わない。

## Identity / publish 経路（実装済み・エンジンと独立に live）

- Ed25519 did:key 自己生成（`animekaza.cacao/load-or-create-identity!`、
  `.animeka/identity.edn` gitignored、秘密鍵素材はログに出さない）。
- createAccount → updateHandle（keyed flip、`animeka.aozora.app` →
  actor 自身の did:key）→ resolveHandle 検証。
- profile record（`com.etzhayyim.apps.animeka.profile`、rkey `self`）を
  createSession(self-CACAO)→JWT→createRecord で公開 — エンジン不在でも
  identity + publish 配線は本物で live 検証する（mp4 の捏造はしない）。

## Follow-ups

- エンジン CLI landed 後: outer loop の run-chain 拡張 + E2E 1 clip
  製造・announce + 本 ADR addendum。
- registry cadence flip（`:active? true`）は owner 判断（registry 1 行）。
- RAD identity journal 登録（etzhayyim/root `80-data/kotoba-rad/`、
  minidrama ADR-0001 の完了形と同型）。
- superproject west / fleet-db 登録は親セッションが実施（本 repo からは
  manifest に触らない）。

## 追記 (2026-07-16): org 移設 — etzhayyim/com-etzhayyim-animeka → gftdcojp/animeka-actor

オーナー指示により gftdcojp org へ transfer(rename、visibility は org 既定の
private)。**lexicon NSID(`com.etzhayyim.apps.*`)は既に PDS 上の実 record が
使っている wire 識別子のため変更しない**(repo のホスト org と lexicon
namespace は独立 — aozora identity は etzhayyim-rooted のまま)。GitHub の旧
URL は redirect が残る。

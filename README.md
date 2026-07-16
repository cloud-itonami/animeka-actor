# com-etzhayyim-animeka (アニメ家)

縦型（720x1280）30〜60 秒 **AI アニメクリップ**の制作 actor。企画 → 絵コンテ
（shot list、prompt は "anime style, …"）までを AnimeLLM が *proposal* として
提案し、**AnimekaGovernor が検閲**して可決分だけを append-only 台帳に commit
する。可決済みプランは `gftdcojp/ai-gftd-animeka` 生成エンジンへの work order
であり、公開は app-aozora `/videos`（`app.aozora.embed.video`、
ADR-2607071000/2607071100 経路）。

設計 正本: superproject
`90-docs/adr/2607162200-aozora-creator-scheduled-publishing-integration.md`
（Phase C）+ `90-docs/adr/2607071300-aozora-creator-actors-minidrama.md`
（actor family 原型）。repo-local: `docs/adr/0001-architecture.md`。
actor identity: `animeka.aozora.app`（鍵付き did:key、`aozora.appview.creator-actors`
registry — cadence は `:active? false` で登録済み、flip は registry 1 行）。

ns 接頭辞は `animekaza.*`（アニメ家座）— エンジン repo の `animeka.*` と
衝突しないため。

## Overview

```
theme ──▶ :advise (AnimeLLM, sealed) ──▶ :govern (AnimekaGovernor) ──▶ :decide
                                                        │
                :commit ◀── clean ──────────────────────┴── HARD ──▶ :hold
                  │  SSoT (clip plan) + ledger                 ledger only
                  └─ phase/approval gate ──▶ Publisher (announce)
```

## StateGraph (one clip plan = one run)

`animekaza.operation/build` — intake → advise → govern → decide →
commit | hold。無限内部ループ無し。生成・合成はこの graph に含めない
（committed plan がエンジンへの発注書）。

## AnimekaGovernor gates (ADR-2607071300 shape)

HARD → HOLD（台帳に記録、commit も announce もしない）:
`:no-actuation` `:over-duration`(>120s) `:too-many-shots`(>24)
`:overlong-shot`(>10s) `:content-veto`(Rider §2) `:likeness`
`:unprovenanced-asset` `:budget-exceeded` `:rate-limited`

SOFT → commit + タグ: `:low-confidence`

## Phase rollout

| phase | label | announce |
|---|---|---|
| 0 (default) | draft | しない（台帳のみ） |
| 1 | unlisted | 自動（unlisted preview） |
| 2 | public | **`:publish`（per-clip human）または `:auto-publish`（scheduled outer loop standing grant、ADR-2607162200 Layer D）がある run だけ** |

## エンジン統合 — HOLD (2026-07-16)

`ai-gftd-animeka` には dougaka の `dougaka.pipeline` に相当する
**plan EDN → mp4 の offline CLI 経路が存在しない**（唯一の `-main` は
`animeka.server` HTTP サーバ; `generate-keyframe` は ComfyUI gateway 経由の
静止画 1 枚のみ・offline は placeholder SVG; `assembleEpisode`/`publishEpisode`
は status metadata の書き換えのみ; clj/src に ffmpeg / mp4 出力は無い）。
根拠と撤去条件は `docs/adr/0001-architecture.md`。フェイク生成で E2E を
偽装しない — engine leg が landed するまで outer loop は tick を
`"held"`/engine-hold で消費して owner に escalate する。

## Injected seams (each a swap, core unchanged)

- **Store** — `MemStore`（既定）‖ `DatomicStore`（langchain.db `:db-api`、
  kotoba-server pod へも同 record で接続可）
- **Advisor** — `mock-advisor`（既定、決定的）‖ `llm-advisor`
  （`langchain.model` ChatModel、Murakumo fleet 限定 `assert-murakumo!`）
- **Publisher** — `MockPublisher`（既定）‖ 実 app-aozora createRecord
  （`animekaza.aozora`、CACAO self-mint）
- **Phase / approvals / budget / daily-cap** — run の `:context`

## Run

```bash
clojure -M:lint       # clj-kondo (errors fail)
clojure -M:dev:test   # cognitect test-runner
clojure -M:dev:run    # offline demo (mock advisor/publisher, MemStore)

# theme 一発で plan work order を製造 (エンジン leg は HOLD、mp4 は作られない):
clojure -M:dev -m animekaza.produce "桜と始発電車" my-clip 45
clojure -M:dev -m animekaza.produce --from clips/sakura-densha.edn

# identity (keyed actor):
clojure -M:dev -m animekaza.deploy create-account    # createAccount (self-CACAO)
clojure -M:dev -m animekaza.deploy register-handle   # updateHandle keyed flip
clojure -M:dev -m animekaza.deploy identify-live     # profile record (rkey self)

# outer loop (tick 消費、registry cadence inactive なので通常 :idle):
clojure -M:dev -m animekaza.outer-loop status
clojure -M:dev -m animekaza.outer-loop
```

## clips/ — アニメクリップ設計カタログ (2026-07-16)

5 本の手書き設計（35〜60s 縦型 / shot list + 台詞 + :speaker ヒント /
prompt は "anime style, …"、実在人物・商標なし）。全設計は
`clip-designs-test` で AnimekaGovernor + フォーマット不変条件を全数検証
される — **governor を通らない設計はカタログに置けない**。

| slug | title | genre | 尺 |
|---|---|---|---|
| sakura-densha | 桜と始発電車 | ファンタジー/日常 | 45s |
| robot-koneko | ロボットと子猫 | SFハートフル | 40s |
| amayadori-jinja | 雨宿りの神社 | 和風ファンタジー | 60s |
| hoshi-tsuri | 星を釣る少女 | ファンタジー | 50s |
| pan-mahou | 魔法のパン屋の朝 | コメディ/日常 | 35s |

## Related files

- `src/animekaza/operation.cljc` — StateGraph
- `src/animekaza/governor.cljc` — AnimekaGovernor
- `src/animekaza/advisor.cljc` — AnimeLLM (mock ‖ Murakumo LLM)
- `src/animekaza/store.cljc` — Store (MemStore ‖ DatomicStore)
- `src/animekaza/publisher.cljc` — Publisher (Mock ‖ aozora)
- `src/animekaza/phase.cljc` — phase 0 draft / 1 unlisted / 2 public+grant
- `src/animekaza/cacao.clj` / `aozora.clj` — CACAO self-mint + PDS I/O (JVM)
- `src/animekaza/outer_loop.clj` — tick 消費 outer loop (engine-hold escalate)
- `docs/adr/0001-architecture.md` — repo-local design note + engine-hold 根拠

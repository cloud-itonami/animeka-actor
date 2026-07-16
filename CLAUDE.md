# animeka-actor

animeka (アニメ家) — AI アニメーションクリップ制作 actor。core contract は
`README.md`、pattern は full-repo `../../../CLAUDE.md` "Actors" 節
（containment + independent governor + append-only ledger）。
Superproject decision records:
`../../../90-docs/adr/2607162200-aozora-creator-scheduled-publishing-integration.md`
（Phase C: dougaka / animeka への横展開）と
`../../../90-docs/adr/2607071300-aozora-creator-actors-minidrama.md`（actor family
の原型）。Design 正本: `docs/adr/0001-architecture.md`。
テンプレート: `etzhayyim/com-etzhayyim-minidrama`（keep in sync）。

ns 接頭辞は **`animekaza.*`（アニメ家座）** — 生成エンジン repo
`gftdcojp/ai-gftd-animeka` の `animeka.*` ns と衝突させないため。actor slug /
handle は "animeka" / `animeka.aozora.app` のまま、collection は
`com.etzhayyim.apps.animeka.*`。

## Invariant

animeka は AnimekaGovernor が拒否したプランを NEVER commit / announce する。
over-duration(>120s) / too-many-shots(>24) / overlong-shot(>10s) /
content-veto(Rider §2) / likeness / unprovenanced-asset / budget-exceeded /
rate-limited は HELD — append-only 台帳に hold として記録され、SSoT には
書かれない。`:commit` だけが Store 書込 + announce を行い、全 commit/hold は
不変の台帳 fact。**public announcement (phase 2) は run context の
approval grant（`:publish` = per-clip human sign-off、または
`:auto-publish` = スケジュール outer loop の standing grant、
superproject ADR-2607162200 Layer D）が無い限り行わない**。
`:auto-publish` は 2026-07-10 恒久承認（公開コンテンツの発行も agent 判断で
可）を反映した grant で、**AnimekaGovernor の HARD gate（content-veto /
likeness / provenance / budget / rate-cap）が escalation 境界として不変**:
HOLD はどの phase でも announce されず、owner へ surface される。どの grant で
公開されたかは台帳 fact の `:publish-grant` に監査記録される。
unlisted (phase 1) までは grant 無しで自動可。
low-confidence は block せず `:low-confidence` タグで commit（透明性）。

**生成・合成はこの actor に実装しない** — committed plan は
`gftdcojp/ai-gftd-animeka` 生成エンジンへの発注書。ただし**エンジン統合は
現在 HOLD**（`docs/adr/0001-architecture.md`: ai-gftd-animeka に
plan EDN → mp4 の offline CLI 経路が存在しない — 唯一の `-main` は
`animeka.server`、実レンダラは静止画 keyframe のみ、assemble/publish は
metadata のみ）。**エンジン不在を単色フレーム等のフェイク生成で偽装しない**
（捏造ゼロ）。outer loop は tick を `"held"`/engine-hold で消費して escalate
する。

## Conventions

- `.cljc` for anything portable (operation/governor/advisor/publisher/phase/
  store/sim) — `.clj` は JVM-only I/O（cacao / aozora / announce / deploy /
  produce / outer-loop）のみ。
- actor 自身の Ed25519 identity は `.animeka/identity.edn`（gitignored）—
  NEVER commit a private key。**秘密鍵素材（private-b64）を stdout / ログ /
  セッションログに出力しない**（minidrama で 2026-07-16 に露出事故 →
  鍵ローテーションの実例あり）。
- `clojure -M:lint`（clj-kondo, errors fail）/ `clojure -M:dev:test`。

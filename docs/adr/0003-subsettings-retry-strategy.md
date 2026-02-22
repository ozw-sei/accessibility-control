# ADR-0003: SubSettings 検出のリトライ＋ノードスキャン戦略

- **Status:** Accepted
- **Date:** 2025-02-22
- **Deciders:** @ozawa

## Context

Pixel 9 Pro Fold（AOSP ベース）では、ユーザー補助設定が独立した Activity ではなく、汎用コンテナ `com.android.settings.SubSettings` 内のフラグメントとして表示される。

```
dumpsys の出力:
Activities=[ActivityRecord{... com.android.settings/.SubSettings t580}]
  mActivityComponent=com.android.settings/.SubSettings
  (内部フラグメント: AccessibilitySettings)
```

これにより 2 つの問題が発生した。

### 問題 1: クラス名でブロックできない

`TYPE_WINDOW_STATE_CHANGED` の `event.className` が `SubSettings` を返す。`SubSettings` はユーザー補助以外にも Wi-Fi、Bluetooth 等あらゆる設定サブ画面で使われるため、クラス名だけでブロック対象かを判定できない。

### 問題 2: タイトルの取得タイミング

`TYPE_WINDOW_STATE_CHANGED` 発火時点ではフラグメントの UI（ツールバータイトル等）がまだ描画されておらず、`getWindowTitle()` が正しいタイトルを返さないケースがある。

Pixel 設定アプリの検索結果から遷移する場合、`getWindowTitle()` のフォールバック `android:id/title` が検索結果リストのアイテム名（例: 「ダウンロードしたアプリ」）を拾い、実際のページタイトル「ユーザー補助」を見逃す事象が確認された。

### 検討した代替案

| 案 | 概要 | 却下理由 |
|----|------|---------|
| `SubSettings` を `BLOCKED_CLASS_PATTERNS` に追加 | SubSettings を見たら常にブロック | 全てのサブ設定画面がブロックされる。Wi-Fi等も開けなくなる |
| `TYPE_WINDOW_CONTENT_CHANGED` のみに依存 | コンテンツ変更イベントでタイトルを取得 | 発火しないケースがある（画面 resume 時等） |
| 固定遅延 1 回だけリトライ | 500ms 後に 1 回だけ再チェック | タイミングがデバイスや負荷状況で変わるため不安定 |

## Decision

3 層の検出メカニズムを採用する。

### Layer A: タイトルベース検出 + 段階的リトライ

`SubSettings` を検知したら即座にタイトルを取得し、失敗した場合は `Handler.postDelayed` で 5 回リトライする。

```
150ms → 400ms → 800ms → 1500ms → 3000ms
```

リトライ時に以下の安全チェックを行う:
- 許可条件が変わっていないか
- まだ設定アプリにいるか（`rootInActiveWindow.packageName`）
- ブロック成功したら残りのリトライをキャンセル

### Layer B: ノードツリー直接スキャン

タイトル取得に失敗した場合のフォールバックとして、`findAccessibilityNodeInfosByText` でノードツリーからブロック対象テキスト（「ユーザー補助」「Accessibility」等）を直接検索する。

誤検出防止のため `isHeaderNode()` フィルタを適用:
- `AccessibilityNodeInfo.isHeading` が true
- 親ノードに `Toolbar` / `ActionBar` / `CollapsingToolbar` が存在（5 階層まで）

### Layer C: `TYPE_WINDOW_CONTENT_CHANGED` での補完

コンテンツ変更イベントでも同じタイトル検出 + ノードスキャンを実行。Layer A/B で漏れた場合の最終補完。

### `android:id/title` の誤検出対策

`getWindowTitle()` の `android:id/title` フォールバックを `isHeaderNode()` でフィルタし、リストアイテム内のタイトルテキストを拾わないようにした。

## Consequences

### Positive

- 即座にタイトルが取得できるケース、遅延して取得できるケース、タイトルが取得できないケースの全てをカバー
- 最大 3 秒のリトライ窓があるため、端末負荷が高い状況でも検出可能
- `isHeaderNode()` による誤検出防止で、検索結果等の無関係なテキストに反応しない

### Negative

- `Handler.postDelayed` による遅延チェックのため、最悪ケースで最大 150ms のラグが発生（ユーザーが一瞬だけ画面を見る）
- ノードスキャンは `findAccessibilityNodeInfosByText` を `BLOCKED_TITLE_PATTERNS` の数だけ呼ぶため、パフォーマンスコストがある（ただし実測上は問題なし）
- OEM がツールバーの実装を変更した場合、`isHeaderNode()` のヒューリスティクスが壊れる可能性がある

### Observed Results (Pixel 9 Pro Fold)

- 設定トップ → ユーザー補助: ブロック成功
- 検索「ユーザー補助」→ クリック: ブロック成功
- タスクキル後の再試行: ブロック成功
- ログ: `Blocked (SubSettings retry #N) via node scan: ユーザー補助`

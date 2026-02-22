# ADR-0004: SettingsDetector の分離とテスタビリティ設計

- **Status:** Accepted
- **Date:** 2024-12-01
- **Deciders:** @ozawa

## Context

`GuardAccessibilityService` は Android の `AccessibilityService` を継承しており、フレームワークに強く依存する。`onAccessibilityEvent` 内でパターンマッチングロジックを直接書くと:

- JVM 単体テストが書けない（AccessibilityService のモックが困難）
- ロジック変更のたびに実機/エミュレータでの結合テストが必要
- OEM ごとのパターン追加時にテスト工数が大きい

同様に、`ConditionChecker` も `Clock` や充電状態に依存するため、テスト時にこれらを差し替えられる設計が必要。

## Decision

### SettingsDetector: Pure Kotlin Object として分離

パターンマッチングロジックを `SettingsDetector` object に抽出する。

```kotlin
object SettingsDetector {
    val BLOCKED_CLASS_PATTERNS = listOf(...)  // OEM 別のクラス名パターン
    val BLOCKED_TITLE_PATTERNS = listOf(...)  // 多言語タイトルパターン

    fun isBlockedClassName(className: String?): Boolean
    fun isBlockedTitle(title: String?): Boolean
    fun isSettingsPackage(packageName: String?): Boolean
}
```

- 入力は `String?` のみ、Android フレームワーク依存なし
- `object` なのでインスタンス管理不要
- JVM 上で Robolectric なしにテスト可能

### ConditionChecker: コンストラクタインジェクション

```kotlin
class ConditionChecker(
    private val context: Context,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val chargingProvider: ChargingProvider = SystemChargingProvider(context),
)
```

- `Clock`: テスト時に `Clock.fixed()` で任意の時刻を注入
- `ChargingProvider`: インターフェースとして抽象化、テスト時にラムダで差し替え
- `SharedPreferences`: Robolectric のインメモリ実装で自動的にテスト可能

### OEM パターンの管理

`BLOCKED_CLASS_PATTERNS` でカバーする OEM:

| OEM | パターン |
|-----|---------|
| AOSP / Pixel | `AccessibilitySettings`, `ToggleAccessibilityServicePreferenceFragment` 等 |
| Samsung OneUI | `AccessibilitySettingsActivity` |
| Xiaomi MIUI | `MiuiAccessibilitySettings` |

ターゲット端末は Pixel 9 Pro Fold（AOSP パターン）だが、他 OEM も低コストでカバーできるため事前に追加。

## Consequences

### Positive

- `SettingsDetectorTest` は純粋な JVM テストで実行可能（Robolectric 不要）
- OEM パターンの追加が `BLOCKED_CLASS_PATTERNS` へのリスト追加 + テスト追加だけで完結
- `ConditionCheckerTest` で境界値テスト（06:00 ちょうど、07:59、08:00 等）を網羅的にカバー可能
- `GuardAccessibilityService` はイベントハンドリングとアクション実行に集中

### Negative

- `SettingsDetector` が `object`（シングルトン）なのでテスト間の状態汚染リスクがある（ただし現状は immutable な定数のみなので問題なし）
- パターンマッチングとノードスキャンが別クラスに分散（`SettingsDetector` はテキストパターン、`GuardAccessibilityService.isHeaderNode()` はノード構造判定）

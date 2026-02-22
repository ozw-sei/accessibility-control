# ADR-0001: AccessibilityService + Device Owner の二重防御アーキテクチャ

- **Status:** Accepted
- **Date:** 2024-12-01
- **Deciders:** @ozawa

## Context

Android の Freedom（アプリブロッカー）を「設定 > ユーザー補助 > 無効化」で回避してしまう衝動的行動を防ぎたい。しかし、Android のセキュリティモデル上、単一の技術では自己防衛が困難である。

- **AccessibilityService 単独**: ユーザー補助画面をリアルタイムで検知して HOME に戻せるが、ユーザー補助設定画面から自分自身を無効化できてしまう（鶏と卵問題）。
- **Device Owner (DPC) 単独**: アンインストール禁止や `setPermittedAccessibilityServices` で保護できるが、リアルタイムのブロック（画面遷移の即時検知→阻止）ができない。

## Decision

AccessibilityService（Layer 1）と Device Owner（Layer 2）を組み合わせた二重防御を採用する。

| レイヤー | 技術 | 役割 |
|---------|------|------|
| Layer 1 | AccessibilityService | 設定アプリのユーザー補助画面を検知し、即座に HOME に戻す（即時性） |
| Layer 2 | Device Owner (DPC) | アンインストール禁止、AccessibilityService の自動復旧、許可サービス制限（耐久性） |
| 補助 | WatchdogWorker | WorkManager で 15 分毎にサービス状態を確認・復旧（最終防衛線） |

### 防御フロー

```
ユーザーがユーザー補助設定を開く
  → AccessibilityService が TYPE_WINDOW_STATE_CHANGED を検知
  → ConditionChecker で許可条件を確認
  → 条件外なら GLOBAL_ACTION_HOME で HOME に戻す

AccessibilityService が無効化された場合
  → WatchdogWorker (15分毎) が検知
  → Device Owner 権限で setSecureSetting により再有効化

アプリがアンインストールされそうな場合
  → Device Owner の setUninstallBlocked で阻止
```

## Consequences

### Positive

- 片方が突破されても、もう片方で復旧できる多層防御が実現
- AccessibilityService のリアルタイム性と Device Owner の耐久性が相互補完
- WatchdogWorker が最終防衛線として 15 分以内にサービス状態を復旧

### Negative

- `dpm set-device-owner` のセットアップが煩雑（ADB 必須、端末にアカウント 1 つのみの制約）
- Device Owner はファクトリーリセットでしか完全解除できないため、緊急時の脱出手順を別途用意する必要がある（→ ADR-0002）
- テスト環境の構築が複雑（Device Owner + AccessibilityService の両方が前提の結合テストは CI で実行困難）

### Risks

- `setSecureSetting` による AccessibilityService の復旧は一部デバイス/バージョンで制限される場合がある（`WRITE_SECURE_SETTINGS` の付与で回避可能）
- WatchdogWorker の 15 分間隔は WorkManager の最小周期制約による。この間は AccessibilityService が無効な状態が発生し得る

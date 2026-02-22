# ADR-0005: CI/ローカルでのデバッグ機能制御

- **Status:** Accepted
- **Date:** 2025-02-22
- **Deciders:** @ozawa

## Context

Device Owner の解除ボタンはローカル開発時のデバッグに不可欠だが、CI でビルドした APK（配布用）に含まれていると、セルフコントロールの目的を損なう。

- ユーザー（＝自分自身）が衝動的に Device Owner を解除するのを防ぎたい
- しかしローカルで開発・テスト中は解除ボタンがないと署名不一致時に端末が詰む
- release ビルドには当然含めない
- **CI の debug ビルドにも含めたくない**（自分が CI でビルドした APK を端末に入れて使うため）

### 検討した代替案

| 案 | 概要 | 却下理由 |
|----|------|---------|
| Build Variant（flavor）で分離 | `devDebug` / `prodDebug` を定義 | プロジェクトが小規模で flavor は過剰。ビルドバリアント爆発 |
| Gradle property (`-PDEBUG_FEATURES=true`) | コマンドラインで渡す | 忘れやすい。CI の yaml にも明示的に書く必要がある |
| 環境変数 `CI` を参照 | GitHub Actions が `CI=true` を自動設定 | 追加設定不要で最もシンプル → **採用** |

## Decision

`BuildConfig.ALLOW_DEBUG_FEATURES` フラグを環境変数 `CI` で制御する。

### build.gradle.kts

```kotlin
buildTypes {
    debug {
        val isCI = System.getenv("CI") == "true"
        buildConfigField("boolean", "ALLOW_DEBUG_FEATURES", if (isCI) "false" else "true")
    }
    release {
        buildConfigField("boolean", "ALLOW_DEBUG_FEATURES", "false")
    }
}
```

### UI での利用

```kotlin
// Device Owner 解除ボタン（ローカルビルドのみ表示）
if (isDeviceOwner && BuildConfig.ALLOW_DEBUG_FEATURES) {
    // 解除ボタンを表示
}
```

### 結果のマトリクス

| ビルド環境 | debug | release |
|-----------|-------|---------|
| ローカル (`CI` 未設定) | `true` — 解除ボタン表示 | `false` |
| CI (`CI=true`) | `false` — 解除ボタン非表示 | `false` |

## Consequences

### Positive

- GitHub Actions は `CI=true` を自動設定するため、CI 側の追加設定が一切不要
- `BuildConfig` はコンパイル時定数なので、R8/ProGuard でデッドコード除去される
- 将来デバッグ機能が増えても同じフラグで制御可能

### Negative

- ローカルで `CI=true` を設定するとデバッグ機能が無効になる（意図しない挙動）
- `System.getenv` は Gradle の設定フェーズで評価されるため、環境変数を変更したらクリーンビルドが必要

### 緊急時の代替手段

CI ビルドの APK がインストールされた状態で Device Owner を解除する必要がある場合は、JDWP デバッガ経由で `clearDeviceOwnerApp()` を直接実行する。手順は CLAUDE.md に記載。

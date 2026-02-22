# ADR-0006: デバッグ署名鍵の共有戦略

- **Status:** Accepted
- **Date:** 2025-02-22
- **Deciders:** @ozawa

## Context

Android APK はインストール済みアプリと署名が一致しないと上書きインストールできない（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。

CI（GitHub Actions）とローカルでデフォルトの debug keystore が異なるため、以下の問題が発生した:

1. CI でビルドした APK を端末にインストール
2. ローカルでコード修正、ビルド
3. `adb install -r` が署名不一致で失敗
4. Device Owner が設定されているためアンインストールもできない（`DELETE_FAILED_DEVICE_POLICY_MANAGER`）
5. JDWP で Device Owner を解除してからアンインストール → 再インストールの手間が発生

### 検討した代替案

| 案 | 概要 | 却下理由 |
|----|------|---------|
| CI で Android SDK のデフォルト keystore を使う | `~/.android/debug.keystore` を CI に持ち込む | CI 環境で毎回異なる可能性がある。制御困難 |
| release keystore を使う | 開発でも release 署名 | release keystore は Secrets 管理が必要。ローカル開発が煩雑 |
| GitHub Secrets に keystore を格納 | Base64 エンコードして Secrets に入れる | debug 用途には過剰。keystore をデコードする CI ステップが増える |
| プロジェクト専用 debug keystore をリポジトリにコミット | 全環境で同じ鍵を使う → **採用** | |

## Decision

プロジェクト専用の debug keystore を生成し、リポジトリにコミットする。

### 生成コマンド

```bash
keytool -genkey -v \
  -keystore app/debug.keystore \
  -alias debug \
  -keyalg RSA -keysize 2048 \
  -validity 36500 \
  -storepass android -keypass android \
  -dname "CN=AccessibilityGuard Debug,O=Debug,C=JP"
```

### build.gradle.kts

```kotlin
signingConfigs {
    getByName("debug") {
        storeFile = file("debug.keystore")
        storePassword = "android"
        keyAlias = "debug"
        keyPassword = "android"
    }
}
```

### .gitignore

```
# Android keystore
*.keystore
*.jks
# ただしデバッグ用はコミット対象
!app/debug.keystore
```

## Security Considerations

**Q: Public リポジトリに keystore をコミットして問題ないか？**

問題ない。理由:

1. **debug keystore の用途**: 開発・テスト専用。Google Play への公開には使えない
2. **パスワードは公開情報**: Android SDK のデフォルト debug keystore もパスワード `android` で広く知られている
3. **漏洩しても影響なし**: debug keystore で署名された APK は端末にサイドロードできるだけ。Store 配布不可
4. **release keystore は別管理**: release ビルドの署名は別途 keystore を作成し、GitHub Secrets で管理する（未実装）

**Note**: release keystore は絶対にコミットしないこと。

## Consequences

### Positive

- ローカル/CI で同じ署名になるため、APK の上書きインストールが常に成功
- Device Owner 設定中でも APK 更新が可能（署名一致のため）
- CI の設定変更不要（keystore がリポジトリにあるので自動的に使われる）

### Negative

- debug keystore をリポジトリにコミットするのは一般的なベストプラクティスからは外れる（ただしセキュリティリスクは実質ゼロ）
- keystore の有効期限が 100 年（36500 日）なので期限切れの心配は不要だが、鍵アルゴリズムの陳腐化の可能性はある

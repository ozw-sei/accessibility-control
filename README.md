# Accessibility Guard

Freedom などのアプリブロッカーを「ユーザー補助 → 無効化」で回避してしまう問題を防ぐ Android アプリ。

**指定した時間帯 + 充電中** の条件を満たす場合のみ、ユーザー補助設定へのアクセスを許可する。

---

## アーキテクチャ

```
┌────────────────────────────────────────────────┐
│               AccessibilityService              │
│  (設定アプリのユーザー補助画面を検知→HOME に戻す)  │
│  ↑ 無効化されたら Watchdog が復旧               │
├────────────────────────────────────────────────┤
│               Device Owner (DPC)                │
│  ・アプリのアンインストールを禁止                  │
│  ・許可する AccessibilityService を制限           │
│  ・AccessibilityService の自動復旧               │
├────────────────────────────────────────────────┤
│              WatchdogWorker (15分毎)             │
│  ・AccessibilityService の状態チェック            │
│  ・無効なら Device Owner 権限で再有効化            │
├────────────────────────────────────────────────┤
│              ConditionChecker                   │
│  ・時間帯チェック (デフォルト 06:00〜08:00)        │
│  ・充電状態チェック                               │
│  ・両方を満たす場合のみアクセスを許可               │
└────────────────────────────────────────────────┘
```

### 防御の多層構造

| レイヤー | 役割 | 回避方法 |
|---------|------|---------|
| AccessibilityService | ユーザー補助設定画面を検知→HOME | Force-stop |
| Device Owner | アンインストール禁止・サービス復旧 | Factory reset / ADB |
| WatchdogWorker | 15分毎にサービス状態チェック | なし (システムが管理) |
| BootReceiver | 再起動後に復旧 | なし |

**意図的な脱出口:**
- ADB (`adb shell dpm remove-active-admin ...`) → PCが必要
- Factory reset → 全データ消えるので抑止力あり
- 許可ウィンドウ内 → 設定変更可能

---

## セットアップ手順

### 前提条件

- Android Studio Hedgehog (2023.1.1) 以上
- Android 9 (API 28) 以上の実機
  - ※ Pixel 9 Pro Fold で動作確認想定
- ADB が使える PC
- 端末に Google アカウントが **1つだけ** 登録されている状態
  - Device Owner 設定にはアカウント条件がある

### Step 1: プロジェクトのビルド

```bash
# プロジェクトを Android Studio で開く
# または CLI でビルド
cd accessibility-guard
./gradlew assembleDebug
```

### Step 2: インストール

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Step 3: Device Owner の設定

**重要:** Device Owner を設定する前に、端末のアカウント状態を確認:

```bash
# 現在のアカウント数を確認
adb shell dumpsys account | grep -c "Account {"

# 既存の Device Owner がいないか確認
adb shell dumpsys device_policy | grep "Device Owner"
```

**Device Owner の設定が失敗する場合（よくある原因）:**

1. **端末に複数のアカウントがある** → 設定 > アカウント から Google 以外のアカウントを一時削除
2. **既に Device Owner が設定されている** → `adb shell dpm remove-active-admin` で先に解除
3. **Android 初期設定ウィザードが完了していない** → 完了させる

```bash
# Device Owner を設定
adb shell dpm set-device-owner com.example.accessibilityguard/.GuardAdminReceiver
```

成功すると以下が表示される:
```
Success: Device owner set to package com.example.accessibilityguard
Active admin set to component {com.example.accessibilityguard/com.example.accessibilityguard.GuardAdminReceiver}
```

### Step 4: AccessibilityService の有効化

1. アプリを起動
2. 「ユーザー補助設定を開く」ボタンをタップ
3. 「Accessibility Guard」を見つけて有効化
4. アプリに戻り、ステータスが全て ✅ になっていることを確認

### Step 5: Freedom の AccessibilityService も許可リストに入っていることを確認

`DeviceOwnerHelper.kt` の `restrictAccessibilityServices()` で Freedom のパッケージ名が正しいことを確認:

```kotlin
// Freedom のパッケージ名を確認
adb shell pm list packages | grep freedom
```

デフォルトでは `to.freedom.android2` を設定済み。異なる場合はコードを修正。

---

## 動作確認

### テスト 1: 基本ブロック確認

1. **許可ウィンドウ外** であることを確認（充電を外す or 時間外）
2. 設定 > ユーザー補助 を開こうとする
3. **期待結果:** 即座に HOME に戻され、Toast が表示される

```
🔒 ユーザー補助設定はロック中
許可: 06:00〜08:00 (充電中)
```

### テスト 2: 許可ウィンドウでのアクセス

1. 許可ウィンドウ内にする（テスト時は時間を広げておくと楽）
   - アプリで開始時刻/終了時刻を調整
   - 充電器を接続
2. 設定 > ユーザー補助 を開く
3. **期待結果:** 正常にアクセスできる

### テスト 3: 設定検索からの回避を阻止

1. 許可ウィンドウ外にする
2. 設定アプリを開く
3. 上部の検索バーに「ユーザー補助」と入力
4. 検索結果をタップ
5. **期待結果:** ユーザー補助画面が開こうとした瞬間に HOME に戻される

### テスト 4: Device Owner の保護確認

```bash
# アンインストールが拒否されることを確認
adb shell pm uninstall com.example.accessibilityguard
# → Failure [DELETE_FAILED_DEVICE_POLICY_MANAGER]

# 設定 > アプリ > Accessibility Guard からもアンインストール不可を確認
```

### テスト 5: AccessibilityService の自動復旧

```bash
# AccessibilityService の現在の状態を確認
adb shell settings get secure enabled_accessibility_services

# テスト: AccessibilityService を手動で無効化
adb shell settings put secure enabled_accessibility_services ""
adb shell settings put secure accessibility_enabled 0

# 15分以内に WatchdogWorker が復旧するか確認
# すぐ確認したい場合は WorkManager のテスト実行:
adb shell am broadcast -a "com.example.accessibilityguard.FORCE_WATCHDOG" \
  --receiver-permission android.permission.BIND_DEVICE_ADMIN

# または logcat で監視
adb logcat -s WatchdogWorker GuardA11y DeviceOwnerHelper
```

### テスト 6: 再起動後の復旧

```bash
adb reboot

# 起動完了後に確認
adb shell settings get secure enabled_accessibility_services
# → AccessibilityGuard が含まれていること

adb logcat -s BootReceiver WatchdogWorker
# → BootReceiver が発火し Watchdog が再スケジュールされること
```

### テスト 7: Force-stop からの復旧

```bash
# Force-stop を実行
adb shell am force-stop com.example.accessibilityguard

# AccessibilityService が停止していることを確認
adb shell dumpsys accessibility | grep GuardAccessibilityService
# → 表示されない

# WatchdogWorker が最大15分以内に復旧することを確認
# (WorkManager は force-stop 後も再実行される)
adb logcat -s WatchdogWorker
```

---

## ログの確認方法

```bash
# 全てのコンポーネントのログを表示
adb logcat -s GuardA11y GuardAdmin DeviceOwnerHelper WatchdogWorker BootReceiver

# AccessibilityService のイベントログのみ
adb logcat -s GuardA11y

# Device Owner 関連のみ
adb logcat -s DeviceOwnerHelper
```

---

## トラブルシューティング

### Q: `dpm set-device-owner` が失敗する

```
java.lang.IllegalStateException: Not allowed to set the device owner
because there are already some accounts on the device
```

**対処:**
```bash
# 端末のアカウントを一時的に削除してから再実行
# 設定 > アカウント > 各アカウントを削除

# 再実行
adb shell dpm set-device-owner com.example.accessibilityguard/.GuardAdminReceiver

# 成功後にアカウントを再追加
```

### Q: AccessibilityService が `setSecureSetting` で復旧しない

一部のデバイス/Android バージョンでは `setSecureSetting` で `ENABLED_ACCESSIBILITY_SERVICES` の書き込みが制限される場合がある。

**対処（代替手段）:**
```bash
# WRITE_SECURE_SETTINGS を付与
adb shell pm grant com.example.accessibilityguard android.permission.WRITE_SECURE_SETTINGS
```

付与後は `Settings.Secure.putString()` でも書き込み可能になるので、
`DeviceOwnerHelper.kt` にフォールバック処理を追加:

```kotlin
// setSecureSetting が失敗した場合のフォールバック
try {
    Settings.Secure.putString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        newValue
    )
} catch (e: SecurityException) {
    Log.e(TAG, "Fallback also failed", e)
}
```

### Q: Samsung / Xiaomi でブロックが効かない

OEM によってユーザー補助設定のアクティビティ名が異なる。
`GuardAccessibilityService.kt` の `BLOCKED_CLASS_PATTERNS` にデバイス固有のクラス名を追加:

```bash
# 設定アプリのアクティビティ名を調べる
adb shell dumpsys activity activities | grep -i accessibility
```

### Q: Device Owner を解除したい

```bash
# ADB から解除
adb shell dpm remove-active-admin com.example.accessibilityguard/.GuardAdminReceiver

# またはアプリの UI から「Device Owner を解除」ボタン
# (許可ウィンドウ内のみ操作可能)
```

---

## リリース

Obtainium は GitHub Releases を追う。タグを push すると、署名済みの release APK が1つと、その `.sha256` が Release に付く。

このアプリは本番アプリ。

| タグ | GitHub Release |
|------|----------------|
| `vX.Y.Z`（例: `v1.2.3`） | 通常の Release |
| `vX.Y.Z-beta.N`（例: `v1.2.3-beta.1`、N は 0〜98） | prerelease |

```bash
git tag v1.2.3
git push origin v1.2.3
```

タグのコミットはデフォルトブランチの履歴に含まれている必要がある。含まれていないとワークフローは失敗する。

versionName はタグから先頭の `v` を除いた文字列（`1.2.3`、beta なら `1.2.3-beta.1`）。versionCode は `X*1000000 + Y*10000 + Z*100 + 99` で、beta のときは 99 の代わりに N。CI が `-PversionName` と `-PversionCode` を渡す。手元のビルドは versionName `1.0.0`、versionCode `1` のまま。

添付は次の2ファイルだけ。リリースノートは `gh release create --generate-notes` で作る。

- `accessibility-control-v1.2.3.apk`
- `accessibility-control-v1.2.3.apk.sha256`

署名鍵はコミット済みの `app/debug.keystore` のまま。release ビルド種別もこの鍵で署名する。このアプリは Device Owner として端末に入っているので、鍵を別の keystore に差し替えると署名が一致せず上書きインストールできず、入れ直すには Device Owner の解除が必要になる。そのため `ANDROID_KEYSTORE_BASE64` などの secrets には切り替えず、この鍵を使い続ける。

公開 APK は release ビルド種別で、`ALLOW_DEBUG_FEATURES` は false。Device Owner の解除ボタンは出ない。

## CI/CD (GitHub Actions)

### パイプライン構成

```
push/PR → [Unit Tests] → [Lint] → [Build APK] → Actions artifacts
tag v*  → [タグ検証] → [Unit Tests] [Lint] → [署名済み release APK] → GitHub Release
```

| ジョブ | 内容 | 成果物 |
|-------|------|--------|
| `version-script` | タグから versionName / versionCode への変換をチェック | なし |
| `unit-test` | `./gradlew testDebugUnitTest` | HTML テストレポート |
| `lint` | `./gradlew lintDebug` | Lint レポート |
| `build` | debug APK と、`debug.keystore` で署名した release APK | Actions artifact |
| `release`（`release.yml`、タグのみ） | `accessibility-control-<tag>.apk` と `.sha256` | GitHub Release |

### APK のダウンロード

配布用 APK は GitHub の **Releases** にある。Obtainium もここを登録する。

PR やブランチのビルドは Actions の Artifacts（`apk-debug-{sha}` / `apk-release-{sha}`）に出る。タグから計算した versionName / versionCode が付くのは Releases の APK だけ。

### 初回 Git リポジトリセットアップ

```bash
cd accessibility-guard

# Gradle Wrapper を生成（CI と local 両方で必要）
chmod +x setup.sh && ./setup.sh

# Git 初期化
git init
git add .
git commit -m "Initial commit"

# GitHub リポジトリに push
git remote add origin git@github.com:<user>/accessibility-guard.git
git branch -M main
git push -u origin main
```

> **Note:** `setup.sh` が `gradle/wrapper/gradle-wrapper.jar` と `gradlew` を生成します。
> これらは Git にコミットしてください（CI が依存します）。

---

## カスタマイズ

### Freedom 以外のブロッカーにも対応

`DeviceOwnerHelper.kt` の `restrictAccessibilityServices()` に
対象アプリのパッケージ名を追加:

```kotlin
val allowedPackages = listOf(
    context.packageName,
    "to.freedom.android2",        // Freedom
    "com.yourapp.blocker",        // 追加したいブロッカー
)
```

### ブロック条件のカスタマイズ

`ConditionChecker.kt` の `isAllowed()` を修正して条件を変更可能:

```kotlin
// 例: 平日のみ許可
fun isAllowed(): Boolean {
    val dayOfWeek = LocalDate.now().dayOfWeek
    val isWeekday = dayOfWeek != DayOfWeek.SATURDAY && dayOfWeek != DayOfWeek.SUNDAY
    // ... 既存の条件と組み合わせ
}
```

---

## ファイル構成

```
accessibility-guard/
├── .github/
│   ├── scripts/
│   │   ├── release-version.sh       # タグ → versionName / versionCode
│   │   └── release-version.test.sh
│   └── workflows/
│       ├── build.yml                # PR / push のテストと APK
│       └── release.yml              # タグで GitHub Release
├── .gitignore
├── setup.sh                         # 初回セットアップスクリプト
├── build.gradle.kts                 # プロジェクトレベル
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/
│   └── gradle-wrapper.properties
├── README.md
│
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   ├── java/com/example/accessibilityguard/
        │   │   ├── MainActivity.kt              # Compose UI
        │   │   ├── GuardAdminReceiver.kt        # Device Owner Receiver
        │   │   ├── GuardAccessibilityService.kt # 設定画面ブロック
        │   │   ├── DeviceOwnerHelper.kt         # Device Owner ユーティリティ
        │   │   ├── ConditionChecker.kt          # 時間帯・充電条件
        │   │   ├── SettingsDetector.kt          # パターンマッチング
        │   │   ├── WatchdogWorker.kt            # 15分毎の状態チェック
        │   │   └── BootReceiver.kt              # 起動時復旧
        │   └── res/
        │       ├── xml/
        │       │   ├── device_admin.xml
        │       │   └── accessibility_service_config.xml
        │       └── values/strings.xml
        │
        ├── test/  (ローカル単体テスト)
        │   └── java/com/example/accessibilityguard/
        │       ├── ConditionCheckerTest.kt      # 30+ tests
        │       ├── SettingsDetectorTest.kt      # 30+ tests
        │       ├── DeviceOwnerHelperTest.kt     # 10+ tests
        │       └── WatchdogWorkerTest.kt        # 3 tests
        │
        └── androidTest/  (結合テスト)
            └── java/com/example/accessibilityguard/
                ├── AccessibilityBlockingTest.kt # UIAutomator
                └── MainActivityUiTest.kt        # Compose UI
```

---

## テスト

### テスト構成

| 種別 | ファイル | テスト数 | 内容 |
|------|---------|---------|------|
| 単体テスト | `ConditionCheckerTest` | 30+ | 時間ウィンドウ判定、充電条件、ガード有効/無効、境界値、SharedPreferences 永続化 |
| 単体テスト | `SettingsDetectorTest` | 30+ | クラス名パターンマッチ (AOSP/Pixel/Samsung/Xiaomi)、タイトルパターン (日/英)、非ブロック画面の安全性 |
| 単体テスト | `DeviceOwnerHelperTest` | 10+ | Device Owner 判定、アンインストールブロック、AccessibilityService 制限、解除 |
| 単体テスト | `WatchdogWorkerTest` | 3 | Worker の成功/失敗、ガード無効時の挙動 |
| 結合テスト | `AccessibilityBlockingTest` | 4 | 実機での画面ブロック、ガード無効時のアクセス、設定トップ画面非ブロック |
| UI テスト | `MainActivityUiTest` | 8 | Compose UI の各コンポーネント表示確認 |

### ローカル単体テスト (JVM)

PC上で実行。実機不要。Robolectric を使用。

```bash
# 全てのローカルテスト実行
./gradlew testDebugUnitTest

# 特定のテストクラスのみ
./gradlew testDebugUnitTest --tests "*.ConditionCheckerTest"
./gradlew testDebugUnitTest --tests "*.SettingsDetectorTest"
./gradlew testDebugUnitTest --tests "*.DeviceOwnerHelperTest"
./gradlew testDebugUnitTest --tests "*.WatchdogWorkerTest"

# テスト結果 (HTML)
open app/build/reports/tests/testDebugUnitTest/index.html
```

### 結合テスト (実機 / エミュレータ)

**前提条件:**
- Device Owner 設定済み
- AccessibilityService 有効化済み

```bash
# 全ての結合テスト実行
./gradlew connectedDebugAndroidTest

# AccessibilityService のブロックテストのみ
adb shell am instrument -w \
  -e class com.example.accessibilityguard.AccessibilityBlockingTest \
  com.example.accessibilityguard.test/androidx.test.runner.AndroidJUnitRunner

# Compose UI テストのみ
adb shell am instrument -w \
  -e class com.example.accessibilityguard.MainActivityUiTest \
  com.example.accessibilityguard.test/androidx.test.runner.AndroidJUnitRunner

# テスト結果 (HTML)
open app/build/reports/androidTests/connected/index.html
```

### テスト設計のポイント

**ConditionChecker のテスタビリティ:**
- `Clock` をコンストラクタインジェクション → 任意の時刻でテスト可能
- `ChargingProvider` インターフェース → 充電状態をモック可能
- SharedPreferences は Robolectric が提供するインメモリ実装を使用

**SettingsDetector のテスタビリティ:**
- Android 依存ゼロの pure Kotlin object → 普通の JUnit テスト
- `GuardAccessibilityService` からパターンマッチロジックを分離

**DeviceOwnerHelper のテスタビリティ:**
- Robolectric の `ShadowDevicePolicyManager` で Device Owner をシミュレート
- `setDeviceOwner()` / `isUninstallBlocked()` 等を検証

**結合テストの注意点:**
- `AccessibilityBlockingTest` は許可ウィンドウを 03:00〜03:01 に設定して強制ブロック
- Toast の検出は端末依存のため soft assertion
- テスト後にプリファレンスをクリーンアップ

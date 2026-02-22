# CLAUDE.md — Accessibility Guard

## プロジェクト概要

Android の Freedom（アプリブロッカー）を「設定 > ユーザー補助 > 無効化」で回避してしまう自分自身の行動を防ぐためのセルフコントロールアプリ。

**コアコンセプト:** 指定した時間帯（デフォルト 06:00〜08:00）かつ充電中のときだけユーザー補助設定へのアクセスを許可し、それ以外の時間はブロックする。完全にロックアウトするのではなく「面倒くさいバリア」を作ることで衝動的なスリップを防ぐ設計。

## 設計意図

### なぜ二重防御か

| レイヤー | 技術 | 役割 | 単独での弱点 |
|---------|------|------|-------------|
| Layer 1 | AccessibilityService | 設定アプリのユーザー補助画面を検知 → HOME に戻す | ユーザー補助設定から自分自身を無効化できる（鶏と卵問題） |
| Layer 2 | Device Owner (DPC) | アンインストール禁止、サービス自動復旧、許可サービス制限 | 単独ではリアルタイムにブロックできない（検知が遅い） |

両方を組み合わせることで：
- AccessibilityService がリアルタイムにブロック（即時性）
- Device Owner が AccessibilityService の無効化を検知・復旧（耐久性）
- WatchdogWorker が 15 分毎にサービス状態を確認（最終防衛線）

### 意図的な脱出口

完全なロックアウトはユーザビリティとして危険なので、意図的に以下の脱出口を残している：
- **許可ウィンドウ内**での設定変更（朝の冷静な時間帯に操作）
- **ADB 接続**での Device Owner 解除（PCが必要 = 衝動的にはやりにくい）
- **Factory reset**（データ消失が抑止力）

### ConditionChecker のテスタビリティ設計

`Clock` と `ChargingProvider` をコンストラクタインジェクションにして、テスト時に任意の時刻・充電状態を注入できるようにした。SharedPreferences は Robolectric のインメモリ実装で自動的にテスト可能。

### SettingsDetector の分離

`GuardAccessibilityService` からパターンマッチングロジックを `SettingsDetector` に抽出。AccessibilityService は Android フレームワークに強く依存するため直接テストしにくいが、判定ロジック自体は pure Kotlin として JVM 上でテスト可能にした。

### OEM 対応

ユーザー補助設定画面のアクティビティ名は OEM によって異なる。`SettingsDetector.BLOCKED_CLASS_PATTERNS` で AOSP/Pixel、Samsung OneUI、Xiaomi MIUI をカバーしている。ターゲット端末は Pixel 9 Pro Fold なので AOSP パターンが主。

## アーキテクチャ

```
MainActivity (Compose UI)
    ↕ SharedPreferences
ConditionChecker ← Clock, ChargingProvider (DI)
    ↑
GuardAccessibilityService → SettingsDetector (パターンマッチ)
    ↑ 復旧
DeviceOwnerHelper (DPM ユーティリティ)
    ↑ 定期チェック
WatchdogWorker (WorkManager, 15分毎)
    ↑ 起動時トリガー
BootReceiver
```

## 技術スタック

- Kotlin 1.9.22 / Java 17
- Jetpack Compose (Material3) — UI
- WorkManager — 定期バックグラウンドタスク
- Robolectric 4.11.1 — ローカル単体テスト
- UIAutomator — 結合テスト
- GitHub Actions — CI/CD
- ターゲット: Android 9+ (API 28+), compileSdk 34

## ファイル責務一覧

| ファイル | 責務 |
|---------|------|
| `ConditionChecker.kt` | 時間帯・充電条件の判定。SharedPreferences で設定を永続化 |
| `SettingsDetector.kt` | クラス名・タイトルのパターンマッチング（pure Kotlin） |
| `GuardAccessibilityService.kt` | 設定アプリ監視、条件外なら HOME に戻す |
| `GuardAdminReceiver.kt` | Device Owner の DeviceAdminReceiver |
| `DeviceOwnerHelper.kt` | DPM 操作のユーティリティ（アンインストール禁止、サービス復旧等） |
| `WatchdogWorker.kt` | 15分毎に AccessibilityService の状態を確認・復旧 |
| `BootReceiver.kt` | 端末起動時に Watchdog 再登録・サービス復旧 |
| `MainActivity.kt` | Compose UI（ステータス表示・設定変更） |

## 残タスク

### 優先度: 高

- [ ] **Gradle Wrapper の生成と初回ビルド確認**
  - `setup.sh` を実行して `gradlew` / `gradle-wrapper.jar` を生成
  - `./gradlew assembleDebug` でビルドが通ることを確認
  - `./gradlew testDebugUnitTest` でテストが全て通ることを確認
  - 必要に応じてライブラリバージョンの不整合を修正

- [ ] **Pixel 9 Pro Fold での実機検証**
  - Device Owner 設定 (`adb shell dpm set-device-owner ...`)
  - AccessibilityService 有効化
  - ユーザー補助設定のブロック動作確認
  - `adb shell dumpsys activity activities | grep -i accessibility` で実際のアクティビティ名を確認し、`SettingsDetector.BLOCKED_CLASS_PATTERNS` に過不足がないか確認

- [ ] **Freedom のパッケージ名確認**
  - `adb shell pm list packages | grep freedom` で実際のパッケージ名を取得
  - `DeviceOwnerHelper.restrictAccessibilityServices()` のデフォルト値 `to.freedom.android2` が正しいか確認、違えば修正

### 優先度: 中

- [ ] **通知チャネルの実装**
  - `strings.xml` に通知チャネル定義済みだが、実際の `NotificationChannel` 作成コードが未実装
  - ガードの稼働状態を常駐通知で表示するとユーザーに安心感がある
  - `MainActivity.onCreate()` か `Application.onCreate()` で作成

- [ ] **アプリアイコンの作成**
  - 現在デフォルトの `ic_launcher` を参照しているが未作成
  - 🔒 シールドのようなアイコンが適切
  - `res/mipmap-*` にアダプティブアイコンを配置

- [ ] **設定変更もガード対象にする**
  - 現在、アプリの設定画面（許可ウィンドウ変更等）は許可ウィンドウ外でも開ける
  - `MainActivity` 自体も許可ウィンドウ外ではウィンドウ変更を UI で disable にしているが、Intent で直接起動される場合のガードが甘い
  - `MainActivity.onCreate()` で `ConditionChecker.isAllowed()` をチェックし、false なら設定タブを非表示にするか finish() する

- [ ] **release 署名の設定**
  - keystore を作成して `app/build.gradle.kts` に signingConfigs を追加
  - GitHub Actions では Secrets に格納して CI で署名付きビルド
  - 現状は unsigned release APK のみ出力

- [ ] **ProGuard / R8 の設定確認**
  - `proguard-rules.pro` に最低限の keep ルールは書いてあるが、release ビルドで `isMinifyEnabled = true` にしたときに問題がないか確認
  - Compose / WorkManager / AccessibilityService 関連の keep ルールが足りているか

### 優先度: 低

- [ ] **曜日条件の追加**
  - `ConditionChecker` に曜日フィルタを追加（例: 平日のみ許可）
  - UI にも曜日選択を追加

- [ ] **ブロックログの記録**
  - ブロックした日時をローカル DB (Room) に記録
  - 「いつスリップしそうになったか」の傾向分析に使える
  - UI にログビューアーを追加

- [ ] **Widget の追加**
  - ホーム画面にガード状態を表示する Glance Widget
  - ON/OFF 切り替えは許可ウィンドウ内のみ動作

- [ ] **結合テストの安定化**
  - `AccessibilityBlockingTest` は Device Owner + AccessibilityService が前提なので CI では実行できない
  - エミュレータベースの CI を追加するか、結合テストを手動テスト扱いにするか決める
  - Toast 検出は端末依存で不安定なので代替検証方法を検討

- [ ] **多言語対応**
  - 現在は日本語ハードコード + 英語パターン
  - `strings.xml` の英語版 (`values-en`) を追加
  - SettingsDetector のパターンも他言語のユーザー補助画面名を追加

- [ ] **Compose UI のリファクタリング**
  - `MainActivity.kt` が 300 行超で肥大気味
  - ViewModel の導入（`GuardViewModel`）
  - 状態管理を `StateFlow` に統一
  - Compose Preview の追加

## コマンドリファレンス

```bash
# ビルド
./gradlew assembleDebug
./gradlew assembleRelease

# テスト
./gradlew testDebugUnitTest                           # 全ローカルテスト
./gradlew testDebugUnitTest --tests "*.ConditionCheckerTest"  # 特定クラス
./gradlew connectedDebugAndroidTest                   # 結合テスト (実機)

# Lint
./gradlew lintDebug

# Device Owner
adb shell dpm set-device-owner com.example.accessibilityguard/.GuardAdminReceiver
adb shell dpm remove-active-admin com.example.accessibilityguard/.GuardAdminReceiver

# デバッグ
adb logcat -s GuardA11y GuardAdmin DeviceOwnerHelper WatchdogWorker BootReceiver
adb shell settings get secure enabled_accessibility_services
adb shell dumpsys activity activities | grep -i accessibility
adb shell pm list packages | grep freedom
```

## 緊急時の Device Owner 解除方法

CI ビルド（debug/release 共に）では `BuildConfig.ALLOW_DEBUG_FEATURES = false` となり、UI からの Device Owner 解除ボタンが非表示になる。以下の方法で緊急解除が可能。

### 方法1: ローカルビルドに差し替える（推奨）

ローカルビルドでは `ALLOW_DEBUG_FEATURES = true` なので UI に解除ボタンが表示される。ただし署名が異なると上書きインストールできないため、先に JDWP で Device Owner を解除してからアンインストール → ローカルビルドをインストールする流れになる。

### 方法2: JDWP デバッガ（jdb）で直接解除

アプリプロセスにデバッガを接続し、`clearDeviceOwnerApp()` を実行する。PC（ADB 接続）が必要。

```bash
# 1. アプリの PID を取得
adb shell pidof com.example.accessibilityguard

# 2. JDWP ポートフォワード
adb forward tcp:8700 jdwp:<PID>

# 3. jdb で接続し、ブレークポイントを設定
jdb -connect com.sun.jdi.SocketAttach:hostname=localhost,port=8700

# 4. jdb プロンプトで以下を実行
stop in com.example.accessibilityguard.GuardAccessibilityService.onAccessibilityEvent
resume

# 5. 端末で設定アプリを開いてブレークポイントにヒットさせる
#    （別ターミナルで）
adb shell am start -a android.settings.SETTINGS

# 6. ヒット後、jdb プロンプトで eval を実行
eval ((android.app.admin.DevicePolicyManager)this.getSystemService("device_policy")).clearDeviceOwnerApp("com.example.accessibilityguard")

# 7. <void value> が返れば成功。アプリをアンインストール可能になる
adb uninstall com.example.accessibilityguard
```

### 方法3: Factory Reset

最終手段。端末の全データが消去される。

## デバッグ機能の制御

`BuildConfig.ALLOW_DEBUG_FEATURES` で制御。環境変数 `CI=true`（GitHub Actions が自動設定）の有無で切り替わる。

| ビルド環境 | debug | release |
|-----------|-------|---------|
| ローカル   | `true` (解除ボタン表示) | `false` |
| CI        | `false` | `false` |

## 注意事項

- `dpm set-device-owner` は端末にアカウントが 1 つだけの状態で実行する必要がある
- AccessibilityService の `setSecureSetting` による復旧は一部デバイスで制限される場合がある。その場合は `adb shell pm grant com.example.accessibilityguard android.permission.WRITE_SECURE_SETTINGS` で回避
- `settings.gradle.kts` の `dependencyResolutionManagement` を使用しているので、`app/build.gradle.kts` に `repositories` ブロックを書かないこと

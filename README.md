# Magisk Wi-Fi Band Preference

Android 13 以降で、同じ SSID を 2.4GHz / 5GHz で出している AP の中から、
端末側で接続するバンドを優先・強制する Magisk / KernelSU モジュールです。
ホテル・公衆 Wi-Fi など、AP 側の設定を変更できない環境を想定しています。

## 特徴

- 2.4GHz / 5GHz の優先 (`prefer`) と強制 (`force`) を切り替えられる
- `auto` で Wi-Fi framework の既定値に戻せる
- 再起動後も `service.sh` が設定を自動で再適用する
- system / vendor ファイルを変更しない
- Magisk / KernelSU のアクションボタンに対応

## 症状・用途

- ホテルや施設で 2.4GHz / 5GHz が同じ SSID で運用されている
- 端末が 2.4GHz に接続して遅い、または 5GHz に固定したい
- Android の標準 UI には「この SSID では 5GHz を使う」という設定がない
- `cmd wifi` にもバンドを指定するコマンドがない

## 動作確認環境

- 端末: Redmi Note 9S (curtana / miatoll)
- ROM: HyperOS ベースの CN ポート ROM (Android 14, UKQ1.230804.001)
- カーネル: kinesis-omega-r7.9 (4.14.357-openela)
- root: Magisk (MagiskSU) + LSPosed
- 検証日: 2026-11-24

**ROM 依存性**: Android 13 (API 33) で追加された `WifiNetworkSelectionConfig`
(SystemApi) を使用します。Android 12 以前や、このクラスを削除・変更している
ROM では使えません。特定の端末専用ではありませんが、ROM によって
**使える場合と使えない場合があります**。

## 仕組み

`WifiBandPref` (dex) を root の `app_process` から実行し、
`WifiNetworkSelectionConfig` を reflection で構築して
`IWifiManager.setNetworkSelectionConfig()` に渡します。

| モード | 2.4GHz | 5GHz |
| --- | --- | --- |
| `auto` | 既定 | 既定 |
| `5g-prefer` | weight LOW (-40) | weight HIGH (+40) |
| `5g-force` | RSSI しきい値で全 AP を除外 | weight HIGH (+40) |
| `2g-prefer` | weight HIGH (+40) | weight LOW (-40) |
| `2g-force` | weight HIGH (+40) | RSSI しきい値で全 AP を除外 |

- RSSI しきい値は `{exit, entry, sufficient, good}` の 4 要素です。
  強制モードでは `{-4, -3, -2, -1}` を設定します。`entry = -3 dBm` より
  RSSI が低い AP は候補から外れるため、実在する AP (おおよそ -30〜-90 dBm) は
  すべて除外されます。
- `auto` は `{0, 0, 0, 0}` を設定して overlay 既定値に戻し、
  frequency weight も空にします。
- frequency weight は MHz 単位の完全一致です。2.4GHz の中心周波数
  (2412 / 2437 / 2462 MHz) を含めるため、1MHz 刻みで設定しています。
- 6GHz は制御対象外です。

設定は Wi-Fi framework のメモリ上にのみ保持されます。再起動で消えるため、
`service.sh` が Wi-Fi 有効化後に保存されたモードを再適用します。

**現在接続中の AP は設定変更では切断されません。** 新しいネットワーク選択
(Wi-Fi オフ/オン、圏外移行、再スキャンなど) から反映されます。

## インストール

1. `dist/wifi_band_preference-v1.0.zip` を端末へコピー
2. Magisk / KernelSU アプリ → モジュール → ストレージからインストール
3. 再起動

初期状態は `auto` です。モードはモジュールディレクトリの `mode` に保存されます。

## 使い方

### アクションボタン

Magisk / KernelSU アプリのモジュール画面のアクションボタンで、
`auto → 5g-force → 2g-force → auto` の順に切り替わります。

### CLI

`5g-prefer` / `2g-prefer` を含む全モードは CLI から指定できます。

```sh
su -c "sh /data/adb/modules/wifi_band_preference/wifi-band-pref 5g-prefer"
```

モードは即座に適用され、次回起動時にも使われます。
現在の接続をすぐ切り替えるには Wi-Fi をオフ/オンしてください。

環境によっては `su` から `/data/adb` が見えないことがあります
(SUSFS やゲーム向け SELinux モジュールなど)。その場合はアクションボタンを使うか、
Magisk のモジュールディレクトリが読めるシェルから実行してください。

### 適用確認

```sh
adb shell dumpsys wifi | grep ScoringParams
```

- `5g-force`: `rssi2=-4:-3:-2:-1`
- `2g-force`: `rssi5=-4:-3:-2:-1`
- `auto`: `rssi2=-83:-80:-73:-60,rssi5=-80:-77:-70:-57` (既定値。ROM により異なる)

`5g-prefer` / `2g-prefer` は frequency weight を使うため、`ScoringParams` には
現れません。`adb logcat -s WifiService` の
`WifiNetworkSelectionConfig=...` で weight を確認できます。

### ログ

`/data/adb/modules/wifi_band_preference/apply.log` に起動時の適用結果が入ります。
`FAILED:` が出ている場合は、その ROM ではこの API が使えません。

## 検証結果

### 強制モード

- `5g-force` 適用後: `ScoringParams: rssi2=-4:-3:-2:-1,...`
- `2g-force` 適用後: `ScoringParams: rssi5=-4:-3:-2:-1,...`
- `auto` 適用後: 既定値に復帰
- `2g-force` を適用して Wi-Fi をオフ/オンすると、5GHz の AP へ自動接続しなくなった。
  2.4GHz 側の自動接続が無効な環境だったため、その時点では未接続だった。
  `auto` に戻すと 5GHz へ再接続した。

### 優先モード

- `5g-prefer` 適用時の logcat で、5GHz (5180 / 5200 / 5220 MHz など) の weight が
  HIGH (1)、2.4GHz (2412 / 2437 / 2462 MHz など) が LOW (0) になっていることを確認。
- `2g-prefer` 適用時の logcat で、2.4GHz の 2412 / 2437 / 2462 MHz が
  HIGH (1)、5GHz が LOW (0) になっていることを確認。
- 同じ SSID の 2.4GHz / 5GHz AP が同時に見える実環境での優先動作は未検証です。
  強制モードは実機で確認済みです。

### 起動時適用

- `mode=5g-force` を含むテスト zip をインストールして再起動。
- 再起動後も `ScoringParams` が `rssi2=-4:-3:-2:-1` になっており、
  `service.sh` による再適用を確認。
- その後 CLI で `auto` に戻し、既定値に復帰することを確認。

## アンインストール

1. Magisk / KernelSU アプリでモジュールを削除
2. 再起動

モードはモジュールディレクトリ内にあるため、モジュールを削除すれば残りません。
framework 側の設定はメモリ上のみなので、再起動で既定値に戻ります。
`auto` に戻してから削除する必要はありません。

## 注意事項

- **ROM 依存性**: Android 13+ の `WifiNetworkSelectionConfig` と
  `IWifiManager.setNetworkSelectionConfig()` が必要です。ROM によっては
  クラス名・メソッド・権限が異なり、`FAILED:` になります。
- **同じ SSID の AP が複数ある場合**: AP 側のバンドステアリング (802.11k/v) や
  同一 BSSID のマルチバンド運用では、端末の設定より AP の誘導が優先される
  ことがあります。
- **5GHz 強制**: 5GHz は 2.4GHz より電波が届きにくいため、部屋の端などで
  切断されやすくなります。
- **2.4GHz 強制**: 6GHz は制御対象外です。6GHz 対応端末では 6GHz が
  残る場合があります。
- **現在の接続**: 設定変更だけでは切断されません。すぐ切り替えるには
  Wi-Fi をオフ/オンしてください。
- **他の Wi-Fi モジュール**: 国コード変更、roaming enhancer、Wi-Fi 7 有効化
  などはスコアや候補に影響します。併用時は動作が変わる場合があります。
- **電池・発熱**: 5GHz を強制すると消費電力が増える場合があります。
- 本モジュールは system / vendor ファイルを変更しません。Wi-Fi framework の
  実行時設定だけを変更します。

## ビルド

```powershell
powershell -File tools/build-module.ps1
```

`dist/wifi_band_preference-v<version>.zip` が生成されます。
PowerShell 7 が入っている場合は `pwsh -File tools/build-module.ps1` でも実行できます。

## リポジトリ構成

```
module/                 Magisk / KernelSU モジュールのソース
  module.prop
  service.sh            起動後の再適用
  action.sh             アクションボタン (auto → 5g-force → 2g-force → auto)
  uninstall.sh          アンインストール時の後始末
  wifi-band-pref        CLI
  wbp.dex               WifiBandPref の dex
src/
  WifiBandPref.java     WifiNetworkSelectionConfig を適用するソース
dist/
  wifi_band_preference-v1.0.zip
tools/
  build-module.ps1      zip ビルドスクリプト
  update-binary.sh      Magisk インストーラ
```

## ライセンス

MIT License. 詳細は `LICENSE` を参照してください。

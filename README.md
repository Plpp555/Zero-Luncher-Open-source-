# Zero Launcher

アイコンを一切表示しない、文字だけの超軽量 Android ホームランチャーです。
黒（または白）一色の背景に、インストール済みアプリの名前を縦に並べ、左右スワイプでページを切り替えます。

- 最低 Android 5.0（API 21）、targetSdk 35
- Java のみ・依存ライブラリ 0・権限 0（ネットワーク権限なし）
- 署名済み APK：**33,163 バイト**（`dist/ZeroLauncher-1.0.apk`）

> **注意（正直な現状）**: この APK は実機・エミュレータでは**まだ一度も起動していません**。
> 開発環境から Android エミュレータ／SDK の配布元（dl.google.com）へ接続できなかったためです。
> 代わりに、実際の Android フレームワークのコードを JVM 上で動かす Robolectric で、
> API 23/26/29/30/35 の 5 バージョンについて動作テスト（計 90 件）を行いました。
> RAM・CPU・起動時間などの性能値は**未測定**で、実機で測るためのスクリプト（`tools/measure.sh`）を同梱しています。
> 詳細は「[6. 動作確認と測定結果](#6-動作確認と測定結果)」と「[7. 未検証事項・既知の制限](#7-未検証事項既知の制限)」を参照してください。

---

## 1. 成果物

| 成果物 | 場所 |
|---|---|
| Android Studio でビルドできるソースコード | リポジトリ全体（`app/`、Gradle Wrapper 付き） |
| インストール可能な APK | `dist/ZeroLauncher-1.0.apk`（SHA-256 は `dist/ZeroLauncher-1.0.apk.sha256`） |
| ビルド手順 | [2. ビルド手順](#2-ビルド手順) |
| 実装した機能の一覧 | [4. 実装した機能](#4-実装した機能) |
| 軽量化のための技術 | [5. 軽量化のために採用した技術](#5-軽量化のために採用した技術) |
| 動作確認・測定結果 | [6. 動作確認と測定結果](#6-動作確認と測定結果) |
| 未検証事項・既知の制限 | [7. 未検証事項・既知の制限](#7-未検証事項既知の制限) |

## 2. ビルド手順

### A. Android Studio / Gradle（通常の方法）

1. Android Studio でこのフォルダを開く（JDK 17 以上。Android Studio 同梱の JBR 21 でよい）。
2. Gradle Sync 後、`Build > Build APK(s)`、またはコマンドラインで:

```sh
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk（R8 で縮小済み）
./gradlew testDebugUnitTest    # Robolectric テスト（初回は Android の jar を自動ダウンロード）
```

- `release` は動作確認しやすいよう **debug 鍵で署名**する設定です。配布する場合は `app/build.gradle` の `signingConfig` を自分の鍵に変更してください。
- 構成: Android Gradle Plugin 8.7.3 / Gradle 8.14.3 / compileSdk 35 / minSdk 21 / Java 8 ソース互換。
- **この Gradle ビルドは開発環境では実行できていません**（Google の Maven リポジトリに接続できず AGP を取得できなかったため）。設定は標準的な内容ですが、新しい Android Studio が AGP の更新を提案した場合は従ってください。

### B. SDK・Gradle なしのビルド（`dist/` の APK はこの方法で作成）

Debian/Ubuntu のパッケージと Maven Central の jar だけでビルドできます。

```sh
sudo apt-get install aapt dalvik-exchange zipalign apksigner android-sdk-platform-23
# フレームワーク jar（リソース表入り）: org.robolectric:android-all:13-robolectric-9030017
curl -LO https://repo1.maven.org/maven2/org/robolectric/android-all/13-robolectric-9030017/android-all-13-robolectric-9030017.jar
# 任意: ProGuard 7.x（com.guardsquare:proguard-base と依存 jar）を置いたディレクトリ

FRAMEWORK_JAR=$PWD/android-all-13-robolectric-9030017.jar \
PROGUARD_LIB=/path/to/proguard/lib \
KEYSTORE=my.jks KS_PASS=... KEY_ALIAS=... \
tools/build-apk.sh                 # → build/zero-launcher.apk
```

`KEYSTORE` を省略すると使い捨て鍵を生成して署名します。
手順: aapt（リソース）→ javac（java.* は API 23、android.* は API 33 の定義でコンパイル）→ ProGuard（任意）→ dx → zipalign → apksigner（v1+v2+v3 署名）。

### インストールとホームアプリへの設定

```sh
adb install -r dist/ZeroLauncher-1.0.apk
```

ホームボタンを押して「Zero Launcher」を選ぶか、他のランチャーのアプリ一覧から「Zero Launcher」（設定画面）を開き「ホームアプリに設定」をタップします
（Android 10 以降は RoleManager のダイアログ、それ以前はシステムのホームアプリ設定画面が開きます）。

## 3. 使い方

| 操作 | 動作 |
|---|---|
| アプリ名をタップ | そのアプリを起動 |
| 左右にスワイプ | 次／前のページ（即時切り替え。アニメーションは設定でオン可） |
| 右下のページ番号「1 / 3」をタップ | 次のページ（最後のページからは先頭へ） |
| 左下の「設定」をタップ | 設定画面 |
| アプリ名を長押し | そのアプリのシステム「アプリ情報」画面（アンインストール等） |
| 戻るキー | 先頭ページへ（ホーム画面は閉じない） |
| ホーム画面でホームボタン | 先頭ページへ（他アプリから戻った場合はページを保持） |
| キーボード／D-pad | ↑↓で選択、←→でページ、Enter で起動、Menu で設定 |

## 4. 実装した機能

**ホーム画面**
- 起動可能アプリ（MAIN/LAUNCHER）の名前の縦一覧。アイコン・壁紙・装飾は一切なし。背景 #000000／文字 #FFFFFF（白背景も可）
- 左右スワイプによるページ切り替え（距離 1/5 画面幅以上、または速いフリック）。表示中ページだけを描画
- 1 ページの行数は画面の高さ・文字サイズから自動計算（1 行は最低 48dp のタップ領域）。手動指定も可
- 長いアプリ名は末尾を「…」で省略。右から左に書く言語（RTL）にも対応
- 画面回転・画面サイズ変更（マルチウィンドウ等）に、Activity を作り直さずに追従
- ステータスバー・ナビゲーションバーは表示したまま。ノッチ（ディスプレイカットアウト）とも重ならない
- TalkBack 対応（各アプリ名・設定・ページ番号を個別に読み上げ／操作可能）、キーボード・D-pad 操作

**アプリ一覧の管理**
- `LauncherApps` で取得（ホームアプリ向けの正式 API。仕事用プロファイルのアプリも表示し、名前に「仕事用」等のバッジを付与）
- Android 11 以降のパッケージ可視性制限に対応（`<queries>` で起動可能アプリのみ可視化。`QUERY_ALL_PACKAGES` は不使用）
- アプリの追加・削除・更新・有効/無効化を `LauncherApps.Callback` で検知し、**変化したパッケージだけ**を再取得
- 名前順（ロケールに応じた照合順序）。同名アプリは「Camera (com.vendor.camera)」のように区別
- 起動できない場合（削除済み・無効・ロック中プロファイル等）は落ちずにトーストを表示し、そのアプリの情報を再取得
- アプリ名と起動情報をキャッシュファイルに保存し、プロセス再起動時はファイルから即表示（後述）
- 自分自身（Zero Launcher の設定画面）は一覧に出さない

**設定画面（文字のみ。各行をタップで値が切り替わる）**
- ホームアプリに設定（現在の状態も表示）
- 文字サイズ: 小 / 中 / 大 / 特大（14 / 18 / 22 / 28sp）
- 並び順: 名前（昇順）/ 名前（降順）/ インストールが新しい順
- 1 ページの表示数: 自動 / 4〜25
- アニメーション: オフ（初期値）/ オン — ページ切り替えのスライドと、アプリ起動時のシステムの画面遷移アニメーション
- 背景色: 黒 / 白
- 色深度: 16bit（省メモリ・初期値）/ 32bit
- アプリ一覧を再読み込み
- 設定を初期化（誤操作防止のため 2 回タップ）

## 5. 軽量化のために採用した技術

### RAM
- **画面全体が 1 つの自作 View**。子 View・TextView・Bitmap・Drawable・アイコンを持たず、`Canvas.drawText` だけで描画
- **アプリアイコンを一切読み込まない**。`LauncherActivityInfo` も保持せず、各アプリは文字列数個の不変オブジェクトのみ
- **ソフトウェア描画**（`android:hardwareAccelerated="false"`）: このプロセスに GL コンテキスト・RenderThread・GPU 用キャッシュを作らない
- **16bit（RGB_565）のウィンドウバッファ**（初期値）: 画面バッファのメモリが 32bit の半分。設定で 32bit に戻せる
- AndroidX / AppCompat / Kotlin 標準ライブラリなし。依存ライブラリ 0。クラス 19 個、dex 約 37KB
- Service・BroadcastReceiver・Alarm・Job・ContentProvider・Application クラスなし
- 読み込み用スレッドは仕事がなくなると終了し、常駐スレッドを残さない

### CPU / バッテリー
- **静止中は何も実行しない**: タイマー・ポーリング・アニメーション・時計なし（テストで「アイドル時に予約済み処理 0・ワーカースレッドなし」を確認）
- **イベント駆動**: パッケージ変更は `LauncherApps.Callback` で受け、該当パッケージだけ再取得（定期的な全件スキャンなし）
- **ラベルのキャッシュ**: アプリ名の取得（各アプリのリソースを開く重い処理）は、APK のパスと更新時刻・言語設定が変わらない限り再実行しない。プロセスが OS に終了されても、次回は数十 KB のファイルを 1 回読むだけで一覧を表示
- 重い処理（一覧取得・ソート・ファイル書き込み）は UI スレッド外。画面表示中は優先度を下げて実行
- 描画中のメモリ確保を避ける: ページ切り替え時に、そのページの省略済み文字列だけを 1 回作って再利用
- タップ時の押下表示は該当行だけ再描画（部分再描画）
- 設定値は static フィールドに読み込み、描画中にファイルや設定を参照しない
- 画面回転などで Activity を作り直さない（`configChanges`）
- 古い端末（API 21〜25）でクラス検証が遅くならないよう、新しい API の呼び出しは `Compat` クラスに隔離（R8/ProGuard でもインライン展開させない）

### GPU / 描画
- 不透明な単色背景はウィンドウ背景の 1 回だけ（重ね塗りなし）。壁紙を表示しない（`windowShowWallpaper=false`）
- 半透明・影・グラデーション・ブラー・画像なし。システムバーの半透明スクリム（コントラスト強制）も無効化
- アニメーションは初期値オフ。ページ切り替えは即時、アプリ起動も画面遷移アニメーションなし（`FLAG_ACTIVITY_NO_ANIMATION`）
- 非表示のページは描画しない

### ストレージ
- APK 33KB（リソースは文字列とテーマのみ。画像・フォントなし）
- 保存データは設定（SharedPreferences）と一覧キャッシュ（`cacheDir`、空き容量不足時は OS が削除可能）のみ
- ログファイルは作らない。計測用ログは logcat のみ、かつ既定でオフ

### 起動の速さ
- `launchMode=singleTask` / `clearTaskOnLaunch` / `stateNotNeeded` / `resumeWhilePausing` / `excludeFromRecents`（AOSP の Launcher3 と同様の設定）
- 起動ウィンドウ（プレビュー）は黒一色で、本描画と見た目が変わらない

## 6. 動作確認と測定結果

### 開発環境で実施したこと（すべて実行済み）

| 項目 | 結果 |
|---|---|
| APK 生成（`tools/build-apk.sh`、ProGuard 有効） | 成功。**33,163 バイト**（classes.dex 37,612 バイト / クラス 19 / メソッド参照 430） |
| 署名検証（`apksigner verify`） | v1・v2・v3 署名すべて有効 |
| マニフェスト確認（`aapt dump badging`） | minSdk 21 / targetSdk 35 / HOME カテゴリ / 権限なし |
| API 互換性チェック（`tools/api-check.py`） | dex が参照する Android API を Android 5.0（API 21）の実装と照合。21 に無い API（16 件）はすべて `Compat`／`Ui` 内の SDK バージョン判定済みの箇所のみ |
| Robolectric テスト（`app/src/test`、18 件 × API 23/26/29/30/35 = **90 件**） | **全件成功**。未最適化のクラスと、ProGuard で最適化したクラスの両方で実行 |

テストの内容: 名前順の表示とタップ起動（Intent とフラグの確認）／自分自身の除外／スワイプでのページ切り替え（即時・アニメなし）、短いドラッグでは切り替わらない／戻るキーとホームボタンの挙動／ページ番号タップと D-pad／アプリ追加・削除イベントでの差分更新／キャッシュからの即時表示と、APK が変わらない限りラベルを再取得しないこと／壊れたキャッシュの無視／同名アプリの区別／並び順・手動行数・色深度の設定／起動失敗時にトーストを出して落ちないこと／設定画面の切り替えと 2 回タップでの初期化／フッターから設定を開く／TalkBack 用ノードとクリック操作／**アイドル時にワーカースレッドも予約済み処理も残らないこと**／アプリ 0 件でも落ちないこと。

テストの実行方法（この環境では Google Maven の `androidx.test` を取得できなかったため、`tools/offline-test/androidx-stubs` の最小代替クラスを使用）:

```sh
FRAMEWORK_JAR=... ROBO_LIB=<robolectric 4.17 と依存 jar> ROBO_SDK_DIR=<android-all-instrumented-*-i7.jar> tools/run-tests.sh
# ProGuard で最適化したコードをテスト: APP_CLASSES=build/manual/classes-pg-test.jar を追加
```

> Robolectric は Android フレームワークの実コードを JVM 上で動かすテストで、実機の代わりにはなりません。
> 実際の描画、タッチ入力の感触、メーカー独自の挙動、性能は確認できていません。

### 性能測定（未実施 — 実機で `tools/measure.sh` を実行してください）

実機がなかったため、以下の値は**一切測定していません**。数値を推測で記載することもしていません。

```sh
# Zero Launcher をインストールしてホームに設定した端末で:
tools/measure.sh -a dist/ZeroLauncher-1.0.apk -c <比較する標準ランチャーのパッケージ名>
# 例: -c com.android.launcher3 / com.google.android.apps.nexuslauncher / com.sec.android.app.launcher
```

| 指示書の測定項目 | `measure.sh` での測り方 |
|---|---|
| 1. APK ファイルサイズ | ローカルと端末上の APK のバイト数 |
| 2. 起動直後の RAM | コールド起動 5 秒後の `dumpsys meminfo`（PSS 合計・Java・ネイティブ・Graphics） |
| 3. 安定状態の RAM（PSS） | ホーム画面で `-i` 秒（既定 60 秒）放置後の `dumpsys meminfo` |
| 4. 静止時の CPU 使用率 | 同じ放置時間中の `/proc/<pid>/stat`（utime+stime）の増分 |
| 5. 一覧表示までの時間 | `am start -W` の TotalTime、`reportFullyDrawn` による「Fully drawn」、アプリ自身の `first_list_draw` ログ |
| 6. ページ切り替えの応答時間 | `input swipe` 20 回。指を離してからページ描画完了まで（`page_switch` ログ） |
| 7. 起動操作から起動要求まで | D-pad + Enter で 5 回起動。入力イベントから `startActivity` が返るまで（`launch_request` ログ） |
| 8. 連続操作時のフレーム落ち | スワイプ中の onDraw 時間と 16ms 超過回数（ソフトウェア描画のため `dumpsys gfxinfo` は使えない） |
| 9. 長時間使用時のメモリ増加 | スワイプ＋アプリ起動を `-l` 回（既定 30）繰り返す前後の PSS |

- 標準ランチャーとの比較は `-c` で同一端末・同一条件（起動時間・RAM・静止時 CPU）で測定します。
- アプリ内の計測ログは `adb shell setprop log.tag.ZeroLauncher D` のときだけ出力され、スクリプトの最後に無効化します。通常時のコストは boolean の判定 1 回です。
- `measure.sh` は偽の adb 出力に対する解析処理の確認のみ行っており、実機では未実行です。

## 7. 未検証事項・既知の制限

**未検証**
- 実機・エミュレータでの起動と操作（上記のとおり一度も実行していない）
- すべての性能値（RAM、CPU、起動時間、フレーム落ち、長時間使用）と、標準ランチャーとの比較
- ソフトウェア描画・16bit バッファによる省メモリ効果は設計上の見込みであり、実測していない。端末によっては逆効果の可能性もあるため、色深度は設定で切り替え可能（ソフトウェア描画は `AndroidManifest.xml` の `hardwareAccelerated` で変更可能）
- Gradle（AGP）でのビルド（接続制限のため実行不可。`dist/` の APK は aapt v1 + dx + ProGuard で作成。Gradle 版は aapt2 + D8/R8 を使うため、生成物はわずかに異なる）
- Android 5.0〜5.1（API 21〜22）上での動作は、テストでは確認していない（Robolectric 4.17 は API 23 以降のみ）。API 21 との差分は静的チェックのみ
- 仕事用プロファイル、マルチユーザー、TalkBack、物理キーボード、RTL 言語、折りたたみ端末での実機確認
- メーカー独自 ROM（MIUI、One UI など）でのホームアプリ登録の挙動

**既知の制限・仕様**
- ウィジェット、ショートカット、検索、フォルダ、通知バッジ、壁紙、ドック、アプリの非表示機能はない（指示書の範囲外のため未実装）
- Zero Launcher 自体のアイコン画像はない（「画像素材を使用しない」方針のため、システム標準アイコンが使われる）
- 設定の「アニメーション」はページ切り替えに加え、アプリ起動時の画面遷移アニメーションも制御する（オフ＝遷移なし）
- スワイプは端のページで止まる（ループしない）。ページ番号のタップは最後から先頭に戻る
- API 21〜22 で白背景を選ぶと、ステータスバーは黒のまま（暗いアイコン表示が API 23 以降のため）。ナビゲーションバーは API 26 未満で黒のまま
- アプリが更新なしに名前を変えた場合（実行時のリソース差し替え等）はキャッシュが古いまま残る。設定の「アプリ一覧を再読み込み」で更新できる
- Android 15 の「プライベートスペース」のアプリは表示しない（専用の権限が必要なため）
- 仕事用プロファイルが一時停止中のアプリをタップすると、システムの確認画面または「起動できません」の表示になる
- `dist/` の APK は、リポジトリに含まれない使い捨て鍵で署名している。自分でビルドした APK（別の鍵）で上書き更新するには、一度アンインストールが必要
- 1GB RAM・Cortex-A7 クラスの端末を設計の基準にしているが、その条件での動作は保証していない

## 8. ファイル構成

```
app/src/main/AndroidManifest.xml      ホーム Activity・設定 Activity・<queries>（権限なし）
app/src/main/java/.../HomeActivity     ホーム画面（ウィンドウ設定、戻る/ホームボタンの扱い）
app/src/main/java/.../AppListView      一覧の描画・タッチ・スワイプ・キー操作・TalkBack
app/src/main/java/.../AppStore         アプリ一覧の取得・差分更新・キャッシュ・起動
app/src/main/java/.../App              1 アプリ分の不変データ
app/src/main/java/.../SettingsActivity 文字だけの設定画面
app/src/main/java/.../Prefs            設定値
app/src/main/java/.../Ui, Compat       ウィンドウ配色、新しい API の隔離
app/src/main/java/.../PerfLog          計測用ログ（既定オフ）
app/src/test/java/.../LauncherTest     Robolectric テスト
tools/build-apk.sh                     SDK・Gradle なしの APK ビルド
tools/run-tests.sh                     Gradle なしのテスト実行
tools/measure.sh                       実機での性能測定（adb）
tools/api-check.py                     minSdk に無い API の参照チェック
dist/ZeroLauncher-1.0.apk              ビルド済み APK
```

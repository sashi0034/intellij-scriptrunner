# Script Runner

IntelliJ IDEAの右側の **Script Runner** ツールウィンドウから、プロジェクト内外のスクリプトを実行するプラグインです。Windows・macOS・Linux向けに実装しています。

## 使い方

1. 右側の **Script Runner** を開き、**Create configuration** をクリックします。
2. 作成された `.idea/script-runner/scripts.yml` に実行設定を書き、保存します。
3. 一覧の行頭にある実行アイコンをクリックするか、スクリプトを選んでEnterを押すと起動します。実行中はアイコンが停止に変わり、同じ操作で停止できます。検索欄では名前・パスを絞り込めます。
4. 出力・エラー・終了コードは **Run** ウィンドウに表示されます。コンソールにフォーカスすると行単位で標準入力を送れます。

ツールバーには **Open Configuration** と **Reload** があります。設定の保存やIDEが検知した外部変更は自動反映されます。外部変更がまだ反映されない場合はReloadでディスク上の内容を読み直してください。

実行時は設定ファイルの未保存内容を保存し、ディスク上の最新設定を検証します。設定エラーがある間は新規実行できません。実行中のスクリプトは影響を受けません。

1つのスクリプトIDにつき同時に実行できるのは1プロセスです。異なるスクリプトは並列実行できます。各Runタブに実行番号を付け、**Stop** でその実行と子プロセスを停止し、終了後の **Rerun** でそのタブの起動条件を再利用します。再実行時も現在のYAMLが正常である必要があります。タブを閉じた場合やプロジェクトを閉じた場合も実行を終了します。

## 設定例

```yaml
version: 1

scripts:
  - id: generate
    name: データ生成
    script: scripts/generate.py
    command: [python, "-u"]
    args: ["--count", "100"]
    env:
      APP_ENV: development

  - id: maintenance
    name: メンテナンス
    script: /home/user/tools/maintenance.sh
    command: [bash]
    workingDirectory: .
```

| 項目 | 内容 |
| --- | --- |
| `version` | 必須。整数の `1` |
| `scripts` | 必須。実行設定の配列。未登録なら `[]` |
| `id` | 必須。設定内で重複しない文字列 |
| `name` | 必須。一覧とRunタブに表示する名前 |
| `script` | 必須。スクリプトの相対パスまたは絶対パス |
| `command` | 必須。実行プログラムとオプションの文字列配列。空配列ならスクリプトを直接実行 |
| `args` | 任意。スクリプトに渡す文字列配列。既定値は `[]` |
| `workingDirectory` | 任意。省略するとスクリプトの親ディレクトリ |
| `env` | 任意。継承した環境変数に追加・上書きする文字列マップ |

起動する引数列は `command + [スクリプトの絶対パス] + args` です。たとえば `command: [python, "-u"]` は `python -u <script> <args...>` に対応します。Python・bash・Node.js・PowerShellなどの実行環境はPC側にインストールしてください。言語別IDEプラグインは不要です。

- 相対パスはすべてプロジェクトルート基準です。`workingDirectory: .` はプロジェクトルートを表します。`../tools/task.py` のようにプロジェクト外も指定できます。
- `command` の先頭が `python` のような名前なら、IDEが継承したPATHで検索します。`./.venv/bin/python` や `.venv/Scripts/python.exe` のようなパスはプロジェクトルート基準です。実行ファイルの絶対パスも指定できます。
- 引数を一つのコマンド文字列に連結しません。`args: ["a b", "", "日本語"]` は3個の引数をそのまま渡します。クォート文字を引数に含める必要はありません。
- シェル展開を行わないため、`~`、`$HOME`、`%USERPROFILE%`、`*.txt`、パイプ・リダイレクトは自動展開されません。必要な処理はスクリプト本体に記述してください。
- Windowsでは `script: 'C:\Users\me\tools\task.py'` のようなYAMLのシングルクォート、または `C:/Users/me/tools/task.py` を利用できます。
- `env` や `args` の数値・真偽値は `"8080"`、`"true"` のように文字列として記述してください。
- 標準入出力はUTF-8です。Pythonの出力が遅延する場合は例のように `-u` を指定できます。実行プログラム側もUTF-8を使うよう設定してください。
- YAMLの構文・型・必須項目・未知の項目・重複IDを検証します。YAMLキーの重複や任意のオブジェクトタグも許可しません。設定ファイルは1 MBまでです。

設定はプロジェクトごとに保存されます。Gitで共有する場合は、プロジェクト側の `.gitignore` にある `.idea` の除外設定を確認し、必要な設定ファイルを明示的に追加してください。

## 対応範囲

IntelliJ IDEA 2025.3以降向けです。通常の行単位の標準入力に対応し、自動実行・デバッグ・TTYを必要とする全画面端末アプリ・WSL/SSH/コンテナ専用連携は対象外です。OSに合った実行コマンドとスクリプトを設定してください。

## 開発と検証

Gradle JVMにはJDK 21を指定し、Gradle Wrapperを使います。
IntelliJでは **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JVM** から選択してください。
プロジェクトSDKとGradle JVMは別設定です。Java 27をプロジェクトSDKにしている場合も、Gradle JVMにはJDK 21を選びます。

```powershell
.\gradlew.bat test buildPlugin verifyPluginProjectConfiguration
.\gradlew.bat runIde
```

macOS/Linuxでは `./gradlew` を使います。インストール用ZIPは `build/distributions/` に生成されます。

自動テストはYAML検証、パス・引数解決、プロセスのUTF-8出力・stderr・標準入力・環境変数・終了コード、同一スクリプトの二重起動防止、異なるスクリプトの並列実行と独立停止、子プロセスの停止を対象にします。プロセステストにはJDK上で動く小さなJavaプログラムを使い、Pythonやbashの追加インストールは不要です。

IDE上の手動確認項目:

- Script Runnerが右側に表示され、未設定からサンプルを作成・編集できる。
- 保存・外部変更・Reloadで一覧が更新され、検索、行頭アイコン、Enterでの実行が使える。
- 不正なYAMLで理由が表示され、新規実行が無効になる。修正後に復帰する。
- プロジェクト外のファイル、空白・日本語を含むパスが実行できる。
- 実行中の行頭アイコンが停止表示に変わり、同一スクリプトを二重起動せず、そのアイコンから停止できる。
- 異なるスクリプトの複数Runタブ、ANSIカラー、標準入力、Stop、Rerunが動作する。
- YAMLを変更しても既存タブのRerunは元の条件を使う。
- タブやプロジェクトを閉じたときにプロセスが残らない。
- 各対応OSで、そのOSの実行環境を使って起動とプロセスツリーの停止を確認する。

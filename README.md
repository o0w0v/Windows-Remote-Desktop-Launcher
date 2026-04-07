# Windows Remote Desktop Launcher

Windows 用の軽量な RDP ランチャーです。`mstsc.exe` を呼び出して、通常の RDP 接続に加えて次の 2 つを扱えます。

- SSH トンネル経由の RDP
- RD Gateway 経由の RDP

このブランチでは、さらに次の機能を追加しています。

- SSH の多段踏み台 (`ProxyJump` / `-J`) 対応
- RD Gateway 設定をアプリのセッションとして保存
- RD Gateway 用の一時 `.rdp` ファイル生成
- ローカルモニター配置のプレビューと選択モニター表示

## Current Scope

現在の実装では、1 セッションで次のどちらか一方を使う前提です。

- SSH トンネル
- RD Gateway

つまり、`SSH トンネル + RD Gateway` の同時利用は未対応です。

## Session Fields

### Common

- `Name`: セッション名
- `RDP host`: 接続先 Windows ホスト
- `RDP port`: 通常は `3389`
- `Username` / `Password` / `Domain/Prefix`: 接続時の認証情報
- `Display`: `Display` ボタンから全画面、ウィンドウサイズ、全モニター、選択モニターを設定

### SSH Tunnel

- `Use SSH tunnel`: SSH 経由でローカルポートフォワードを作成
- `SSH bastion chain`: 手元PCから順番に経由する SSH 踏み台をカンマ区切りで指定
- `Last SSH bastion`: チェーンの最後の踏み台。画面では自動表示され、直接編集しません
- `SSH options`: `-p`, `-i` など追加オプション

例:

```text
SSH bastion chain: bastion1,bastion2,bastion3
RDP host: windows-server.internal
```

この場合、`bastion1`, `bastion2`, `bastion3` はすべて SSH 踏み台です。アプリ内部では最後の `bastion3` を SSH の接続先にし、それより手前の `bastion1,bastion2` を `-J` に分解します。

実行イメージは次のとおりです。

```powershell
ssh.exe -N -T -L 127.0.0.1:<localPort>:windows-server.internal:3389 -J bastion1,bastion2 bastion3
```

`SSH options` にすでに `-J`, `ProxyJump`, `ProxyCommand` がある場合は、踏み台チェーンから `-J` を自動付与しません。

### RD Gateway

- `Use RD Gateway`: RD Gateway 経由で接続
- `Gateway host`: RD Gateway サーバ名
- `Use current Windows user for gateway`: Gateway 認証に現在の Windows ユーザーを使う
- `Reuse credentials for gateway and target`: Gateway と接続先で資格情報を 1 回にまとめる

RD Gateway 利用時は `mstsc /v:` の直接起動ではなく、一時 `.rdp` ファイルを生成して開きます。

## Monitor Selection

- `Display` ボタンからモニター選択ダイアログを開けます
- ダイアログではローカル画面の配置をプレビュー表示し、クリックで選択できます
- 選択モニターがある場合、RDP はそのモニター群へフルスクリーン表示されます
- モニター ID の取得には `mstsc.exe /l` をアプリ内で短いタイムアウト付きで試し、取得できない場合はローカル画面順にフォールバックします
- 外部で `mstsc /l` を手動実行して固まる必要はありません

## Credentials

- Password はファイル保存しません
- Username/Domain は必要に応じてセッションへ保存できます
- Username と Password を両方入れた場合は `cmdkey` で一時資格情報を登録し、切断時に削除します
- Username のみを入れた場合は、一時 `.rdp` に `username` を書いて資格情報入力を促します

## CSV Format

セッションは `%USERPROFILE%\rdp-launcher\sessions.csv` に保存されます。

現在のヘッダーは次のとおりです。

```csv
name,useBastion,sshAlias,sshOptions,rdpHost,rdpPort,username,domain,fullscreen,width,height,multimon,span,jumpHosts,useRdGateway,rdGatewayHost,rdGatewayUseCurrentUser,rdGatewayShareCreds,selectedMonitors
```

後方互換のため、旧形式 CSV も読み込めます。新しい項目は末尾に追加しています。

注意:

- CSV は単純なカンマ区切りです
- フィールド値にカンマを含めるケースは未対応です
- `SSH bastion chain` はカンマ区切りを前提にしているため、空白なしの `bastion1,bastion2,bastion3` 形式を推奨します
- `selectedMonitors` もカンマ区切りで保存されます

## Build

### Run

```powershell
.\gradlew.bat run
```

### Build

```powershell
.\gradlew.bat clean build
```

### Package (MSI)

```powershell
.\gradlew.bat clean jpackage -PinstallerType=msi
```

## Requirements

- Windows 10/11
- JDK 22
- `mstsc.exe`
- `cmdkey.exe`
- OpenSSH (`ssh.exe`)
- MSI を作る場合は WiX Toolset

## Data Files

- Sessions CSV: `%USERPROFILE%\rdp-launcher\sessions.csv`
- SSH stdout log: `%TEMP%\rdp-launcher-ssh-out.log`
- SSH stderr log: `%TEMP%\rdp-launcher-ssh-err.log`
- Known hosts: `%USERPROFILE%\rdp-launcher\known_hosts`

## Notes

- SSH トンネルではローカル接続先を `127.0.0.1` に固定して、`localhost` / `::1` の揺れを避けています
- RD Gateway の認証ポリシーは環境差があるため、`Use current Windows user for gateway` と `Reuse credentials for gateway and target` は実環境で確認してください
- 選択モニター表示は `selectedmonitors` を使うため、RDP はフルスクリーン表示として扱われます
- 将来的に必要であれば、`SSH + RD Gateway` の複合経路や Windows 踏み台のワークフローは別モードとして拡張できます

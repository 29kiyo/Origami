<p align="center"><img src="src/main/resources/assets/origami/icon.png" alt="Origami" width="128"></p>

# Origami

[English](README.md) | 日本語

Origami は、Bukkit API を Fabric / Minecraft の呼び出しに翻訳することで、**Paper / Spigot プラグイン**を Fabric のサーバー(とシングルプレイ)で動かす Fabric Mod です。純粋な API 翻訳レイヤーで、**Mixin を一切使いません**。そのため Sodium や Lithium などの他の Mod と衝突しません。

> **状態: 初期版 (0.1.0)。** Minecraft 26.2 専用です。多くの Bukkit API を実装していますが、すべてではありません。[制限事項](#制限事項)を参照してください。

## 必要なもの

- Minecraft 26.2
- Fabric Loader 0.19.5 以上
- [Fabric API](https://modrinth.com/mod/fabric-api)
- Java 25

## インストール

1. `origami-<バージョン>.jar` と Fabric API を `mods/` フォルダに入れます。
2. プラグインの `.jar` を、ゲームのディレクトリ(専用サーバーならサーバーのルート)の `plugins/` フォルダに入れます。
3. ゲームまたはサーバーを起動します。プラグインはサーバー起動時に読み込まれ、依存関係の順に有効化されます。

ダウンロード: [Releases](https://github.com/29kiyo/Origami/releases) を参照してください。

## 動作するもの

Origami 自身のテスト用プラグインと、次の実プラグインで確認しています。

| プラグイン | 結果 |
|---|---|
| Vault 2.1.1 | 動作 (ServicesManager) |
| EssentialsX 2.22.1-dev+27 | 主要コマンドが動作 (home、warp、tpa、kit、pay、ban など) |
| LuckPerms 5.5.71 | 動作 (権限、グループ、Vault 連携) |
| WorldEdit 7.4.6 (Bukkit 版) | 読み込みと選択系コマンドのみ。編集系は NMS アダプタが必要なため非対応。Fabric 版の WorldEdit を使ってください |

実装している領域: プラグインの読み込み (`plugin.yml`、依存順、隔離クラスローダー)、コマンド (Brigadier との橋渡し、Tab 補完、権限)、イベント (優先度、キャンセル)、`BukkitScheduler`、プレイヤー、ワールド、ブロック (基本)、`ItemStack` / `ItemMeta` / インベントリ (一部)。

Sodium、Sodium Extra、Lithium、FerriteCore との併用でクラッシュしないことも確認しています。

## 制限事項

Origami は次のことを意図して行いません。

- Minecraft の内部 (`net.minecraft.*`、NMS) を直接呼ぶプラグイン、CraftBukkit のクラスへのキャストを行うプラグイン。検出して、分かりやすいメッセージで読み込みを拒否します。
- パケットの直接操作 (ProtocolLib 依存のプラグインなど)。
- `paper-plugin.yml` だけのプラグイン、`plugin.yml` の `libraries:`。
- バニラや他 Mod と同名のコマンドの上書き。`プラグイン名:コマンド`、プラグイン自身の別名、または `commandAliases` (下記) を使ってください。

Mixin が必要になるため、現在は未実装のもの: バニラコマンドの実行前イベント、`PlayerKickEvent`、チャットや参加・退出メッセージの書き換え、プレイヤーの実際の非表示 (`/vanish` に見た目の効果はありません)。

そのほかの未実装: プレイヤー以外のエンティティ、`Block#getState` / `getBlockData`、`PlayerTeleportEvent`、独自のインベントリ、Paper 形式のアイテムのシリアライズ。未実装のメソッドを呼ぶと、例外を投げて警告を1回ログに出します。

## 設定

`config/origami.json`:

| キー | 既定値 | 説明 |
|---|---|---|
| `language` | `en_us` | ログの言語 (`en_us`、`ja_jp`) |
| `recordUnimplementedCalls` | `false` | 未実装 API の呼び出しを数え、停止時に `config/origami-unimplemented.json` へ書き出す (不具合報告に便利) |
| `debug` | `false` | 追加のログを出す |
| `nmsDetection` | `error` | NMS / CraftBukkit を参照するプラグインの扱い: `error`、`warn`、`off`。使わない NMS 用クラスを同梱しているプラグインは `warn` で読み込めます |
| `commandAliases` | `{}` | 追加のコマンド名。例: `{"xgive": "give"}`。新しい名前を足すだけで、既存のコマンドは上書きしません |

## ビルド

JDK 25 で `./gradlew build` を実行します。jar は `build/libs/` に出力されます。

## ライセンス

Origami は [GNU GPL v3](LICENSE) でライセンスされています。

リリース jar には Paper API とそのライブラリがネストした jar として同梱されており、それぞれのライセンスに従います。全文と配布元は [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) にあり、jar の中にも同じ内容 (`THIRD_PARTY_NOTICES_origami.md`) が入っています。

| 同梱物 | ライセンス |
|---|---|
| Paper API | GPL-3.0 |
| Adventure (8 モジュール)、option | MIT |
| SnakeYAML | Apache-2.0 |
| bungeecord-chat | BSD 3-Clause 風のライセンス。**追加条項**があり、作者の書面による許可なしに、商用のソフトウェアホスティングサービスに使うことはできません。Origami を再配布する場合や、商用のホスティングで使う場合は、必ず読んでください |

Origami は PaperMC、SpigotMC、Mojang、Microsoft とは関係がなく、承認も受けていません。「Minecraft」は Mojang AB の商標です。

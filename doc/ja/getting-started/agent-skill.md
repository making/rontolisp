# エージェントスキル

Common Lisp を知っている AI コーディングエージェントでも、rontolisp のコードは
書き間違えます。サブセットに存在しない演算子に手を伸ばし、rontolisp 独自の拡張を
見落とし、各バックエンドでの走らせ方を知らないからです。**エージェントスキル**は
その差を埋めます。中身はこのマニュアルそのもので、Common Lisp との差分を記した
`SKILL.md` と、ここにある全ページを参照用に同梱したものを、デプロイのたびに生成
しています。したがって、いま読んでいるドキュメントから内容がずれることはありません。

## Claude Code へのインストール

スキルはプラグインとして公開しています。マーケットプレイスを追加してインストール
します:

```bash
claude plugin marketplace add https://making.github.io/rontolisp/skill/marketplace.json
claude plugin install rontolisp@rontolisp
```

同じ 2 手順はセッション内でも `/plugin marketplace add ...` と
`/plugin install rontolisp@rontolisp` として実行できます。最初のコマンドに
`--scope project` を付けると、ユーザー設定ではなくリポジトリ側にマーケットプレイス
を宣言できるので、そのチェックアウトで作業する全員に同じものが提示されます。

他に設定は要りません。`.lisp` や `.asd` のファイル、あるいは言語名を含む依頼など、
rontolisp が関わる作業ではエージェントが自分でスキルを参照します。

```bash
claude plugin update rontolisp@rontolisp     # 新しいバージョンを取得
claude plugin uninstall rontolisp@rontolisp  # 削除
claude plugin list                           # インストール済みの一覧
```

Claude Code はその URL からマーケットプレイスのファイルを読み直すため、こちらで
何もしなくても新しいバージョンが利用可能になります。バージョンの上がり方は
[最新に保つ](#最新に保つ)を参照してください。

## プラグインを使わないインストール

スキルはディレクトリであり、Claude Code は `~/.claude/skills`（ユーザー）と
`.claude/skills`（リポジトリ）配下のスキルをすべて読み込みます。マーケットプレイス
を登録せず、置くだけで済ませたい場合は:

```bash
mkdir -p ~/.claude/skills && \
  curl -sSL https://making.github.io/rontolisp/skill/rontolisp-skill.tar.gz | tar xz -C ~/.claude/skills
```

展開先を `.claude/skills` に変えれば、プロジェクト側へのインストールになります。
どちらの場合も `skills/rontolisp/SKILL.md` と `skills/rontolisp/references/` が
できます。読み込まれているものは `/skills` で一覧でき、`rontolisp` ディレクトリを
削除すればアンインストールです。自動では更新されないので、この方法のときは自分で
バージョンを確認してください。

## 他のエージェント・ホスト向け

| ファイル | 用途 |
| --- | --- |
| [marketplace.json](https://making.github.io/rontolisp/skill/marketplace.json) | プラグインのマーケットプレイス。上記のとおり URL で追加 |
| [rontolisp-plugin.zip](https://making.github.io/rontolisp/skill/rontolisp-plugin.zip) | プラグイン本体。別の方法でプラグインを入れる場合に |
| [rontolisp-skill.tar.gz](https://making.github.io/rontolisp/skill/rontolisp-skill.tar.gz) | スキル単体のディレクトリ。`skills` フォルダ向け |
| [rontolisp.skill](https://making.github.io/rontolisp/skill/rontolisp.skill) | 同じ内容の zip。展開ではなくアップロードして使うホスト向け |
| [SKILL.md](https://making.github.io/rontolisp/skill/rontolisp/SKILL.md) | スキル本文のみ。そのまま読める |
| [rontolisp-full.md](https://making.github.io/rontolisp/skill/rontolisp-full.md) | マニュアルとスキルを 1 つの Markdown にまとめたもの。スキル機構を持たないツール向け |

## 最新に保つ

スキルのバージョンは `<リリースの major.minor>.<スキルを変えうるコミット数>` です。
つまりドキュメントか生成器が動いたときだけ上がります。プラグインなら
`claude plugin update rontolisp@rontolisp` で新しいものを取得できます。手で入れた
場合は、手元のものと公開されているものを比べ、違っていれば入れ直してください:

```bash
head -3 ~/.claude/skills/rontolisp/SKILL.md            # version: 0.1.391
curl -sSL https://making.github.io/rontolisp/skill/VERSION
```

入れ直しはインストールと同じコマンドです（その場で上書きされます）。スクリプトから
確認したい場合は、同じバージョンとビルド元のコミットを含む
[version.json](https://making.github.io/rontolisp/skill/version.json) を使えます。

## 中身

`SKILL.md` は「ここでは Common Lisp の知識は*事前分布*であって真実ではない」という
作業原則を述べ、[未対応の Common Lisp 機能](../guides/missing-features.md)を
インラインで取り込みます。その事前分布が最も間違えるのがそこだからです。
`references/` の下には:

- `operators.md` — 言語に存在する全演算子のカテゴリ別索引。「rontolisp にこれは
  あるか」に一度の参照で答えます。Common Lisp の知識が誤答しやすい問いです。
- `contents.md` — このマニュアルの全ページをタイトルで一覧にしたもの。
- このマニュアルの全ページを、同じ相対パスでそのまま収録。必要な詳細は
  ネットワークなしにファイルを読むだけで手に入ります。
- `examples.md` と `examples/` — リポジトリのサンプルプログラムをそのまま収録
  （ビルド生成物は除外し、コンパイル済みの `.wasm` や重みの `.bin` はリポジトリ
  へのリンクになります）。ドキュメントは演算子が何をするかを述べますが、サンプル
  はある形のプログラム全体がどう書かれ、どうビルドされるかを示します。

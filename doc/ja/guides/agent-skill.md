# エージェントスキル

Common Lisp を知っている AI コーディングエージェントでも、rontolisp のコードは
書き間違えます。サブセットに存在しない演算子に手を伸ばし、rontolisp 独自の拡張を
見落とし、各バックエンドでの走らせ方を知らないからです。**エージェントスキル**は
その差を埋めます。中身はこのマニュアルそのもので、Common Lisp との差分を記した
`SKILL.md` と、ここにある全ページを参照用に同梱したものを、デプロイのたびに生成
しています。したがって、いま読んでいるドキュメントから内容がずれることはありません。

## Claude Code へのインストール

スキルは `skills` ディレクトリに置きます。すべてのプロジェクトで使う場合:

```bash
mkdir -p ~/.claude/skills && \
  curl -sSL https://making.github.io/rontolisp/skill/rontolisp-skill.tar.gz | tar xz -C ~/.claude/skills
```

特定のプロジェクトだけで使う場合は、リポジトリ側に展開します。チーム全員が
チェックアウトから同じものを得られるので、こちらが便利なこともあります:

```bash
mkdir -p .claude/skills && \
  curl -sSL https://making.github.io/rontolisp/skill/rontolisp-skill.tar.gz | tar xz -C .claude/skills
```

どちらの場合も `skills/rontolisp/SKILL.md` と `skills/rontolisp/references/` が
できます。Claude Code で `/skills` を実行し、`rontolisp` が一覧に出れば完了です。
他に設定は要りません。`.lisp` や `.asd` のファイル、あるいは言語名を含む依頼など、
rontolisp が関わる作業ではエージェントが自分でスキルを参照します。

削除するときは、その `skills` ディレクトリから `rontolisp` ディレクトリを消して
ください。

## 他のエージェント・ホスト向け

| ファイル | 用途 |
| --- | --- |
| [rontolisp-skill.tar.gz](https://making.github.io/rontolisp/skill/rontolisp-skill.tar.gz) | スキルのディレクトリ。上記のとおり `skills` フォルダへ |
| [rontolisp.skill](https://making.github.io/rontolisp/skill/rontolisp.skill) | 同じ内容の zip。展開ではなくアップロードして使うホスト向け |
| [SKILL.md](https://making.github.io/rontolisp/skill/rontolisp/SKILL.md) | スキル本文のみ。そのまま読める |
| [rontolisp-full.md](https://making.github.io/rontolisp/skill/rontolisp-full.md) | マニュアルとスキルを 1 つの Markdown にまとめたもの。スキル機構を持たないツール向け |

## 最新に保つ

スキルのバージョンは `<リリースの major.minor>.<スキルを変えうるコミット数>` です。
つまりドキュメントか生成器が動いたときだけ上がります。手元のものと公開されている
ものを比べ、違っていれば入れ直してください:

```bash
head -3 ~/.claude/skills/rontolisp/SKILL.md            # version: 0.1.391
curl -sSL https://making.github.io/rontolisp/skill/VERSION
```

入れ直しはインストールと同じコマンドです（その場で上書きされます）。スクリプトから
確認したい場合は、同じバージョンとビルド元のコミットを含む
[version.json](https://making.github.io/rontolisp/skill/version.json) を使えます。

## 中身

`SKILL.md` は「ここでは Common Lisp の知識は*事前分布*であって真実ではない」という
作業原則を述べ、[未対応の Common Lisp 機能](missing-features.md)をインラインで
取り込みます。その事前分布が最も間違えるのがそこだからです。`references/` の下には:

- `operators.md` — 言語に存在する全演算子のカテゴリ別索引。「rontolisp にこれは
  あるか」に一度の参照で答えます。Common Lisp の知識が誤答しやすい問いです。
- `contents.md` — このマニュアルの全ページをタイトルで一覧にしたもの。
- このマニュアルの全ページを、同じ相対パスでそのまま収録。必要な詳細は
  ネットワークなしにファイルを読むだけで手に入ります。

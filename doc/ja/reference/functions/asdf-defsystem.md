# asdf:defsystem

`(asdf:defsystem name &rest options)`

後続の [`asdf:load-system`](asdf-load-system.md) のために **システム** — ロード順の制約を持つ、名前付きのソースファイル群 — を定義し、システム名をシンボルとして返します。これは ASDF の `defsystem` の限定的な API 互換サブセットです: オプションはプレーンなデータであり、評価されることはありません。`name` はリテラルな指示子 (文字列 `"my-lib"`、キーワード `:my-lib`、またはシンボル) です。サポートするオプション:

- メタデータ — `:description`、`:long-description`、`:version`、`:author`、`:maintainer`、`:license` (`:licence` も可)、`:homepage`、`:bug-tracker`、`:source-control`、`:mailto` — `.asd` 互換のために受理され、無視されます。
- `:depends-on (system...)` — このシステムより先にロードされるシステム。`load-system` と同じ探索パスで検索されます。
- `:serial t` — 各コンポーネントが暗黙に直前のコンポーネントへ依存します。
- `:components (component...)` — ソースファイル群: `(:file "name" [:depends-on ("other"...)])` は `name.lisp` を指します。`(:module "dir" [:serial t] [:depends-on (...)] :components (...))` は子要素に `dir/` を前置します。`(:static-file "name")` は受理されますがソースには寄与しません。コンポーネントは `:depends-on` 制約の安定トポロジカル順でロードされます。

その他のオプション (`:in-order-to`、`:perform`、`:defsystem-depends-on`、`:if-feature`、計算された `:version` など) やコンポーネント型は、未サポートの句を名指しするエラーになります。通常このフォームはソースの隣の `NAME.asd` ファイルに書きます。`.asd` ファイルは **データ** として解析されるため、`defsystem` フォーム (裸または `asdf:` 修飾) と `in-package` フォーム (スキップされます) のみを含められます。プログラム中のトップレベルにインラインで書いた `(asdf:defsystem ...)` もシステムを登録します。コンポーネントのパスは `.asd` ファイル (または定義したソース) のディレクトリを基準に解決されます。

```console
;; my-lib.asd
(defsystem :my-lib
  :description "A small example system"
  :version "0.1.0"
  :serial t
  :components ((:file "package")
               (:file "main")))
```

このシステムは `package.lisp` を、次に `main.lisp` をロードします (`:serial t`)。どちらも `my-lib.asd` のディレクトリからです。プロジェクト全体のウォークスルーは[システムガイド](../../guides/asdf-systems.md)を参照してください。

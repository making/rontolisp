# asdf:defsystem

`(asdf:defsystem name &rest options)`

後続の [`asdf:load-system`](asdf-load-system.md) のために **システム** — ロード順の制約を持つ、名前付きのソースファイル群 — を定義し、システム名をシンボルとして返します。これは ASDF の `defsystem` の限定的な API 互換サブセットです: オプションはプレーンなデータであり、評価されることはありません。`name` はリテラルな指示子 (文字列 `"my-lib"`、キーワード `:my-lib`、またはシンボル) です。サポートするオプション:

- メタデータ — `:description`、`:long-description`、`:version`、`:author`、`:maintainer`、`:license` (`:licence` も可)、`:homepage`、`:bug-tracker`、`:source-control`、`:mailto` — `.asd` 互換のために受理され、無視されます。ただし素の文字列で書かれた `:version` だけは [`asdf:component-version`](asdf-component-version.md) が読み返します (`(:read-file-form "version.sexp")` のような計算された書き方は評価されず nil になります)。
- `:depends-on (system...)` — このシステムより先にロードされるシステム。`load-system` と同じ探索パスで検索されます。
- `:defsystem-depends-on (system...)` — 本物の ASDF が `.asd` の **読み込み中** にロードするシステムです。同じ方法で検索され、`:depends-on` より先にロードされます。これらはシステムの依存ではありません ([`asdf:component-sideway-dependencies`](asdf-component-sideway-dependencies.md) には現れません)。ここに組み込みシムシステムを書くと、そのシステムは **フィーチャーを宣言** します: `:defsystem-depends-on ("trivial-features")` は、このシステム自身の句とコンポーネントファイルが読まれる間 `:unix` と `:little-endian` を有効にします。サードパーティのシステムは何も宣言しません — そのためには実行が必要ですが、ここでの `.asd` はデータだからです。
- `:serial t` — 各コンポーネントが暗黙に直前のコンポーネントへ依存します。
- `:pathname "dir"` — システムの全コンポーネントに前置されるディレクトリです。コンポーネント名を裸で書けるようになります (`:pathname "src"` のもとでの `(:file "main")` は `src/main.lisp`)。リテラルなネームストリングのみで、空文字列はディレクトリ階層を追加しません。`:module` の前置とコンポーネントレベルの `:pathname` はこの内側にネストします。
- `:components (component...)` — ソースファイル群: `(:file "name" [:depends-on ("other"...)] [:pathname "file.lisp"])` は `name.lisp` を指します。`(:module "dir" [:serial t] [:depends-on (...)] [:pathname "other-dir"] :components (...))` は子要素に `dir/` を前置します。`(:static-file "name")` は受理されますがソースには寄与しません。コンポーネントには `:if-feature expr` も書けます。フィーチャー式が成立しない場合、ロード順の位置は保ったままソースには寄与しません。コンポーネントは `:depends-on` 制約の安定トポロジカル順でロードされます。
- `:class :package-inferred-system` — システムは `:components` を一切持たず、グラフはソースから導出されます: サブシステム名がシステムのディレクトリ配下のファイルパスになり (`my-lib/util/text` は `util/text.lisp`。システムが `:pathname` を持つ場合はその配下)、そのファイル先頭の `defpackage` が依存関係を表します。[システムガイド](../../guides/asdf-systems.md#what-is-and-is-not-supported)を参照してください。これ以外の `:class` はサポートしません。

test-op 配線用のオプション `:in-order-to` と `:perform` は許容され無視されます (駆動すべき `test-op`/`operate` の機構がありません)。その他のオプション (`:around-compile`、計算された `:pathname` など) やコンポーネント型は、未サポートの句を名指しするエラーになります。通常このフォームはソースの隣の `NAME.asd` ファイルに書きます。`.asd` ファイルは **データ** として解析されるため、含められるのは `defsystem` フォーム (裸または `asdf:` 修飾)、`in-package`/`defpackage` フォーム (スキップされます)、`register-system-packages` フォーム (「このパッケージはあのシステムにある」という対応を記録します。package-inferred-system が `defpackage` の依存をシステム名に変換するときに参照されます)、そして純粋なリテラル値のトップレベル `defparameter` だけです。プログラム中のトップレベルにインラインで書いた `(asdf:defsystem ...)` もシステムを登録します。コンポーネントのパスは `.asd` ファイル (または定義したソース) のディレクトリを基準に解決されます。

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

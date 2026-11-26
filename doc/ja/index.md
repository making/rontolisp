# rontolisp

![rontolisp logo](../assets/rontolisp-logo.png)

rontolisp は、S 式という 1 つの中間言語で結ばれた言語フロントエンドと実行バックエンドの
集まりです。各フロントエンドはソースを読み、共通のコア Lisp フォームに変換します。
マクロ展開とコンパイルパスはこのフォームの上で動き、そこから先は各バックエンドが
受け持ちます。バックエンドはフロントエンド固有の名前を一切知りません。

```text
 .lisp  Common Lisp --+                           +-- Interpreter (REPL)
 .scm   Scheme      --+                           +-- JVM: .class / .jar
 .clj   Clojure     --+--> S-expressions ---------+-- WASM (wasm-GC): WASI Preview 1 module,
 .star  Starlark    --+    (core Lisp forms)      |     WASI 0.3 component
 .java  Java        --+                           +-- WASM (--no-gc): MVP module
                                                  +-- Native executable (--native)
```

Common Lisp は中間言語そのものの言語であり、最も完成度の高いフロントエンドです。ほかの
フロントエンドは実験的な位置づけです。全体が Java で書かれており、コアは外部依存を
持ちません。

## フロントエンド

| 言語 | ファイル | |
| --- | --- | --- |
| Common Lisp | `.lisp` | ANSI Common Lisp のサブセット。[言語リファレンス](reference/data-types.md)、[ガイド](guides/asdf-systems.md)、[未対応の機能](guides/missing-features.md)を参照 |
| [Scheme](scheme/index.md)（実験的） | `.scm` | R7RS-small のサブセット |
| [Clojure](clojure/index.md)（実験的） | `.clj`, `.cljc` | 小さなサブセット。`deps.edn` プロジェクト、nREPL、Ring |
| [Starlark](starlark/index.md)（実験的） | `.star` | Bazel のビルドファイルに使われる Python 方言 |
| [Java](java/index.md)（実験的） | `.java` | 独自のクラス群（JDK のコードは含まない）を備えた言語のサブセット。Servlet、JDBC |

言語はファイルごとに拡張子で決まり（`--source-language` で上書き可能）、1 つのプログラムの
中でファイル単位に言語を混在できます。組み合わせられないのは次の 2 つです。`--no-gc` は
Common Lisp の限られたサブセットしか受け付けず、Clojure の Java 相互運用はインタプリタと
JVM でのみ動きます。

## バックエンド

- **インタプリタ** -- [REPL](getting-started/repl.md) を備えたツリーウォーク評価。
  [ファイル実行](getting-started/file-interpretation.md)時のデフォルトで、スループット
  よりターンアラウンド向け（ループ中心のコードはコンパイルすると 20 倍〜200 倍高速）。
  Common Lisp 向けに [SWANK](getting-started/swank.md)（SLIME、SLY、Lem）、Clojure
  向けに [nREPL](clojure/nrepl.md) を提供
- **[JVM](compiling/jvm.md)** -- 任意の JRE で動く `.class` または実行可能 `.jar`
- **[WebAssembly](compiling/wasm.md)** -- wasm-GC 出力として
  [WASI Preview 1 のコアモジュール](guides/wasm-gc-module.md)、または
  [WIT ワールド](guides/wit-contracts.md)を実装・呼び出しする
  [WASI 0.3 コンポーネント](guides/wasm-component.md)を生成。wasm-GC を持たない
  ランタイム向けには [`--no-gc`](guides/wasm-nogc.md) で MVP モジュールも生成可能
- **[ネイティブ実行ファイル](compiling/native.md)** -- `--native` で WASM 出力を
  wasmtime によりプリコンパイルし、wasmtime も JVM も不要な単一の実行ファイルにする

`rontolisp` コマンドは実行とコンパイルのほかに、[ソースの整形](getting-started/format.md)と、
結果を終了コードで返すテストスイートの実行も行います。テストには Common Lisp なら
[rove](guides/testing.md)、`deps.edn` プロジェクトなら [`clojure.test`](clojure/reference/test.md)、
Java プログラムなら [JUnit Jupiter のテスト](java/testing.md)を使います。
[Maven プラグイン](compiling/maven-plugin.md)は同じことを Maven のビルドの中で行います。
`src/main/lisp` をプロジェクトの jar にコンパイルし、Java のプログラムを WebAssembly に
コンパイルし、テストを実行します。
ビルド済みバイナリの入手とソースからのビルドは [ビルドとインストール](getting-started/build.md)を
参照してください。

## 試してみる

このサイトの例は、[playground](../../playground.html) と同じ WebAssembly ビルドを使って
ブラウザ上で実行されます。

```lisp
(defun fact (n) (if (= n 0) 1 (* n (fact (- n 1)))))
(print (fact 10))
```

上の **Run** を押してください（初回実行時に rontolisp ランタイムが読み込まれます）。

AI コーディングエージェントと作業する場合は、同じページ群から生成される
[エージェントスキル](getting-started/agent-skill.md)（デプロイのたびに再生成）も利用できます。

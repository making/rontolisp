# テスト (rove)

[Rove](https://github.com/fukamachi/rove) — 深町英太郎氏によるテスティング
フレームワークで、Prove の後継 — は `(ql:quickload "rove")` で無改変で
ロードでき (v0.10.0)、その形式で書いたテストスイートが spec レポーター付きで
4 つのバックエンドすべてで動作します: インタプリタ、コンパイル済み JVM クラス、
WASM Preview 1、WASI 0.3 コンポーネント。依存は自動で解決されます: cl-ppcre と
[dissect](https://github.com/Shinmera/dissect) は実物のソースから、uiop /
trivial-gray-streams / bordeaux-threads は組み込みシムからです。

## スイートを実行する: `rontolisp test`

`rontolisp test TARGET` は rove のターゲットを実行し、**その判定を終了コードに
します** — 全テストがパスすれば 0、1 つでも失敗すれば 1。rove 自身の
`roswell/rove.ros` スクリプトに相当するもので、CI のステップや `make test`、
git フックが `$?` を読めるようになります:

```bash
rontolisp test tests/main.lisp     # a test file
rontolisp test my-app.asd          # the system the .asd is named after
rontolisp test my-app/tests        # an ASDF system designator
```

| ターゲット | 実行されるもの |
| --- | --- |
| `FILE.lisp` | ファイルをロードします。その `defpackage` が検索パス上の ASDF システムを名指していれば、代わりにそのシステムをロードしてテストします (ASDF の package-inferred 規則)。そうでなければファイル自身のパッケージのスイートを実行します — ただしファイルが既に実行済みならそれを検出するので、テストがちょうど 1 回だけ走ります |
| `FILE.asd` | そのファイル名が示すシステム |
| `SYSTEM` | `asdf:load-system` + `asdf:test-system`。`:perform (test-op ...)` を宣言していないシステムでは続けて `rove:run` |

終了コードは、全テストがパスで **0**、失敗があった場合・プログラムがエラーを
送出した場合・そして*テストが 1 つも走らなかった*場合 (テストの登録が止まった
スイートは空虚なパスではなく失敗として扱います) は **1**、コマンドライン自体が
誤っていた場合は **2** です。

| オプション | 意味 |
| --- | --- |
| `-r`, `--reporter spec\|dot\|none` | rove のレポータースタイル。既定は `spec` |
| `--disable-colors`, `--color` | ANSI カラーを強制的に切る / 点ける。既定は出力先に従い、端末なら点き、パイプなら切れます |
| `--system-path DIRS` | `NAME.asd` を探すディレクトリ (`PATH` と同じ形式) |
| `--dist DISTS` | quicklisp に加えて `ql:quickload` がダウンロードできる dist (例: `ultralisp`。[システムガイド](asdf-systems.md#adding-a-dist-ultralisp)を参照) |
| `-o FILE` | 実行する代わりにコンパイルします (後述) |

素の `rontolisp FILE` は従来どおりで、Common Lisp のセマンティクスを保ちます:
最後のトップレベルフォームの値は捨てられ、ステータスは 0 のままです —
`sbcl --script` とまったく同じです。独自のステータスを返したいプログラムは
`uiop:quit` を書きます。

## テストを書く

アサーション一式が動作します: `deftest`、`testing`、`ok`、`ng`、`signals`
(ユーザー定義のコンディションクラスでも `'type-error` のような組み込みでも)、
`outputs`、`expands`、`pass`、`fail`、`skip`、`failing`、`setup`、`teardown`、
`defhook`、`diag`。評価中にフォームがエラーを送出したアサーションは、その
コンディション付きの失敗として記録され、実行は継続します。

```console
$ cat tests/main.lisp
(defpackage #:my-app/tests/main
  (:use #:cl
        #:rove
        #:my-app/main))
(in-package #:my-app/tests/main)

(deftest add-test
  (testing "adding two integers"
    (ok (= (add 1 2) 3))
    (ng (= (add 1 2) 4))))

(deftest parse-token-test
  (testing "invalid tokens"
    (ok (signals (parse-token "") 'app-error)
        "Parse error")))
```

## 2 つのエントリポイント

**システム駆動** — `rove:run` は ASDF システムデジグネータを受け取り、ロードし、
含まれるスイートをすべて実行します。システムの形は両方動作します:
`:package-inferred-system` (スイートはシステムのパッケージ依存関係から見つかります)
と、素の `defsystem` テストシステム (スイートは rove が `deftest` ごとに
`*load-pathname*` をキーに記録するファイル→パッケージ対応から見つかります):

```console
* (rove:run :my-app/tests)
```

**ファイル駆動** — README FAQ のスタイル: テストファイルの末尾に `run-suite` を
置き、ファイルのロードがそのまま実行になります:

```console
(rove:run-suite *package*)
```

`rove:run-test` (テストシンボル 1 つ) と `rove:run-tests` (リスト) も動作します。
各エントリポイントは、すべてパスしたかどうかを第 1 の値として返します。

非対話出力では、まず ANSI カラーを切ってください — rove のデフォルトは
Emacs 外ではカラー ON です (`rontolisp test` は、出力先が端末でなければ自動で
切ります):

```console
(setf rove:*enable-colors* nil)
```

## 4 つのバックエンドで実行する

コンパイルされたテストプログラムは自己完結です: トップレベルの
`asdf:load-system` が名指すシステムはコンパイル時にスプライスされ、rove 自身が
実行時に行うロード済みシステムの `load-system` は no-op になります。
`--system-path` に `.asd` を持つディレクトリ (テスト対象アプリ、rove、dissect、
cl-ppcre) を指定します。

`rontolisp test -o` は実行する代わりにコンパイルし、出力される成果物は同じ
終了コードの契約を持ちます。コンパイラのフラグはすべてそのまま使えます:

```bash
SP="path/to/my-app:path/to/rove:path/to/dissect:path/to/cl-ppcre"
T="rontolisp test --system-path $SP tests/main.lisp"

# 1. Interpreter
$T

# 2. JVM
$T -o Tests.class && java Tests

# 3. WASM Preview 1
$T -o tests.wasm && wasmtime run -W gc=y -W exceptions=y tests.wasm

# 4. WASI 0.3 component
$T -o tests-comp.wasm --component && \
  wasmtime run -W gc=y -W exceptions=y tests-comp.wasm
```

WASM の実行は両方とも `-W exceptions=y` が必要です: rove は失敗したテストを
`handler-bind` で記録するため、モジュールは EH モードになります。自分自身が
ランナーであるテストプログラム (後述) は、`test` を付けない素の `rontolisp`
で同じようにコンパイルできます。

## 終了コード

終了コードは `rontolisp test` が持ちます。そしてそれが本来あるべき場所です:
テストファイルの中に書いた `uiop:quit` は、そのファイルを*他の何か* — 別の
スイート、REPL、それに依存するシステム — がロードした瞬間にプロセスを殺します。
ファイルはテストを持ち、ランナーは終了コードを持つ。上流も同じ線を引いています:
`uiop:quit` を呼ぶのは `rove.ros` であり、`.asd` の `:perform (test-op ...)` は
終了を ASDF の呼び出し元に委ねます。

手で書くのが正しいのはただ 1 か所、自前の 1 行ランナーです。`rove:run` は
パスしたかどうかの真偽値を返し、`uiop:quit` はどのバックエンドでも本当に
プロセスを終了させます:

```console
(uiop:quit (if (rove:run :my-app/tests) 0 1))
```

## 自分自身を検査するサンプル

このリポジトリのサンプル 3 本がこの形で書かれており、サンプル用のハーネスが
すべてのバックエンドで実行します — 合う形をコピーしてください:

| サンプル | 形 |
| --- | --- |
| [`examples/console/roman.lisp`](https://github.com/making/rontolisp/blob/develop/examples/console/roman.lisp) | デモを印字したうえで、印字した内容そのものをアサートするプログラム |
| [`examples/cloudflare-workers/httpbin/check.lisp`](https://github.com/making/rontolisp/blob/develop/examples/cloudflare-workers/httpbin/check.lisp) | ドライバ: 対象プログラムを `load` し、実行し、パース済みの応答をアサートする |
| [`examples/browser/minesweeper/minesweeper-core-test.lisp`](https://github.com/making/rontolisp/blob/develop/examples/browser/minesweeper/minesweeper-core-test.lisp) | ヘッドレスで動かせないプログラムの隣に置いたテストファイル。共有している描画抜きのコアを対象にする |

いずれもパッケージも ASDF システムも定義していません。1 ファイルなら、`cl-user`
での `(use-package :rove)` と末尾の `run-suite *package*` だけで足ります:

```console
(asdf:load-system :rove)
(use-package :rove)
(setf *enable-colors* nil)

(deftest arithmetic
  (testing "adding two integers"
    (ok (= (add 1 2) 3))))

(uiop:quit (if (run-suite *package*) 0 1))
```

## 制限事項

- **WASM の生トラップは実行を終わらせます。** インタプリタと JVM では `(car 1)`
  や `(/ 1 0)` に当たったテスト本体は記録された失敗になりますが、WASM
  バックエンドではこれらは生トラップにコンパイルされ、どのハンドラも捕捉
  できません。エラーを**送出する**テスト (`error` 呼び出し、`check-type`、
  不正な `aref`) はどこでも正しく記録されます。
- **失敗レポートにバックトレースは出ません** — dissect のスタック内省は
  すべてのバックエンドで空の no-op インターフェイスなので、SBCL が印字する
  `at file:line` やスタック行はありません。
- **アサーション記述内のシンボルはパッケージ修飾付きで印字されます**
  (SBCL が `(= (ADD 1 2) 3)` と印字するところが
  `Expect (= (MY-APP/MAIN:ADD 1 2) 3) ...` になります) — プリンタはまだ
  `*package*` のアクセス可能性を考慮しません。
- **`deftest` の `:compile-at :run-time` オプションはインタプリタ限定です** —
  本体を `compile` に通しますが、コンパイルバックエンドの eval ランタイムは
  ユーザーマクロを展開できません。
- **コンパイルされたプログラムでの `:style :none`** は、プログラム自身が
  `rove/reporter/none` をロードする必要があります — `make-reporter` は未知の
  スタイルのシステムを実行時にロードし、それができるのはインタプリタだけです。
  `:spec` (デフォルト) と `:dot` は組み込みです。`rontolisp test -r none -o ...`
  なら代わりにロードします。

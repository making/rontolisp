# テスト (rove)

[Rove](https://github.com/fukamachi/rove) — 深町英太郎氏によるテスティング
フレームワークで、Prove の後継 — は `(ql:quickload "rove")` で無改変で
ロードでき (v0.10.0)、その形式で書いたテストスイートが spec レポーター付きで
4 つのバックエンドすべてで動作します: インタプリタ、コンパイル済み JVM クラス、
WASM Preview 1、WASI 0.3 コンポーネント。依存は自動で解決されます: cl-ppcre と
[dissect](https://github.com/Shinmera/dissect) は実物のソースから、uiop /
trivial-gray-streams / bordeaux-threads は組み込みシムからです。

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
Emacs 外ではカラー ON です:

```console
(setf rove:*enable-colors* nil)
```

## 4 つのバックエンドで実行する

コンパイルされたテストプログラムは自己完結です: トップレベルの
`asdf:load-system` が名指すシステムはコンパイル時にスプライスされ、rove 自身が
実行時に行うロード済みシステムの `load-system` は no-op になります。
`--system-path` に `.asd` を持つディレクトリ (テスト対象アプリ、rove、dissect、
cl-ppcre) を指定します:

```bash
SP="path/to/my-app:path/to/rove:path/to/dissect:path/to/cl-ppcre"

# 1. Interpreter
rontolisp --system-path "$SP" run-tests.lisp

# 2. JVM
rontolisp --system-path "$SP" run-tests.lisp -o Tests.class && java Tests

# 3. WASM Preview 1
rontolisp --system-path "$SP" run-tests.lisp -o tests.wasm && \
  wasmtime run -W gc -W exceptions=y tests.wasm

# 4. WASI 0.3 component
rontolisp --system-path "$SP" run-tests.lisp -o tests-comp.wasm --component && \
  wasmtime run -W gc=y -W exceptions=y tests-comp.wasm
```

WASM の実行は両方とも `-W exceptions=y` が必要です: rove は失敗したテストを
`handler-bind` で記録するため、モジュールは EH モードになります。

## 終了コード

`rove:run` はパスしたかどうかの真偽値を返し、`uiop:quit` はどのバックエンドでも
本当にプロセスを終了させます — CI のゲートはプログラム末尾の 1 行です:

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
  `:spec` (デフォルト) と `:dot` は組み込みです。
- **テスト本体の内側の `handler-case` は rove の記録機構を覆い隠しません。**
  rove は各テスト本体を `handler-bind` で包みますが、その内側の `handler-case`
  はまだ外側のハンドラの起動を止められません — したがって、自分でエラーを
  捕捉するコード (フォールバック付きのパースなど) をテストすると、どの
  バックエンドでも "Raise an error while testing." として報告され、そこで
  テストが終わります。そうしたコードはテストの**前**に実行し、返ってきた値を
  アサートしてください。上記の `cloudflare-workers/httpbin/check.lisp` が
  まさにその形です。

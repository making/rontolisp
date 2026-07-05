# システム (asdf)

`asdf` パッケージは、Common Lisp のビルド機構である ASDF の**限定的な API 互換サブセット**を提供します:
複数ファイルのプロジェクトを `NAME.asd` ファイルに
[`asdf:defsystem`](../reference/functions/asdf-defsystem.md) で一度だけ記述すれば、
[`asdf:load-system`](../reference/functions/asdf-load-system.md) がファイルを依存順に —
すべてのバックエンドで — ロードします。本物の ASDF は移植されていません
(CLOS、コンディションシステム、パスネーム API に依存しており、いずれもここには存在しません)。
代わりに `.asd` ファイルはプレーンなデータとして解析され、サポートされる `defsystem`
サブセットが `load`/`require` と同じ機構を駆動します。サブセット内に収まる `.asd`
はそのまま動作します。

| オペレータ | 用途 |
|----------|---------|
| [`asdf:defsystem`](../reference/functions/asdf-defsystem.md) | システムの定義: `:depends-on`、`:serial`、`:components` |
| [`asdf:load-system`](../reference/functions/asdf-load-system.md) | システムのロード (依存が先、ファイルは順序どおり、冪等) |

## プロジェクトの全体像

```console
app/
  my-app.asd
  package.lisp
  main.lisp
  run.lisp
registry/base/
  base.asd
  base.lisp
```

```console
;; app/my-app.asd
(defsystem :my-app
  :version "0.1.0"
  :depends-on (:base)
  :serial t
  :components ((:file "package")
               (:file "main")))

;; app/package.lisp
(defpackage :my-app (:use :cl) (:export :run))

;; app/main.lisp
(in-package :my-app)
(defun run () (print (base:double 21)))

;; app/run.lisp
(asdf:load-system :my-app)
(my-app:run)
```

エントリファイルを実行またはコンパイルします。同じディレクティブが 4 つのバックエンドすべてで動作します:

```console
rontolisp app/run.lisp --system-path registry/base                 # interpret
rontolisp app/run.lisp --system-path registry/base -o Prog.class   # JVM
rontolisp app/run.lisp --system-path registry/base -o app.wasm     # WASM
```

`my-app.asd` は `run.lisp` の隣で見つかり、依存システム `:base` は `--system-path`
経由で見つかります。コンパイルパスではシステム全体 (依存が先) がコンパイル時にプログラムへ
継ぎ足されます。コンパイル時 `load` インクルードとまったく同じ仕組みなので、JVM と WASM
のコンパイラはすべての `defun` をネイティブに認識します。

## システム探索パス

`asdf:load-system` は `NAME.asd` を次の順で探します:

1. `load-system` を実行しているファイルのディレクトリ (`load` と同様)、
2. `--system-path` で指定したディレクトリ (`PATH` のようにプラットフォームのパス区切り文字で複数連結可)、
3. 環境変数 `RONTOLISP_SOURCE_REGISTRY` のディレクトリ (同じ形式)。

依存システムの `.asd` は、依存する側のシステムのディレクトリから探索が始まるため、
1 つのレジストリディレクトリに並んだ兄弟システムは互いを見つけられます。

## サポート範囲 (と非サポート)

- `.asd` ファイルは**データ**として解析されます: `defsystem` (裸または `asdf:` 修飾) と
  `in-package` フォーム (スキップ) のみ書けます。`#+`/`#-` フィーチャ条件は動作し
  (ターゲットバックエンドのフィーチャーに対して評価されます。
  [データ型](../reference/data-types.md#コメントフィーチャー条件features)を参照)、
  `#.` リード時評価フォーム — ASDF バージョンガードの慣用形 — は、通常の read
  エラーの代わりに警告付きでスキップされます。
- `defsystem` はメタデータオプション (無視)、`:depends-on`、`:serial`、
  `:file`/`:module`/`:static-file` エントリを持つ `:components` をサポートします。
  コンポーネントには `:if-feature expr` を付けられます。フィーチャー式が成立しない
  場合、そのコンポーネントのファイルは除外されますが (ライブラリが CLOS 専用
  ファイルを `(:or :sbcl ...)` の後ろにゲートする方法)、依存順序内の位置は
  維持されます。test-op 配線用のオプション `:in-order-to` と `:perform` は許容され
  無視されます (`test-op`/`operate` の機構はありません)。`:version` の値は ASDF の
  `(:read-file-form ...)` 間接参照を含む任意のリテラルフォームで構いません
  (検査されません)。それ以外 (`:defsystem-depends-on` など) は句を名指しする
  エラーです。
- 同じシステムの 2 回目のロードは no-op です。循環する `:depends-on` は検出して報告されます。
- コンパイルパスはリテラルなトップレベルの `(asdf:load-system NAME)` を要求します。
  インタプリタは実行時に計算された名前も受け付けます。

## 実際に何がロードできるか

現在、実世界の 2 つのライブラリが無改変でロードでき、4 つ全てのバックエンド
(インタプリタ、JVM、WASM Preview 1、`--component`) で検証済みです:

- **[split-sequence](https://github.com/sharplispers/split-sequence) v2.0.1**:
  `split-sequence`/`split-sequence-if`/`split-sequence-if-not` が文字列と
  リストに対して動作します — 関数境界を多値チャネル経由で越える第 2 戻り値
  (再開インデックス) を含めて。CLOS 専用の `extended-sequence.lisp` は
  `:if-feature (:or :sbcl :abcl)` でゲートされており自動的に除外されます。
- **[parse-number](https://github.com/sharplispers/parse-number) v1.8**:
  `parse-number`/`parse-real-number`/`parse-positive-real-number` が整数、
  有理数、浮動小数点数、基数プレフィクス付きリテラル (`#xFF`、`#3r12`)、
  指数マーカーを扱います。`(error 'invalid-number :value ... :reason ...)`
  イディオムは簡易コンディション代替を通じて意図した診断情報付きで
  シグナルされます。

両ライブラリの実行可能なデモ — バックエンド別の実行コマンドと期待出力付き —
は
[`examples/asdf/`](https://github.com/making/rontolisp/tree/develop/examples/asdf)
にあります。

現時点でロードできるライブラリの目安は、おおよそ次の範囲に収まるものです:
素の `defun`/`defmacro`/`defpackage` コード、`loop`、`values` を末尾に持つ
関数への `multiple-value-bind`、サポート済みの型指定子による
`check-type`/`etypecase`、宣言 (パース済み no-op、`deftype` を含む)、そして
簡易版 `define-condition`/`make-condition` のエラーイディオム。CLOS、
コンディション/リスタートシステム、動的 (スペシャル) 変数束縛、パス名の上に
構築されたライブラリはまだロードできません
([未対応のCL機能](missing-features.md)を参照)。それ以外の場合の実用は、
**自分自身の**複数ファイル rontolisp プロジェクトの構成です — その `.asd` は
本物の ASDF でも読めるものになります。

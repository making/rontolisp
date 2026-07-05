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
  `in-package` フォーム (スキップ) のみ書けます。リーダーレベルの ASDF イディオム —
  `#+`/`#-` フィーチャ条件、`#.` リード時評価 — はサポートされません。
- `defsystem` はメタデータオプション (無視)、`:depends-on`、`:serial`、
  `:file`/`:module`/`:static-file` エントリを持つ `:components` をサポートします。
  それ以外 (`:in-order-to`、`:perform`、`:defsystem-depends-on`、`:if-feature`、
  `(:read-file-form ...)` など) は句を名指しするエラーです。
  `test-op`/`operate` の機構はありません。
- 同じシステムの 2 回目のロードは no-op です。循環する `:depends-on` は検出して報告されます。
- コンパイルパスはリテラルなトップレベルの `(asdf:load-system NAME)` を要求します。
  インタプリタは実行時に計算された名前も受け付けます。

既存のサードパーティライブラリの多くは、rontolisp が未実装の Common Lisp 機能も使っています
([未対応のCL機能](missing-features.md)を参照)。そのため現時点での実用は、
**自分自身の**複数ファイル rontolisp プロジェクトの構成です — その `.asd` は本物の
ASDF でも読めるものになります。

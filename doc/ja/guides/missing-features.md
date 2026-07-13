# サポートされない Common Lisp の機能

rontolisp は意図的に小さくした Common Lisp のサブセットで、3 つのバックエンド
（インタプリタ、JVM、WASM）で同一に動作します。ランタイムのメタオブジェクト
プロトコルなしで言語をそのままのバイトコードにコンパイルできるよう保つため、
完全な Common Lisp の機能の多くが意図的に省かれています。

このページでは、特に目立つ省略事項を挙げます。**利用できる**ものについては、
[言語リファレンス](../reference/special-forms.md)を参照するか、実行時に
`rontolisp:list-special-forms`、`rontolisp:list-macros`、`rontolisp:list-functions`
で一覧表示してください。

| 機能 | 状況 |
| --- | --- |
| `defmacro`（ユーザーマクロ） | 利用可能（[`defmacro`](../reference/special-forms/defmacro.md) 参照） |
| `&optional` / `&rest` / `&key` / `&aux` | `defun`/`lambda` で利用可能（[`defun`](../reference/special-forms/defun.md) を参照）。`defmacro` は `&rest`/`&body` のみ |
| `&whole` | 利用不可 |
| `values` / `multiple-value-bind` | 利用可能。ユーザ関数の多値も含む（[`multiple-value-bind`](../reference/macros/multiple-value-bind.md) を参照） |
| `block` / `return-from` / `tagbody` / `go` | 利用不可 |
| `catch` / `throw` | 利用不可 |
| `unwind-protect` | 利用可能（[`unwind-protect`](../reference/special-forms/unwind-protect.md) 参照）。wasm-GC では exception-handling プロポーザル経由（`wasmtime -W exceptions=y`）。`--no-gc` ではコンパイルエラー |
| 条件（`define-condition`、`handler-case`、`ignore-errors`、`signal`） | 利用可能（[`handler-case`](../reference/macros/handler-case.md) 参照）。wasm-GC では捕捉に `wasmtime -W exceptions=y` が必要で、ランタイムトラップはそこでは捕捉不能のまま。`--no-gc` ではコンパイルエラー。リスタート（`handler-bind`/`restart-case`）は利用不可 |
| `flet` / `labels` | 利用可能（[`flet`](../reference/macros/flet.md)、[`labels`](../reference/macros/labels.md) 参照） |
| `macrolet` | 利用不可 |
| `loop`（拡張版） | 一部対応（単純ループのサブセット） |
| `defstruct` | 利用可能（[`defstruct`](../reference/special-forms/defstruct.md) 参照）。オプション/`:include` は利用不可 |
| CLOS | 一部対応（静的サブセット: [`defclass`](../reference/special-forms/defclass.md)、[`defgeneric`](../reference/special-forms/defgeneric.md)、[`defmethod`](../reference/special-forms/defmethod.md)、[`make-instance`](../reference/macros/make-instance.md)、[`slot-value`](../reference/macros/slot-value.md)） |
| `declare` / `declaim` / `proclaim` / `the` | 解析されるだけの no-op として利用可能（[`declare`](../reference/macros/declare.md) 参照） |
| `check-type` / `assert` | 利用可能（ライト版、リスタートなし。[`check-type`](../reference/macros/check-type.md) 参照） |
| `eval-when` | 利用可能（`progn` として扱う。[`eval-when`](../reference/macros/eval-when.md) 参照） |
| `typep` | 利用不可 |
| `coerce` | 部分対応(リテラルの `'list` / `'vector` / `'string` 結果型。[`coerce`](../reference/functions/coerce.md) を参照) |
| `defpackage`（ユーザーパッケージ） | 一部対応（`:use`/`:export`/`:nicknames`/`:import-from`。[`defpackage`](../reference/special-forms/defpackage.md) 参照） |
| `make-package` / `export` / `use-package`（ランタイム） | 利用不可 |
| `#+` / `#-` / `*features*` / `#\| ... \|#` | 利用可能（[データ型](../reference/data-types.md#コメントフィーチャー条件features)参照） |
| `#.` read 時評価 / `#:` の新規 uninterned シンボル | 利用不可（`#.` は read エラー、`.asd` ファイル内でのみ許容。`#:name` は普通のシンボルとして読まれ、designator として受理される） |
| `require` / `provide` | 利用可能（[`require`](../reference/functions/require.md) 参照）。`*modules*` 変数は利用不可 |
| `let` による動的（special）束縛 | 利用可能（`defvar`/`defparameter`/`declaim special` が名前を special 宣言する。`progv` はインタプリタのみ） |
| 複素数 | 利用不可 |

## ユーザー定義マクロ（`defmacro`）

ユーザーマクロは**サポートされています** —
バッククォートのテンプレート構文（ネストしたバッククォートを含む）や制限事項
（`&whole`/`&environment` 非対応、コンパイル済みプログラムの実行時 `eval` では
認識されない、など）を含む詳細は
[`defmacro`](../reference/special-forms/defmacro.md) を参照してください。
組み込みマクロのセット（`cond`、`case`、`when`、`unless`、`dotimes`、`dolist`、
`do`、`setf`、`push`、`pop`、`incf` など）は `(rontolisp:list-macros)` で
一覧できます。これらの名前は再定義できません。

## ラムダリストキーワード（`&optional`、`&rest`、`&key`、`&aux`）

`defun` と `lambda` は `&optional`、`&rest`、`&key`、`&allow-other-keys`、
`&aux` をサポートします。詳細は [`defun`](../reference/special-forms/defun.md)
を参照してください。残る制限は次のとおりです: `&whole` は利用できません。
`defmacro` のラムダリストは引き続き必須パラメータと末尾の `&rest`/`&body`
1 つのみを取ります。funcall/apply 経由の呼び出しでは関数の物理パラメータは
7 個までです。コンパイル済み `eval` がランタイムに構築する `lambda` は
ラムダリストキーワードを解釈しません（[コンパイル済み eval の制限](eval-limitations.md)
を参照）。

## 多値（`values`、`multiple-value-bind`）

多値は**利用可能**です: [`values`](../reference/functions/values.md)、
[`values-list`](../reference/functions/values-list.md)、
[`multiple-value-bind`](../reference/macros/multiple-value-bind.md)、
[`multiple-value-list`](../reference/macros/multiple-value-list.md)、
[`multiple-value-call`](../reference/macros/multiple-value-call.md)、
[`nth-value`](../reference/macros/nth-value.md)、および
`floor`/`ceiling`/`round`/`truncate`（剰余）、`gethash`（present-p）、
`parse-integer`（パース停止位置）の副次値と `floor` ファミリのオプションの
除数引数です。**ユーザ定義関数**の結果位置にある `(values ...)` は内部
チャネルを通じて呼び出し側のコンシューマに届くため、CL の一般的なイディオムが
動作します:

```console
> (defun two () (values 1 2))
> (multiple-value-bind (a b) (two) (list a b))
(1 2)
```

Common Lisp からの残る相違点は次のとおりです:

- **末尾以外**の位置で `values` を呼んでから通常の値を返すプロデューサは
  古い余剰値を残すことがあるため、`values` は結果位置で使ってください。
- `funcall #'values`（ファーストクラス値）はコンパイル済みプログラムでは
  主値のみを返します。
- 組み込みの `#'name` を渡した `multiple-value-call` はラッパーの固定
  アリティのままです — それ以外の引数個数にはユーザ定義関数か `lambda` を
  渡してください。
- CL で副次値を持つ他の組み込み関数（`read-from-string`、`macroexpand-1`、
  `intern` など）は単一値のままです。

## 非局所脱出と制御フロー

名前付きブロックや任意のジャンプは利用できません。

- `block` / `return-from` — 名前付きブロックはありません。唯一の非局所脱出は
  `return` で、これは `do` / `do*` / `dolist` / `dotimes` によって確立された
  **最も内側**の反復ブロックから抜けます。
- `tagbody` / `go` — ラベルとジャンプによる制御フローはありません。
- `catch` / `throw` — 動的スコープの脱出はありません。

```console
> (block done (return-from done 1) 2)
The function block is undefined
```

[`unwind-protect`](../reference/special-forms/unwind-protect.md)(あらゆる
脱出時 — 通常復帰・`error` 巻き戻し・`return`/`return-from` — の
クリーンアップ)は `--no-gc` を除くすべてのバックエンドで**利用可能**です。
wasm-GC バックエンドでは WebAssembly の exception-handling プロポーザルを
使うため、出力モジュールの実行には `wasmtime -W exceptions=y`(37+)が必要で、
ランタイムトラップは依然として cleanup をスキップします。

## 条件とリスタート

条件システムのコアは**利用可能**です: コンディション型は組み込み階層
（`condition` > `serious-condition` > `error`、`warning`）の上の CLOS
サブセットクラスで、[`define-condition`](../reference/macros/define-condition.md)
（`:report` 付き）で定義し、[`make-condition`](../reference/macros/make-condition.md)
や型付きの [`error`](../reference/macros/error.md)/[`signal`](../reference/macros/signal.md)
designator で構築し、`--no-gc`(コンパイルエラー)を除くすべてのバックエンドで
[`handler-case`](../reference/macros/handler-case.md) /
[`ignore-errors`](../reference/macros/ignore-errors.md) により型で捕捉できます。
wasm-GC バックエンドでの捕捉は WebAssembly の exception-handling プロポーザルを
使い(`wasmtime -W exceptions=y`、37+)、捕捉できるのは**シグナルされた**
コンディションのみです — ランタイムトラップは依然として中断させます。

```lisp
(handler-case (error "boom") (error (e) :caught)) ; => :caught
```

**リスタートシステム**は利用できません: `handler-bind`、`restart-case`
（主フォームだけを残す no-op として受理）、`restart-bind`、`invoke-restart`、
`with-simple-restart`、`cerror`、`abort`、`continue`、`break` は存在せず、
`check-type`/`assert` は再格納リスタートを提供せずにエラーを通知します。

## 局所マクロ（`macrolet`）

局所関数は**利用可能**です -- [`flet`](../reference/macros/flet.md) と
[`labels`](../reference/macros/labels.md) を参照してください。局所マクロ
（`macrolet`）は利用できません。マクロは `defmacro` によってトップレベルで
のみ存在します。

## `loop` マクロ

拡張版 `loop` の限定的なサブセットが **利用可能** です（[`loop`](../reference/macros/loop.md) を参照）。
数値/リストのステップ（`for`）、文字列のステップ（`for ... across`）、よく使う集約
（`collect`、`append`、`sum`、`count`、`maximize`、`minimize` など）、単純な制御節
（`while`/`until`、`repeat`、`when`/`unless`、`finally`、`return`）に対応します。
対象外は、分配束縛、`for` 節同士の並行 `and`、`being`、アナフォリックな `it`、
`named`/`loop-finish`、`thereis`/`always`/`never` です。その他の反復フォーム（`do`、`dolist`、`dotimes`、
`while`）も引き続き利用できます。

## 構造体とオブジェクト（`defstruct`、CLOS）

構造体は [`defstruct`](../reference/special-forms/defstruct.md) で **利用可能**
です。キーワードコンストラクタ、述語、コピー関数、`setf` 可能なアクセサを
生成します。`defstruct` のオプション構文（`:conc-name`、`:constructor` など）、
`:include` による継承、`#S(...)` の印字/読み取り構文はサポートされません。

**静的な CLOS サブセット**が利用可能です:
[`defclass`](../reference/special-forms/defclass.md)（単一継承、
`:initarg`/`:initform`/`:reader`/`:accessor` スロットオプション）、
[`make-instance`](../reference/macros/make-instance.md) と
[`slot-value`](../reference/macros/slot-value.md)（どちらもリテラルのクォート
された名前が必要）、そして第 1 引数でディスパッチする
[`defgeneric`](../reference/special-forms/defgeneric.md) /
[`defmethod`](../reference/special-forms/defmethod.md)（`eql`、クラス、組み込み
型の specializer）で、標準メソッド結合 — `:before`/`:after`/`:around` 修飾子、
`call-next-method`、`next-method-p`（クラスメソッドとデフォルトメソッド向け）—
も含みます。対象外: 多重継承、第 2 引数以降の specializer、`slot-boundp`、
MOP / 実行時クラス操作（`find-class`、`change-class`、`add-method`、クラス
再定義）— コンパイルされたプログラムのクラスとメソッドの集合はコンパイル時に
固定されます。

## 型宣言、`typep`、`coerce`

型宣言は解析されるだけの no-op として **受理されます**。
[`declare`](../reference/macros/declare.md)、
[`declaim`](../reference/macros/declaim.md)、
[`proclaim`](../reference/macros/proclaim.md)、
[`the`](../reference/macros/the.md) はいずれもパースされ、効果を持たないため、
型注釈付きのソースを変更なしにロードできます。
[`check-type`](../reference/macros/check-type.md) と
[`assert`](../reference/macros/assert.md) は実際のランタイム検査を提供します
（ライト版、リスタートなし）。ランタイムヘルパーの `typep` は利用できません。
[`coerce`](../reference/functions/coerce.md) はリテラルの結果型 `'list`、`'vector`、
`'string` に限り **利用できます**(結果型は `map` と同様、クォートされたリテラルで
なければなりません)。その他の結果型はサポートされません。

## ユーザー定義パッケージ

新しいパッケージは [`defpackage`](../reference/special-forms/defpackage.md) で
定義 **できます**。これは `:use`、`:export`、`:nicknames`、`:import-from` の
clause をサポートする、リテラルなトップレベルの read/コンパイル時
ディレクティブです（`:documentation`/`:size` は受理されるが無視されます。
[パッケージ](../reference/packages.md#ユーザー定義パッケージdefpackage)を参照）。
`:shadow` と `:shadowing-import-from` はエラーで(シンボルのシャドウイングは
ありません)、**ランタイム** のパッケージ操作はありません:
`make-package`、`export`、`import`、`use-package`、`find-package`、
`rename-package` は利用できません。パッケージの export(external)シンボルの
集合は定義時に固定されます。シングル/ダブルコロンの修飾子(external シンボルには
`pkg:name`、internal シンボルには `pkg::name`)は Common Lisp と同様に
機能します([パッケージ](../reference/packages.md#external-シンボルと-internal-シンボル)を参照)。
標準ニックネーム `common-lisp`/`common-lisp-user` は `cl`/`cl-user` に解決され、
`#:name` の designator も受理されます。
複数の使用先パッケージが同じ名前を export している場合、コンフリクトをシグナル
する代わりに `:use` 順で最初のパッケージが優先されます。

## 動的（special）変数束縛

動的（special）変数束縛は **サポートされています**。`defvar`、`defparameter`、
`defconstant`(および `(declaim (special *x*))`)は変数を special 宣言し、special
な名前に対する `let`/`let*` は、レキシカルではなく **動的** 束縛を確立します。
これはエクステント内で呼ばれた関数からも見え、脱出時に復元されます。

```console
> (defvar *factor* 1)
> (defun scale (n) (* n *factor*))
> (let ((*factor* 10)) (scale 5))
50
```

束縛は制御のスレッドごとに保持されるため、並行する
[`rontolisp:http-handler`](../reference/functions/rontolisp-http-handler.md)
リクエストが互いの束縛を見ることはありません。**コンパイル済み** バックエンドに
限り、2 つの制限があります(インタプリタには影響しません)。実行時に計算される
シンボルのリストを束縛する [`progv`](../reference/special-forms/progv.md) は
インタプリタのみで、JVM/WASM ではコンパイルエラーになります。また special な
`let` の境界を **越えて** 脱出する `return`/`return-from` は、そこでグローバルを
復元しません(通常の脱出とエラーによる中断は問題ありません)。

## 数値タワー

rontolisp は整数（任意精度の bignum を含む）、比（`1/3`）、倍精度浮動小数点数を
サポートしますが、**複素数はサポートしません**。負の数の平方根は、複素数の結果
ではなく浮動小数点の `NaN` を返します。

```console
> (sqrt -1)
NaN      ; full Common Lisp would return #C(0.0 1.0)
```

## その他の省略事項

`symbol-macrolet` は利用できず、`progv` はインタプリタのみです(JVM/WASM では
コンパイルエラー)。[`eval-when`](../reference/macros/eval-when.md) は `progn`
として扱われ、**利用できます**。この一覧はすべてを網羅したものではありません。rontolisp は
完全な標準ではなく、焦点を絞ったコアを実装しています。

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
| `values` / `multiple-value-bind` | 利用不可 |
| `block` / `return-from` / `tagbody` / `go` | 利用不可 |
| `catch` / `throw` / `unwind-protect` | 利用不可 |
| 条件とリスタート（`handler-case` など） | 利用不可 |
| `flet` / `labels` / `macrolet` | 利用不可 |
| `loop`（拡張版） | 一部対応（単純ループのサブセット） |
| `defstruct`、CLOS | 利用不可 |
| `declare` / `the` / `typep` / `coerce` | 利用不可 |
| `defpackage`（ユーザーパッケージ） | 一部対応（`:use`/`:export` のみ。[`defpackage`](../reference/special-forms/defpackage.md) 参照） |
| `make-package` / `export` / `use-package`（ランタイム） | 利用不可 |
| `require` / `provide` | 利用可能（[`require`](../reference/functions/require.md) 参照）。`*modules*` 変数は利用不可 |
| `let` による動的（special）束縛 | レキシカルのみ |
| 複素数 | 利用不可 |

## ユーザー定義マクロ（`defmacro`）

ユーザーマクロは**サポートされています** —
バッククォートのテンプレート構文や制限事項（`&optional`/`&key` 非対応、
ネストしたバッククォート非対応、コンパイル済みプログラムの実行時 `eval` では
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

複数の戻り値はありません。関数はちょうど 1 つの値を返します。その結果、
`floor`、`truncate`、`round`、`ceiling` は**単一の引数**を取り、整数のみを
返します。除数の引数も、2 番目の（剰余の）値もありません。

```console
> (floor 7 2)
floor expects 1 arguments, got 2
```

## 非局所脱出と制御フロー

名前付きブロックや任意のジャンプは利用できません。

- `block` / `return-from` — 名前付きブロックはありません。唯一の非局所脱出は
  `return` で、これは `do` / `do*` / `dolist` / `dotimes` によって確立された
  **最も内側**の反復ブロックから抜けます。
- `tagbody` / `go` — ラベルとジャンプによる制御フローはありません。
- `catch` / `throw` — 動的スコープの脱出はありません。
- `unwind-protect` — 脱出時のクリーンアップ保証はありません。

```console
> (block done (return-from done 1) 2)
The function block is undefined
```

## 条件とリスタート

条件システムはありません。`error` はシグナルを発しプログラムを中断しますが、
そのシグナルは**言語内から捕捉できません**。`handler-case`、`handler-bind`、
`ignore-errors`、`restart-case`、`define-condition`、`signal`、`warn` はすべて
存在しません。

```console
> (ignore-errors (error "boom"))
The function ignore-errors is undefined
```

## 局所関数（`flet`、`labels`、`macrolet`）

関数を局所的に定義することはできません。関数は `defun` によってトップレベルで
のみ存在します（または変数に束縛された `lambda` 値として）。

## `loop` マクロ

拡張版 `loop` の限定的なサブセットが **利用可能** です（[`loop`](../reference/macros/loop.md) を参照）。
数値/リストのステップ（`for`）、文字列のステップ（`for ... across`）、よく使う集約
（`collect`、`append`、`sum`、`count`、`maximize`、`minimize` など）、単純な制御節
（`while`/`until`、`repeat`、`when`/`unless`、`finally`、`return`）に対応します。
対象外は、分配束縛、`for` 節同士の並行 `and`、`being`、アナフォリックな `it`、
`named`/`loop-finish`、`thereis`/`always`/`never` です。その他の反復フォーム（`do`、`dolist`、`dotimes`、
`while`）も引き続き利用できます。

## 構造体とオブジェクト（`defstruct`、CLOS）

構造体（`defstruct`）はなく、オブジェクトシステム（`defclass`、`defgeneric`、
`defmethod`、`make-instance`）もありません。

## 型宣言、`typep`、`coerce`

型宣言は解析されません。`declare`、`declaim`、`proclaim`、`the` は利用できず、
ランタイムヘルパーの `typep` と `coerce` も利用できません。

## ユーザー定義パッケージ

新しいパッケージは [`defpackage`](../reference/special-forms/defpackage.md) で
定義 **できます**。これは `:use` と `:export` の clause のみをサポートする、
リテラルなトップレベルの read/コンパイル時ディレクティブです
（[パッケージ](../reference/packages.md#ユーザー定義パッケージdefpackage)を参照）。
それ以外の `defpackage` clause（`:nicknames`、`:shadow`、`:import-from`、
`:documentation` など）はエラーで、**ランタイム** のパッケージ操作はありません:
`make-package`、`export`、`import`、`use-package`、`find-package`、
`rename-package` は利用できません。パッケージの export(external)シンボルの
集合は定義時に固定されます。シングル/ダブルコロンの修飾子(external シンボルには
`pkg:name`、internal シンボルには `pkg::name`)は Common Lisp と同様に
機能します([パッケージ](../reference/packages.md#external-シンボルと-internal-シンボル)を参照)。
複数の使用先パッケージが同じ名前を export している場合、コンフリクトをシグナル
する代わりに `:use` 順で最初のパッケージが優先されます。

## 動的（special）変数束縛

`defvar` と `defparameter` はグローバル変数を作成しますが、rontolisp の束縛は
**レキシカルのみ**です。`let` は special 変数に対して動的束縛を確立しません。
`let` 内でグローバルを再束縛しても、そのスコープ内で呼び出される別途定義された
関数からはそれが見えません。

```console
> (defvar *factor* 1)
> (defun scale (n) (* n *factor*))
> (let ((*factor* 10)) (scale 5))
5        ; full Common Lisp would return 50
```

## 数値タワー

rontolisp は整数（任意精度の bignum を含む）、比（`1/3`）、倍精度浮動小数点数を
サポートしますが、**複素数はサポートしません**。負の数の平方根は、複素数の結果
ではなく浮動小数点の `NaN` を返します。

```console
> (sqrt -1)
NaN      ; full Common Lisp would return #C(0.0 1.0)
```

## その他の省略事項

`destructuring-bind`、`eval-when`、`symbol-macrolet`、`progv` も利用できません。
この一覧はすべてを網羅したものではありません。rontolisp は完全な標準ではなく、
焦点を絞ったコアを実装しています。

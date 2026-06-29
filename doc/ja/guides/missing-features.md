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
| `defmacro`（ユーザーマクロ） | 利用不可 |
| `&optional` / `&rest` / `&key` / `&aux` | 利用不可 |
| `values` / `multiple-value-bind` | 利用不可 |
| `block` / `return-from` / `tagbody` / `go` | 利用不可 |
| `catch` / `throw` / `unwind-protect` | 利用不可 |
| 条件とリスタート（`handler-case` など） | 利用不可 |
| `flet` / `labels` / `macrolet` | 利用不可 |
| `loop`（拡張版） | 利用不可 |
| `defstruct`、CLOS | 利用不可 |
| `declare` / `the` / `typep` / `coerce` | 利用不可 |
| `defpackage` / `export` / ユーザーパッケージ | 利用不可 |
| `let` による動的（special）束縛 | レキシカルのみ |
| 複素数 | 利用不可 |

## ユーザー定義マクロ（`defmacro`）

rontolisp ではマクロを定義できません。マクロのセットは固定で、コンパイラに
組み込まれています（`cond`、`case`、`when`、`unless`、`dotimes`、`dolist`、`do`、
`setf`、`push`、`pop`、`incf` など）。`defmacro` 自体は定義された演算子ではありません。

```console
> (defmacro square (x) (list '* x x))
The function defmacro is undefined
```

利用できるマクロを確認するには `(rontolisp:list-macros)` を実行してください。

## ラムダリストキーワード（`&optional`、`&rest`、`&key`、`&aux`）

関数は**固定数の位置パラメータ**を取ります。オプション引数、レスト引数、
キーワード引数はありません。

これは陥りやすい落とし穴です。`&rest` のようなラムダリストキーワードは
拒否されず、`&rest` という**名前**の通常のパラメータとして黙って扱われます。
そのため `(defun f (a &rest r) ...)` は可変長関数ではなく、3 パラメータの関数
（`a`、`&rest`、`r`）を定義します。

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

拡張版の `loop`（`loop for ... collect ...`）は利用できません。反復は `do`、
`dolist`、`dotimes`、`while` で行います。

## 構造体とオブジェクト（`defstruct`、CLOS）

構造体（`defstruct`）はなく、オブジェクトシステム（`defclass`、`defgeneric`、
`defmethod`、`make-instance`）もありません。

## 型宣言、`typep`、`coerce`

型宣言は解析されません。`declare`、`declaim`、`proclaim`、`the` は利用できず、
ランタイムヘルパーの `typep` と `coerce` も利用できません。

## ユーザー定義パッケージ

rontolisp にはちょうど 3 つの組み込みパッケージ — `cl`、`cl-user`、`rontolisp`
があります（[パッケージ](../reference/packages.md)を参照）。新しいパッケージは
作成できません。`defpackage`、`make-package`、`export`、`import`、`use-package`
は利用できません。`in-package` は 3 つの組み込みパッケージの間で現在のパッケージ
を切り替えるだけです。

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

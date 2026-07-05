# values

`(values form...)`

複数の値を返します。通常の（単一値の）文脈では `prog1` のように全ての引数が評価され、最初の値が結果になります。`(values)` は nil になります。余分な値はコンシューマである [`multiple-value-bind`](../macros/multiple-value-bind.md)、[`multiple-value-list`](../macros/multiple-value-list.md)、[`multiple-value-call`](../macros/multiple-value-call.md)、[`nth-value`](../macros/nth-value.md) が受け取ります — リテラルの `(values ...)` プロデューサは構文的に、ユーザ関数の結果位置にある `values` は呼び出し境界を越えて値を運ぶ内部チャネルを通じて受け渡されます。`values` を呼ばずに通常の値を返す関数は単一の値を供給します（余った変数は nil を読みます）。`funcall #'values` 経由やコンパイル済みのファーストクラス文脈からの呼び出しでは主値のみが得られます。

```lisp
(multiple-value-list (values 1 2 3)) ; => (1 2 3)
```

```lisp
(values 1 2 3) ; => 1
```

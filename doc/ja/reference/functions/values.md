# values

`(values form...)`

複数の値を返します。rontolisp には実行時の多値表現はありません: リテラルの `(values ...)` 呼び出しの全ての値を受け取れるのは、構文的なコンシューマである [`multiple-value-bind`](../macros/multiple-value-bind.md)、[`multiple-value-list`](../macros/multiple-value-list.md)、[`multiple-value-call`](../macros/multiple-value-call.md)、[`nth-value`](../macros/nth-value.md) だけです。それ以外の（単一値の）文脈では `prog1` のように全ての引数が評価され、最初の値が結果になります。`(values)` は nil になります。したがって関数末尾の `(values ...)` は呼び出し境界で主値に潰れ、呼び出し側の `multiple-value-bind` の余った変数は nil に束縛されます。

```lisp
(multiple-value-list (values 1 2 3)) ; => (1 2 3)
```

```lisp
(values 1 2 3) ; => 1
```

# subtypep

`(subtypep type1 type2)`

`type1` が `type2` のサブタイプかどうかを、組み込み型の束 (例: `integer` ⊂ `rational` ⊂ `real` ⊂ `number`、`string` ⊂ `vector` ⊂ `array`/`sequence`) とクラスレジストリの祖先集合 (`defclass`/`define-condition` 階層) に対して判定します。CL と同じく 2 値を返します: 判定結果と、それが「決定」かどうかを示す `valid-p` です (下記参照)。float・文字の型名は単一のランタイム表現に集約されるため `(subtypep 'short-float 'single-float)` は `t` です。`base-string` / `simple-base-string` も同じ理由 (文字型が 1 つ) で集約されます。一方 `simple-` 系は集約されません: fill pointer・`:adjustable t`・displacement があれば simple でない配列・文字列になるため、`simple-vector` / `simple-array` / `simple-string` は `vector` / `array` / `string` の真部分型であり、逆方向は nil です。パックド配列の要素幅である `bfloat16` ([データ型](../data-types.md)) は rontolisp の拡張で、`float` に集約されるのではなくその*下*に位置します: この型を持つスカラは存在しないため、`(subtypep 'bfloat16 'float)` は `t`、`(subtypep 'float 'bfloat16)` は nil です。

どちらの引数も型名の代わりにクラスメタオブジェクトを渡せます: [`find-class`](find-class.md) や [`class-of`](class-of.md) が返すものは自分自身のクラスを指し示すため、メタオブジェクトは型名の綴りとまったく同じように比較されます。両方の引数は実行時に計算されたものでも構いません。JVM / WASM コンパイラではリテラル (クオート) の組はコンパイル時に定数へ畳み込まれ、それ以外は同じ束の上で実行時に判定されます。4 つのバックエンドすべてが同じ答えを返します。

複合指定子はクオートでも実行時計算でも、どちらの側にも書けます。`(or ...)` はいずれかの枝のサブタイプなら真、`(and ...)` はすべての連言のサブタイプなら真です。サブ側に置いた場合は `(or ...)` がすべての枝、`(and ...)` がいずれかの連言を要求します。それ以外の頭部はサブ側では頭部そのものへ簡約されます — 制限付き指定子は頭部の部分集合を表すからです。したがって `(subtypep '(integer 0 10) 'integer)` は `t` で、ベクタに対する `(subtypep (type-of a) 'vector)` も `t` です。スーパー側で同じ簡約を行うのは不健全なので (そちら側では複合指定子のほうが小さい型です)、`(subtypep 'integer '(integer 0 10))` は nil です。`(not ...)`・`(member ...)`・`(eql ...)`・`(satisfies ...)` はこの lite 版 `subtypep` が nil を返す「未知」です。

第 2 値は CL の `valid-p` で、その判定が「決定」なのか「判断できない」なのかを表します。`t` の答えは常に決定です — 証明できたときにしか返さないからです。nil の答えは、型 *名* どうしの組であれば決定です (名前の束は完全に決定できます)。一方、どちらかが複合指定子の場合は `nil nil` (未決定) になります — 上記の複合規則は肯定方向しか証明しないからです。したがって `(subtypep '(and (cons symbol *) (cons * symbol)) '(cons symbol symbol))` は `nil nil` を返します: 実際には同じ型を表しますが、対ごとの規則ではそれを見抜けません。第 2 値を読むには多値フォーム ([`multiple-value-bind`](../macros/multiple-value-bind.md)・[`multiple-value-list`](../macros/multiple-value-list.md)・[`nth-value`](../macros/nth-value.md)) が必要で、通常の呼び出し位置では主値だけが見えます。関数オブジェクト `#'subtypep` も同じ 2 つの値を返します。差異: 未知の型 *名* に対して、ここでは `nil t` を返します (CL 処理系は `nil nil` を返すことがあります)。

```lisp
(subtypep 'integer 'number) ; => T
```

```lisp
(multiple-value-list (subtypep 'number 'integer)) ; => (NIL T)
```

```lisp
(multiple-value-list (subtypep '(satisfies evenp) 'integer)) ; => (NIL NIL)
```

```lisp
(subtypep 'type-error 'error) ; => T
```

```lisp
(defclass animal () ())
(defclass dog (animal) ())
(list (subtypep (find-class 'dog) (find-class 'animal))
      (subtypep (find-class 'animal) (find-class 'dog))) ; => (T NIL)
```

```lisp
(list (subtypep '(integer 0 10) 'integer)
      (subtypep 'integer '(integer 0 10))) ; => (T NIL)
```

```lisp
(list (subtypep 'simple-vector 'vector)
      (subtypep 'vector 'simple-vector)) ; => (T NIL)
```

```lisp
(list (subtypep 'bfloat16 'float)
      (subtypep 'float 'bfloat16)) ; => (T NIL)
```

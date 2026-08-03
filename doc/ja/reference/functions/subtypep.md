# subtypep

`(subtypep type1 type2)`

`type1` が `type2` のサブタイプかどうかを、組み込み型の束 (例: `integer` ⊂ `rational` ⊂ `real` ⊂ `number`、`string` ⊂ `vector` ⊂ `array`/`sequence`) とクラスレジストリの祖先集合 (`defclass`/`define-condition` 階層) に対して判定します。lite 版: 主値のみを返し、未知の組には nil を返します。float・文字の型名は単一のランタイム表現に集約されるため `(subtypep 'short-float 'single-float)` は `t` です。

どちらの引数も型名の代わりにクラスメタオブジェクトを渡せます: [`find-class`](find-class.md) や [`class-of`](class-of.md) が返すものは自分自身のクラスを指し示すため、メタオブジェクトは型名の綴りとまったく同じように比較されます。両方の引数は実行時に計算されたものでも構いません。JVM / WASM コンパイラではリテラル (クオート) の組はコンパイル時に定数へ畳み込まれ、それ以外は同じ束の上で実行時に判定されます。

```lisp
(subtypep 'integer 'number) ; => T
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

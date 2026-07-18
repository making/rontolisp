# subtypep

`(subtypep type1 type2)`

`type1` が `type2` のサブタイプかどうかを、組み込み型の束 (例: `integer` ⊂ `rational` ⊂ `real` ⊂ `number`、`string` ⊂ `vector` ⊂ `array`/`sequence`) とクラスレジストリの祖先集合 (`defclass`/`define-condition` 階層) に対して判定します。lite 版: 主値のみを返し、未知の組には nil を返します。float・文字の型名は単一のランタイム表現に集約されるため `(subtypep 'short-float 'single-float)` は `t` です。

現時点では**インタープリタのみ**でサポートされます。JVM / WASM コンパイラは未対応です。

```lisp
(subtypep 'integer 'number) ; => t
```

```lisp
(subtypep 'type-error 'error) ; => t
```

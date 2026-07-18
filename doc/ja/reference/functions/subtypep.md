# subtypep

`(subtypep type1 type2)`

`type1` が `type2` のサブタイプかどうかを、組み込み型の束 (例: `integer` ⊂ `rational` ⊂ `real` ⊂ `number`、`string` ⊂ `vector` ⊂ `array`/`sequence`) とクラスレジストリの祖先集合 (`defclass`/`define-condition` 階層) に対して判定します。lite 版: 主値のみを返し、未知の組には nil を返します。float・文字の型名は単一のランタイム表現に集約されるため `(subtypep 'short-float 'single-float)` は `t` です。

JVM / WASM コンパイラでは両方の型指定子がリテラル(クオート)である必要があります: 答えはコンパイル時に定数へ畳み込まれます。実行時に計算される指定子はインタープリタのみで動作します。

```lisp
(subtypep 'integer 'number) ; => t
```

```lisp
(subtypep 'type-error 'error) ; => t
```

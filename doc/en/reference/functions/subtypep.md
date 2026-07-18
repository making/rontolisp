# subtypep

`(subtypep type1 type2)`

Whether `type1` names a subtype of `type2`, answering over the built-in type lattice (e.g. `integer` ⊂ `rational` ⊂ `real` ⊂ `number`, `string` ⊂ `vector` ⊂ `array`/`sequence`) plus the class registry's ancestor sets (`defclass`/`define-condition` hierarchies). Lite: a single primary value — an unknown pair answers nil. The float and character type names collapse to the one runtime representation, so `(subtypep 'short-float 'single-float)` is `t`.

Supported on the **interpreter only** for now; the JVM and WASM compilers do not support it yet.

```lisp
(subtypep 'integer 'number) ; => t
```

```lisp
(subtypep 'type-error 'error) ; => t
```

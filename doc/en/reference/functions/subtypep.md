# subtypep

`(subtypep type1 type2)`

Whether `type1` names a subtype of `type2`, answering over the built-in type lattice (e.g. `integer` ⊂ `rational` ⊂ `real` ⊂ `number`, `string` ⊂ `vector` ⊂ `array`/`sequence`) plus the class registry's ancestor sets (`defclass`/`define-condition` hierarchies). Lite: a single primary value — an unknown pair answers nil. The float and character type names collapse to the one runtime representation, so `(subtypep 'short-float 'single-float)` is `t`.

On the JVM and WASM compilers both type specifiers must be literal (quoted): the answer is folded into a constant at compile time; runtime-computed designators work on the interpreter only.

```lisp
(subtypep 'integer 'number) ; => T
```

```lisp
(subtypep 'type-error 'error) ; => T
```

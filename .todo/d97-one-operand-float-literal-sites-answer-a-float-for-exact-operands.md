# d97. One-operand float-literal sites answer a float for exact operands

Difficulty: Medium

A float literal anywhere inside the operand (`hasDoubleLiteral` recurses into calls and
`if`) routes these sites onto the raw double path, which converts the operand whatever it
turns out to be. `+ - * / mod rem` with two or more operands no longer do: they take the raw
fold only where `isDefinitelyDouble` proves an operand (`.kb/jvm-double-arithmetic.md`,
"The exact prefix").

```lisp
(defun rf-id (x) (if (consp x) (car x) x))
(print (abs (if (rf-id nil) 1.5 -2)))
(print (- (if (rf-id nil) 1.5 2)))
(print (/ (if (rf-id nil) 2.0 4)))
(print (signum (if (rf-id nil) 1.5 -2)))
(print (expt (if (rf-id nil) 2.0 2) 3))
(print (integerp (random (if (rf-id nil) 1.0 10))))
```

Measured 2026-10-07:

| form | interpreter (= SBCL) | JVM | P1, component |
|---|---|---|---|
| `abs` | `2` | `2.0` | `2.0` |
| `(- x)` | `-2` | `-2.0` | `-2.0` |
| `(/ x)` | `1/4` | `0.25` | `0.25` |
| `signum` | `-1` | `-1.0` | `-1` |
| `expt` | `8` | `8.0` | `8` |
| `random` | `T` | `NIL` | `NIL` |

Plan: route each of these onto its raw path only when `isDefinitelyDouble` proves the
operand (the generic helper otherwise), on the JVM (`JvmArithCompiler`'s unary arms,
`JvmAbsCompiler`, `JvmSignumCompiler`, `JvmExptCompiler`, `JvmRandomCompiler`) and WASM
(`WasmArithCompiler`'s unary arms, `WasmAbsCompiler`, `WasmRandomCompiler`), and pin the rows
in a shared fixture on all four backends.

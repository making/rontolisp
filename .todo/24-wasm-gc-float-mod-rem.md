# 24 - WASM GC backend: float `mod` / `rem` miscompile

## Symptom

On the **WASM-GC** backend (`WasmLispCompiler`, e.g. `--no-wasi` / Preview 1 /
default), `mod` and `rem` with floating-point operands are broken, while the
interpreter and JVM compute them correctly:

- `(rem 4.6666 2.0)` -> the emitted module fails to even validate:
  `Invalid opcode 0xff` (an `f64` opcode of `-1` is written verbatim).
- `(mod 4.6666 2.0)` -> validates but traps at runtime with `illegal cast`.

Reproduce (Node, GC reactor):

```lisp
(defun f (s) (concatenate 'string s (princ-to-string (mod 4.6666 2.0))))
(rontolisp:wasm-export 'f :params '(:string) :returns :string)
```

```
java -jar ...-exec.jar repro.lisp -o repro.wasm --no-wasi
# node instantiation/run -> "illegal cast" (mod) or "Invalid opcode 0xff" (rem)
```

Interpreter reference: `(mod 4.6666 2.0)` => `0.6665999999999999`,
`(mod -0.3 6.0)` => `5.7`.

## Root cause

`WasmExprCompiler` dispatches both ops to
`WasmArithCompiler.compile(cons, ctx, Instruction.I32_REM_S, /*f64Opcode*/ -1, /*ratioFunc*/ -1)`.

`WasmArithCompiler.compile`:
- When `hasDoubleLiteral(args)` is true (a literal float operand), it takes the
  float path and writes `ctx.writer.write(f64Opcode)` with `f64Opcode == -1`,
  emitting byte `0xff`. WASM has **no** `f64.rem` opcode, so there is nothing
  valid to emit here -- float rem must be synthesized as `a - b*trunc(a/b)`.
- When there is no literal float (e.g. `mod` expands via `LispMacroExpander.expandMod`
  to `rem` over the gensyms `__mod_a`/`__mod_b`, which are not literals), it takes
  the integer path (`castI31GetS`) and casts the `TYPE_FLOAT` struct operands to
  `i31`, trapping with `illegal cast` at runtime.

The deeper issue: unlike `+ - * /` (which go through the `FUNC_RAT_*` runtime
helpers that dispatch on i31/ratio/float at runtime), `mod`/`rem` use an inline
i32-only path with a purely *syntactic* float check (`hasDoubleLiteral`), so a
float value reaching them through a variable is miscompiled.

## Fix direction

Give `mod`/`rem` a runtime-dispatching path like the other arithmetic ops:
add `FUNC_RAT_MOD` / `FUNC_RAT_REM` runtime helpers (or extend the existing
ratio runtime) that branch on operand type (i31 / ratio / `TYPE_FLOAT`) and, for
the float case, compute `rem = a - b*trunc(a/b)` and `mod = ((a rem b)+b) rem b`
producing a `TYPE_FLOAT`. The `--no-gc` `ScalarWasmCompiler` already emits the
float `rem`/`mod` formulas natively and can serve as a reference.

Per the project's bug-fix workflow: first add a failing cross-backend test
(`WasmLispCompilerIntegrationTest` float `mod`/`rem`, plus a `ci-spec.yaml` case
so all four backends are checked), then fix, then confirm parity with the
interpreter.

## Workaround in use

`examples/rainbow.lisp` avoids float `mod` entirely with a local floor-based
`fmod` helper (`(- a (* m (float (floor (/ a m)))))`), which lowers to plain
float arithmetic and is correct on every backend. Integer `mod` is unaffected
and still used (e.g. in `hex2`).

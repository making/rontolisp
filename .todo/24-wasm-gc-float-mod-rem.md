# 24 - WASM GC backend: float `mod` / `rem` miscompile

## STATUS: fixed (2026-06-29)

`mod` / `rem` now dispatch on operand type at runtime, like `+ - * /`. Two runtime
helpers were added -- `FUNC_RAT_REM` / `FUNC_RAT_MOD`
(`WasmRatioRuntimeBuilder.buildRatRemBody(boolean mod)`) -- appended after the hash
helpers, just before `FUNC_USER_BASE`, so no import / `FUNC_START` index shifts and
the `--component` blobs are unaffected (same technique as `FUNC_HASH`). Each helper:

- **float operand (either side)**: computes in f64 -- `rem = a - b*trunc(a/b)`
  (`f64.trunc`), `mod = a - b*floor(a/b)` (`f64.floor`) -- boxed as `TYPE_FLOAT`.
- **both i31 integers**: fast i32 path (`i32.rem_s`, plus the divisor-sign
  correction `(r ^ b) < 0` for `mod`, which avoids the r*b i31 overflow).
- **otherwise (ratio)**: exact `a - b*(trunc|floor)(a/b)` via the existing
  `FUNC_RAT_DIV` / `FUNC_RAT_TRUNC` / `FUNC_RAT_FLOOR` / `FUNC_RAT_MUL` /
  `FUNC_RAT_SUB` helpers -- so the GC backend now also handles ratio `mod`/`rem`.

`WasmExprCompiler` routes both `MOD` and `REM` through
`WasmArithCompiler.compileModRem`, which just compiles the two operands and calls
the helper (no `hasDoubleLiteral` special-casing -- there is no single f64 modulo
opcode). The dead i32-only path and the unused `i32Opcode` parameter of
`WasmArithCompiler.compile` were removed.

Verified on all four backends (interpreter / JVM / WASM Preview 1 / WASM component)
via local wasmtime 46 and the native-image `CiSpecE2eTest` (new
`float-and-negative-mod-rem` case). Tests:
`WasmLispCompilerIntegrationTest#floatModAndRemComputeCorrectly` (float + negative
integer mod/rem under wasmtime) and the ci-spec case. The full suite (1611 tests)
passes. `examples/rainbow.lisp` now uses the built-in `mod` directly (the
floor-based `fmod` workaround was removed).

NOTE: a leftover cross-backend divergence -- the interpreter and JVM `mod`/`rem`
still throw on a ratio operand (e.g. `(mod 1/2 1/3)`), which WASM now computes
(`1/6`). That is a pre-existing interpreter/JVM gap, not part of this fix; out of
scope here.

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

## Former workaround (removed)

Before the fix `examples/rainbow.lisp` avoided float `mod` with a local
floor-based `fmod` helper (`(- a (* m (float (floor (/ a m)))))`). Now that the
built-in float `mod` is correct on every backend, that helper was removed and the
example calls `mod` directly.

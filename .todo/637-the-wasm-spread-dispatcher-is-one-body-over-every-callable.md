# The WASM spread dispatcher is one body over every callable

Difficulty: Medium

Filed 2026-09-02 while closing `.todo/630`, whose one new `ci-spec.yaml` case tripped
`CiSpecE2eTest`'s function-body guard on the `--component` build:

```
refusing to run wasmtime: largest emitted function body of guard.component.wasm is
262597 bytes, over the 262144 byte bound
```

## What the body is

Not a top-level chunk (`WasmToplevelEmit` cuts those at 48 KB) and not a user defun: it
is the SPREAD dispatcher `WasmRuntimeBuilder.buildDispatchBody(..., spread = true, ...)`,
the one `_apply` calls -- function index 80 of the corpus's core module, a `br_table`
over EVERY callable in the program (about 3000 in the concatenated corpus) with one case
body each, ~110 bytes a case. It grows with the program's function count, not with
anything a test author can see, and at `develop` before this item it stood at **261821
bytes against the 262144 bound**: 323 bytes, about three callables, of headroom. The
per-arity dispatchers are far smaller (the next largest body in the module is a 72 KB
user function).

Rewriting the new case with `defmacro` + `macrolet` instead of three defuns and four
lambdas brought the corpus back to 261823 (the core build's largest is 258601). That is a
workaround; the next case anyone adds with a handful of lambdas fails the same way, and
the failure names a body bound nobody changed.

## What to build

Two candidates, measurable on the corpus with `WasmModuleInspector`:

- **Page the table.** An outer dispatcher `br_table`s on `funcId >> 8` to per-page
  dispatchers of at most 256 cases; each page is a function of the spread signature plus
  the funcId. Bounded body size whatever the program, one extra call per `apply`.
- **Give the dispatcher only the functions the program can reach as a VALUE.** The
  `dispatchable` parameter exists for exactly this and is always `null`; its javadoc names
  a `WasmLispCompiler.dispatchableFuncIds` that no longer exists. A defun that is only
  ever called directly needs no case. This shrinks the corpus's table by most of its
  rows but does not bound it.

Either way, `WasmToplevelChunkingTest` should gain a case that pins the dispatcher's body
against a program with a few thousand callables, the way it pins the two top levels.

# `car`/`cdr` as a shared callee at the size level (`WasmConsRuntimeBuilder`)

**Invariant: a `car`/`cdr` site and the shared `_car`/`_cdr` body answer alike -- outside EH mode
they spell ONE shape**
(`WasmEmitHelper.emitInlineConsField`: `local.get x; ref.is_null; if (result eqref)
ref.null eq else local.get x; ref.cast $cons; struct.get $cons k end` -- nil answers nil, a
cons its field, anything else traps on the cast), so a site that calls the body answers
exactly what the inline spelling answered. Which spelling a site gets is decided by the
level alone, never by the program:

| level | a plain local operand | any other operand | `apply`'s argument walk |
| --- | --- | --- | --- |
| `off`, `default` | inline, read in place (17 B) | spilled into a fresh temp, inline (21 B + a local) | inline, per parameter |
| `size` | operand + `call _car` (3-4 B) | same | `local.get; call` per parameter |

- **EH mode checks** ([error-handling.md](error-handling.md), "A wrong-type argument names its
  operator"): there the bodies are CHECKED (nil answers nil, a non-list is `CAR`'s/`CDR`'s
  type-error through `_type_err_list`) and an inline site is `local.get x; ref.test $cons; if
  (result eqref) local.get x; ref.cast $cons; struct.get else local.get x; call _car end` -- the
  checked body is its slow path, so the two still answer alike. A size-level site is unchanged.
  The double type test costs a tight traversal +28% (`.todo/979`).
- `FUNC_CAR`/`FUNC_CDR` (`TYPE_CALLABLE_BASE + 0`, `WasmLispCompiler`) are appended after the
  last fixed helper, so no fixed index moves; unreferenced at every level but `size`, and
  the shaker drops them there (`WasmLispCompilerTest.aConsAccessSiteIsOneCallAtTheSizeLevelAndReadsAPlainLocalInPlaceOtherwise`
  pins the body shipping exactly once at `size` and not at all at `default`).
- **The "read in place" licence is `WasmExprCompiler.plainLocalSlot`**: exactly the
  variables `compileSymbolRef` compiles to one bare `local.get` -- not a special (a
  dual-bound special reads its global), not a `rawLocals` entry (a boxing read), not a
  `boxedVars` cell (an unbox). Anything else goes through `compileExpr` and a temp, as
  before. A new kind of local read that is not a bare `local.get` must be excluded there
  or the fast path reads the wrong thing silently.
- `WasmCarCompiler`/`WasmCdrCompiler`/`WasmApplyCompiler` go through
  `WasmEmitHelper.compileConsField`/`emitConsField`; the spread dispatcher's per-parameter
  walk (`WasmRuntimeBuilder.emitNullSafeCell`) takes the same switch as `sharedConsReaders`,
  threaded through `buildDispatch` from `WasmLispCompiler.optimize`. `_dispatch_spread` on
  the hello-clack Worker: 86,004 -> 46,926 B.
- **Why `size` only.** A call per `car` is a speed trade, and `default` makes none
  (`.kb/optimize-dead-code-elimination.md`, "What `SIZE` declines"): measured 2026-09-12 on
  a 16M-`car`/`cdr` list-traversal loop (wasmtime 47, best of five), the shared reader at
  the default level costs +1.4% (1.45 -> 1.47 s) and would have gained 2.4 KB on `zlib`
  (2.1%) and 49 KB on the hello-clack Worker (5.0%).
- Temps are still a fresh local per computed operand (`Ctx.allocTemp` never recycles). The
  BYTES that cost -- a two-byte index for every local from 128 up -- are recovered by the
  local renumbering (`.kb/optimize-dead-code-elimination.md`, "The local renumbering"), which
  made a scratch slot unnecessary for size; the frames stay wide.

## Measured 2026-09-12 (before -> after)

| Program | `--optimize=size` | `--optimize` |
| --- | --- | --- |
| `zlib` `--no-wasi` (size-report) | 94,069 -> **90,874** (-3.4%; gzip 31,869 -> 30,861) | 117,558 -> 116,605 (-0.8%) |
| hello-clack Worker `--no-wasi` | 916,587 -> **815,414** (-11.0%; gzip 227,415 -> 215,040) | 1,003,618 -> 990,451 (-1.3%) |
| `hello_world`, `pi_approx`, the `.todo/789` reactor, `webgl-triangle` | unchanged (no cons access) | unchanged |

The `--optimize` column is the temp-free plain-local read alone. What the size level
gains beyond an external optimizer: `wasm-opt -Oz` finds the same 182,697 B on the Worker
before and after, i.e. the 101 KB is outside its reach (it does not outline).

## Tests

`WasmLispCompilerIntegrationTest.consAccessAnswersTheSameAtEveryLevel` (nil, a cons's
field, a special, a parameter and a `do`-stepped local as operands, `apply`'s walk exact
and with a rest tail, `funcall` of a computed designator, and `(car 5)` trapping through
the shared reader outside EH mode), `WasmTreeShakerCorpusTest` (now compiles the corpus at `size` too:
validate + shortest-encoding round trip), the byte-budget pin above.

# 806: the i32 tier that was measured and rejected, and the void finding that came out of it

Numbers and the reading: `.kb/no-gc-scalar-wasm.md` ("There is no i32 tier, and there must
not be one" and the VOID half of "Value model and inference"). The item is closed and its
text is recoverable from the deletion history; this directory is how to reproduce it.
The benchmark and the JS host are in
[`../805-no-gc-literal-import-call-sites/`](../805-no-gc-literal-import-call-sites);
use `bench.lisp` from there.

## The i32 spike

`spike.diff` applied to a scratch worktree at `fcb0e8738`, `spike.py` the script that
applies and reverts it. Module-wide and deliberately crude: every `INT` becomes `i32`,
every `i64.*` opcode its `i32.*` twin, `i64.extend_i32_*` / `i32.wrap_i64` disappear,
`emitBoundaryRangeGuard` returns 0; a second flag relaxes `isPassThroughExport`.

With both flags off the patched compiler reproduced the baseline byte for byte, and after
the revert so did the pristine source -- run that check first if you re-apply it.

| | total | code (functions) | types |
| --- | ---: | ---: | ---: |
| baseline | 1,383 | 491 (20) | 129 (24) |
| i32 tier, guards dropped | 1,310 | 418 (20) | 129 |
| + `:s32` pass-through | 1,260 | 382 (18) | 118 |

`AddNumbers`'s wrapper (53 bytes for `a+b`) and `RunComputation`'s (50) disappear entirely;
the internal function is exported directly.

## Why it is rejected, and how to see it yourself

`wrap.lisp` + `wrap-host.mjs` are the demonstration, and they are the reason none of those
123 bytes are available:

| call | baseline | spiked | exact |
| --- | --- | --- | --- |
| `Square(65536)` | trap | 0 | 2^32 (the boundary rejects it) |
| `SquareMod(65536)` = `(mod (* n n) 1000)` | 296 | **0** | 296 |
| `FactDigits(13)` = `(mod (fact n) 10000)` | 800 | **3504** | 800 |
| `FactDigits(20)` | 0 | 7264 | 0 |

**Every boundary value there fits `:s32`; only an intermediate crosses 2^31.** No guard at
the boundary can see it, and the shapes are exactly the ones
`doc/*/guides/wasm-nogc.md` names as this backend's purpose. Today's wrap point at 2^63 is
documented and out of reach for them; 2^31 is reached by `13!`.

A range analysis does not rescue it: it would have to bound every integer-valued expression
in a body, not just the parameters and the result. `(+ a b)` of two `:s32` needs 33 bits, so
`add-numbers` fails immediately; `fib` needs a bound on a recursive function's growth, and
interval widening gives the top element at the first recursive `+`. The analysis succeeds
on comparisons and indices -- where the tier is worth about two bytes -- and fails wherever
the bytes are.

**Do not re-derive this.** If an i32 tier is ever wanted it has to be an explicit
declaration, so that the wrap point is the user's stated contract the way `:s32` already is
at the boundary.

## What came out of it instead, and landed

The internal VOID the spike exposed: a fourth point in the value lattice, so a form in
statement position produces nothing and a `:void` boundary is `(...) -> ()` on both sides.
Landed 2026-09-13 -- the reactor above went 953 -> 931 B at `--optimize=size` with zero
`i64.const 0` / `drop` pairs left and `InitApp` reaching `isPassThroughExport`. Two
predictions this directory's first draft made did NOT hold and are corrected in
`.kb/no-gc-scalar-wasm.md`: `AppendLogMessage` keeps its wrapper whatever happens to the
void (its `:s32` parameter needs an `i64.extend_i32_s`), and most of the win is not at the
import boundary at all but in `while` and in the dead `nil` the `cond` expansion leaves.

`sizes.mjs` prints the per-function table the numbers above come from.

# wasm-GC integer arithmetic wraps at the `i31` range and reports a wrong number

On the wasm-GC backends (Preview 1, `--no-wasi` and `--component`) an integer
result that leaves the `i31` range is silently reduced modulo 2^31. The
interpreter and the JVM promote; the WASM backends do not, and they do not trap
either -- they return a different number.

## Symptoms

    (defun bump (n) (+ n 1))
    (print (bump 1073741823))   ; interp/JVM -> 1073741824   wasm-GC -> -1073741824
    (print (* 46341 46341))     ; interp/JVM -> 2147488281    wasm-GC -> 4633
    (print (bump 2000000000))   ; interp/JVM -> 2000000001    wasm-GC -> -147483647

The `4633` case is the worst shape: the wrapped value is positive and small, so
nothing downstream looks suspicious. `--no-gc` computes in `i64` and returns
every one of these correctly.

## Why it matters more now

The export boundary carries the value exactly or traps
(`.kb/wit.md`, "The integer boundary"). A `u32` export whose body overflows
therefore stops the call:

    ;; adder.wit: add: func(x: u32, y: u32) -> u32;
    wasmtime run -W gc=y --invoke 'add(1073741823, 1)' comp.wasm
    # wasm trap: integer overflow      <- the boundary refusing the wrapped -1073741824

That is the boundary doing its job -- the wrong number stops instead of being
reported as `3221225472` -- but the trap points at the boundary while the defect
is upstream, in `+`. The user-visible effect is an export that works for
`add(1073741824, 1)` and traps for `add(1073741823, 1)`, which reads as a
boundary bug until you know the arithmetic wraps.

## Root cause

Integers are `i31ref` on this backend and arithmetic is plain `i32.add` /
`i32.mul` on the unboxed payload, re-boxed with `ref.i31`, which truncates to
the low 31 bits. There is no overflow check and no promotion.

The backend already HAS a representation for an integer past the `i31` range:
the **wide-integer float box** (`TYPE_FLOAT`), which holds every integer below
2^53 exactly. It is what the clock built-ins return
(`.kb/time-environment-builtins.md`), what `WasmComponentImportCompiler.boxI64`
lifts a wide component-model integer into, and -- since the integer boundary
work -- what an `s32`/`u32` export parameter past the `i31` range is boxed as.
Arithmetic is the one place that never learned the convention.

## Options

1. **Promote on overflow** -- detect the overflow (`i32.add` + a sign-comparison
   guard, or `i64` intermediates) and box the result as the wide-integer float
   when it does not fit. Consistent with the boundary and the import side, and
   exact to 2^53. Costs a branch on every integer `+`/`-`/`*`; measure against
   the numeric benchmarks (`.kb/vec.md`, the deep-learning port) before
   committing, and check whether `--optimize` can drop the guard where a range
   is known.
2. **Trap on overflow** -- honest, cheap to reason about, but turns programs
   that work today into programs that stop, with no way to write the
   computation at all on this backend.
3. **Document only** -- the status quo. `doc/**` already says the WASM backend
   uses 31-bit integers with no promotion (e.g.
   `doc/{en,ja}/reference/functions/mul.md`), so this is defensible, but a
   silently wrong small positive number is the failure mode users cannot see.

Option 1 is the one that matches the rest of the backend. Whichever is chosen,
it must behave the same on Preview 1, `--no-wasi` and `--component`, and the
`--no-gc` backend must stay as it is (its house integer is already `i64`).

## Verification

- A ci-spec case whose expected output is the same on all four backends for a
  value that crosses 2^30 -- today such a case cannot exist, which is the point.
- The numeric benchmarks, before and after, on both JVMs (`.kb/vec.md`).
- The export boundary's trap must disappear for `add(1073741823, 1)` once the
  arithmetic promotes, without any change to the boundary code.

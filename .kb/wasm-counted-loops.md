# WASM counted loops (`dotimes` over a literal bound)

**Invariant: a counted loop's induction variable is an `i64` slot with NO boxed
shadow, and the eligibility scan is the whole proof that nothing can observe it
any other way.** `WasmDotimesCompiler`, wasm-GC backend (Preview 1 AND
`--component`; the interpreter, the JVM and `--no-gc` keep the macro expansion).

Unlike the two speed-for-size trades next door (`.kb/wasm-int-fusion.md`,
`.kb/wasm-unboxed-locals.md`) this one is **not gated on `--optimize=size`**: it
emits strictly less code AND runs strictly faster, because the representation it
replaces is not "boxed but cheap", it is "boxed plus a generic comparison call
plus a re-box, per iteration".

## What the general lowering costs

`LispMacroExpander.expandDotimes` -- shared by every backend -- produces

```lisp
(%block (let ((i 0) (__dotimes_limit n))
          (while (< i __dotimes_limit) body... (setq i (+ i 1)))
          result))
```

On wasm-GC that is, per iteration: a `_rat_cmp_bits` call for `(< i limit)`, a
`_t_sym` call to materialise the `t` it answers, a `ref.is_null` to test that
answer, and a fused-but-guarded `(+ i 1)` whose result re-boxes into an `eqref`.
The unboxed-local machinery does not rescue it -- at top level it is switched
off entirely (the eval mirror), and where it IS on, the dual representation adds
a boxed shadow, a per-leaf guard and a duplicated generic fallback, which is a
speed trade `--optimize=size` declines.

## What the counted loop emits

An empty `(dotimes (i 1000000))` is the whole `_start` chain of a **203-byte
module** (it was 1,987):

```wat
block (result eqref)          ;; the %block dotimes wraps its expansion in
  i64.const 0
  local.set $i
  block
    loop
      local.get $i
      i64.const 1000000
      i64.ge_s
      br_if 1                 ;; exit
      <body, for effect>
      local.get $i
      i64.const 1
      i64.add
      local.set $i
      br 0
    end
  end
  ref.null eq                 ;; or the result form
end
```

No overflow check on the step and no range test on a read, because the literal
bound decides both statically.

## The counted `RawLocal`

The variable is registered in `ctx.rawLocals` -- the same map the dual
representation uses -- as `WasmIntFusionCompiler.RawLocal.counted(slot)`, whose
`shadowSlot` is `-1` (the record's canonical constructor enforces
"counted ⇔ no shadow"). Registering it there rather than in a map of its own is
what makes shadowing, `defvar` collision and Lisp-2 symbol resolution work with
no new rules: `WasmExprCompiler.compileSymbolRef` consults raw locals before
ordinary locals, captures and module globals, and `WasmLetCompiler` already
removes a shadowed outer registration whatever the new binding's representation.

The four places that branch on `counted()`:

| site | dual | counted |
| --- | --- | --- |
| `evalLeaves` | snapshot (i64, shadow) into scratch slots | nothing; `snapI64` IS the counted slot |
| `emitLeafUnboxes` | sentinel test, then the i31/TYPE_BIGNUM guarded unbox | nothing |
| `emitFallback` | sentinel test, then `_int_new` or the shadow | `i32.wrap_i64; ref.i31` |
| `emitRawLocalBoxedRead` | the `_ub_read` call | `i32.wrap_i64; ref.i31` |

The snapshot exists so a later leaf's side effect cannot change what an earlier
read of the same local observed; a counted variable has no assignment anywhere
in the loop, so there is nothing to snapshot. That it reads RAW inside a fused
tree is the speed half: `(- i 2)` index math and `(+ s i)` accumulation stop
paying a guard per leaf, and a comparison against it fuses
(`tryCompileCompare`).

`compileRawStore` **throws** on a counted target rather than assuming: the only
way to reach it is an assignment the eligibility scan below missed.

## Eligibility

All of these must hold; anything else falls back to `expandDotimes` verbatim:

- the count is a **literal integer** in `[0, 2^30 - 1]`. The ceiling is the i31
  range, and it is the COUNT that has to fit, not just the last index: the
  result form sees the variable holding the count. A non-literal bound is the
  standing widening question -- see the trigger below;
- the variable is a non-keyword symbol and is not special;
- **nothing in the body or the result form assigns it.** The AST reaching a
  backend has every user macro already expanded (`UserMacroExpander`), so the
  set of operators that can write a variable is CLOSED: `setq`/`psetq`/`setf`/
  `psetf`/`multiple-value-setq`/`incf`/`decf`/`push`/`pushnew`/`pop`/`rotatef`/
  `shiftf`/`remf` plus the `defvar` family. For each, the scan takes that
  operator's PLACE arguments and asks what writing through the place writes: a
  bare symbol is the variable; a place FORM writes its own sub-place
  (`(setf (getf pl k) v)` stores back through `pl`, `(setf (ldb spec x) v)`
  through `x`, `(setf (aref s i) c)` through `s` when `s` turns out to be a
  string). For the indexed accessors the sub-place is a known argument and the
  rest are pure subscript/key READS -- which is what keeps the common
  `(dotimes (i 16) (setf (aref buf i) 0))` eligible; any other accessor is
  treated as writing anything it mentions. The scan is blind to lexical scope
  and to quoting, which can only narrow eligibility;
- the variable is not captured by a nested lambda (`FreeVarAnalyzer`) -- a
  capture needs a cell;
- not in an async body (`ctx.asyncResume`: the spill machinery owns locals
  there), not under `--dynamic` (variables resolve through the environment), and
  not at top level in a program that uses `eval`: there the ordinary lowering's
  `(setq i ...)` MIRRORS each step into the eval global environment
  (`WasmSetqCompiler.mirrorTopLevelGlobal`), which a slot cannot be read from.
  Top level WITHOUT `eval` is eligible, and `pi_approx` is exactly that case.

The i64 slot is allocated from `ctx.nextI64Local` before the body and the
watermark restored after it, so fused sites inside the body allocate above it
and their own save/restore cannot reuse it (`.kb/wasm-unboxed-locals.md`, "the
i64 locals run").

## Measured

wasmtime 47.0.2, `--optimize` unless stated, 2026-08-08.

| probe | before | after |
| --- | ---: | ---: |
| `(dotimes (i 1000000))` alone | 1,987 | **203** |
| `wasm-size/pi_approx`'s loop + `(princ "done")` | 2,530 | **1,770** |
| `wasm-size/pi_approx` (`--optimize`) | 3,540 | **2,781** |
| `wasm-size/pi_approx` (`--optimize=size`) | 3,420 | **2,781** |

Speed, a 100,000,000-iteration `(dotimes (i n) (setq s (+ s i)))` (best of three):

| level | before | after |
| --- | ---: | ---: |
| `--optimize` | 3.49 s | **0.36 s** |
| `--optimize=size` | 4.06 s | **2.27 s** |

The `--optimize` figure is bigger than the counter alone explains: with `i`
reading raw, the accumulator's whole `(+ s i)` tree stays on the fused i64 path
instead of guarding and unboxing a leaf per iteration.

Checked-in browser artifacts moved -0.5% (`webgl-battlefront`) to -8.9%
(`wasm-browser/dice`); `hiragana/infer` and `rainbow` are byte-identical (no
literal-bound `dotimes`).

## Pinning tests

`WasmLispCompilerIntegrationTest.countedDotimesLoopsMatchTheExpandedLowering`
(the result form's value, `return` out of the body, a nested same-name loop, an
assigned counter falling back, a captured counter falling back, an array fill
whose subscript is the counter) and the `counted-dotimes` ci-spec case (all four
backends).

## Re-evaluation triggers

- **A non-literal bound is the open half.** It cannot reuse this shape as it
  stands: a runtime count needs a guard, and a guard needs a generic loop to
  bail into, which means emitting the body twice -- a speed-for-size trade,
  which is exactly what this item is not. The way in would be a runtime
  "count to i64" coercion that reproduces `(< i n)`'s semantics for a float or a
  ratio bound exactly; measure before believing it pays.
- **A counted read feeding a float coercion still round-trips through a box.**
  `pi_approx`'s `(* 2.0 i)` emits `i32.wrap_i64; ref.i31` and then calls
  `_as_f64` on it. An `f64.convert_i64_s` straight off the slot would delete two
  instructions and a call per iteration; it needs `castFloatGetF64` to learn
  about raw operands, so it is a `.kb/wasm-shared-coercion.md` change, not one
  here.
- If the counted flavour ever needs a THIRD behaviour at one of the four
  `counted()` branches, that is the signal that `RawLocal` should become a
  sealed interface with two implementations rather than a record with a flag.

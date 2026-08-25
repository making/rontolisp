# WASM counted loops (`dotimes`, and a `let` + `while` induction variable)

**Invariant: a counted loop's induction variable is an `i64` slot with NO boxed
shadow, and the eligibility scan is the whole proof that nothing can observe it
any other way.** Two entry points, wasm-GC backend (Preview 1 AND `--component`;
the interpreter, the JVM and `--no-gc` keep the macro expansion):

- `WasmDotimesCompiler` -- a `dotimes` over a LITERAL bound, which it emits
  whole (its own `block`/`loop` pair, below);
- `WasmCountedLoopCompiler` -- a `let` binding that a `while` in the body steps,
  which is what `loop`'s numeric `for` head lowers to
  (`.kb/loop-iteration-heads.md`) and what a hand-written loop spells out. Here
  the ordinary `WasmLetCompiler`/`WasmWhileCompiler` emit the loop; only the
  variable's REPRESENTATION changes.

Unlike the two speed-for-size trades next door (`.kb/wasm-int-fusion.md`,
`.kb/wasm-unboxed-locals.md`) this one is **not gated on `--optimize=size`**: the
loop head always shrinks AND always runs faster, because the representation it
replaces is not "boxed but cheap", it is "boxed plus a generic comparison call
plus a re-box, per iteration". There is no shadow, no per-leaf guard and no
duplicated generic fallback to pay for it with.

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

The five places that branch on `counted()`:

| site | dual | counted |
| --- | --- | --- |
| `evalLeaves` | snapshot (i64, shadow) into scratch slots | nothing; `snapI64` IS the counted slot |
| `emitLeafUnboxes` | sentinel test, then the i31/TYPE_BIGNUM guarded unbox | nothing |
| `emitFallback` | sentinel test, then `_int_new` or the shadow | `i32.wrap_i64; ref.i31` |
| `emitRawLocalBoxedRead` | the `_ub_read` call | `i32.wrap_i64; ref.i31` |
| `WasmSetqCompiler.compileForEffect` | `compileRawStore` (raw fast path or shadow) | `WasmCountedLoopCompiler.compileStep` -- the one `i64.add` |

The snapshot exists so a later leaf's side effect cannot change what an earlier
read of the same local observed; a counted variable's only assignment is its own
step, which the loop's own iteration separates from every read, so there is
nothing to snapshot. That it reads RAW inside a fused tree is the speed half:
`(- i 2)` index math and `(+ s i)` accumulation stop paying a guard per leaf,
and a comparison against it fuses (`tryCompileCompare`).

`compileRawStore` **throws** on a counted target rather than assuming, and
`compileStep` throws on any value shape that is not the proven step: between
them, an assignment the eligibility scan below missed fails the compile loudly
instead of inventing a representation there is no shadow to hold.

## Eligibility: `dotimes`

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

## Eligibility: a `let` + `while` induction variable

`WasmCountedLoopCompiler.analyze` runs on every `let` the wasm backend compiles.
By then a `let*` is nested `let`s, so `loop`'s numeric head presents as the
OUTERMOST binding with the `while` several bodies down -- the scan looks at the
whole `let` form, not at its immediate body. A binding qualifies when all of
this holds; anything else just keeps the ordinary representation, with no other
change to how the loop is emitted:

- the init is a **literal integer** within `±(2^30 - 1)`;
- the name is a non-keyword symbol, not special, not duplicated in the same
  binding list, and not captured by a lambda in the body (`capturedInLet`);
- the whole `let` form holds **exactly one** assignment of it -- the same
  place-writing scan the `dotimes` list above describes, which lives in this
  class now and `WasmDotimesCompiler` calls -- and that assignment is
  `(setq VAR (+ VAR n))`, `(+ n VAR)` or `(- VAR n)` with a literal non-zero
  `n`;
- that `setq` is a **direct element of a `while`'s body list**. That is
  statement position, the only position a counted variable can be assigned in at
  all: there is no boxed value to hand back, so
  `WasmSetqCompiler.compileForEffect` routes it to `compileStep` and every other
  route reaches `compileRawStore`, which throws;
- that `while`'s own test **bounds** the variable: a comparison against a
  literal integer, under any number of `not`s, possibly as one conjunct of an
  `and`. Any conjunct is enough -- failing it ends the loop before the step runs
  again. Negating a comparison is sound because both operands are integers (the
  variable by construction, the other by this very test), so there is no NaN to
  make an operator and its negation both false; and the recursion into `and`
  happens only on the POSITIVE side, since a negated conjunction is a
  disjunction of negations and a bound from one disjunct proves nothing;
- the two extremes then have to box exactly as an i31: the init, and one step
  past the bound. That second one is the value that FAILS the test, and it is
  observable -- `(loop for i from 1 to 4 finally (return i))` is 5 -- which is
  why the range proof cannot stop at the limit.

The context gates are the dual representation's minus the `--optimize=size` one:
`--dynamic`, an async body and a top-level body that creates a closure are all
out. `WasmLetCompiler` decides the counted binding BEFORE the dual one --
counted is the stronger representation (no shadow at all), so it wins the
binding.

### The exit test

`WasmComparisonCompiler.tryCompileConditionI32` takes a `negated` request and
answers a `not` by flipping it rather than by emitting an `i32.eqz`. The numeric
head's test is spelled `(not (> i limit))`, so `while` gets its exit condition
as one bare `i64.gt_s` feeding `br_if` -- the same two instructions
`WasmDotimesCompiler` emits by hand. This is general and it is more than an
inversion saved: before, the `not` wrapper pushed the WHOLE test onto the boxed
path (a `_rat_cmp_bits` or fused compare boxing t/nil, a `not` call, a null
test), so every `while`/`if` over a negated comparison now fuses where it did
not.

## Measured

### `dotimes`

wasmtime 47.0.2, `--optimize` unless stated, 2026-08-08.

| probe | before | after |
| --- | ---: | ---: |
| `(dotimes (i 1000000))` alone | 1,987 | **203** |
| `size-report pi_approx`'s loop + `(princ "done")` | 2,530 | **1,770** |
| `size-report pi_approx` (`--optimize`) | 3,540 | **2,781** |
| `size-report pi_approx` (`--optimize=size`) | 3,420 | **2,781** |

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

### The `let` + `while` head (todo-521)

wasmtime 47.0.3, 2026-08-25. `(loop for i from 1 to 100000000 sum i)` inside a
`defun`, best of three:

| level | before | after | the `dotimes` twin |
| --- | ---: | ---: | ---: |
| `--optimize` | 0.78 s | **0.32 s** | 0.30 s |
| `--optimize=size` | 5.81 s | **3.62 s** | -- |

That is the whole gap this was filed for: the idiomatic CL spelling used to cost
2.4x its own `dotimes` and now matches it (SBCL 2.6.5 does it in 0.208 s).

| module | before | after |
| --- | ---: | ---: |
| `(loop for i from 0 below 1000000)` alone | 2,070 | **1,262** |
| the `sum` benchmark above | 9,812 | **9,468** |
| a 1,000-iteration `(setq s (+ s (* i i)))` loop in a `defun` | 10,419 | **10,065** |

At `--optimize=size` the same three are 1,884 -> **1,367**, 9,381 -> 9,383 and
9,929 -> 9,934: the loop head itself always shrinks, and the two +2/+5-byte
programs are the counted reads boxing at sites that fusion would otherwise have
consumed.

Rebuilding the browser examples with and without the change moves five of the
thirteen, all down: `minesweeper` -340, `battlefront` -705, `heat3d` -232,
`platformer` -80, `robot-arm` -790 bytes; the rest are byte-identical. The
CHECKED-IN copies were left alone -- they are stale against HEAD's compiler for
unrelated reasons (a `wasm-browser/dice` rebuild is +60% over the committed
file), so refreshing them here would have buried this change's five deltas in
that drift.

## Pinning tests

`WasmLispCompilerIntegrationTest.countedDotimesLoopsMatchTheExpandedLowering`
(the result form's value, `return` out of the body, a nested same-name loop, an
assigned counter falling back, a captured counter falling back, an array fill
whose subscript is the counter) and the `counted-dotimes` ci-spec case (all four
backends).

`WasmLispCompilerIntegrationTest.countedNumericForHeadsMatchTheExpandedLowering`
and the `counted-numeric-for` ci-spec case do the same for the `let` + `while`
head: the accumulator, an exclusive limit, a descending `by` step, the value
`finally` sees ONE STEP past the limit, `return` out of the body, a nested
same-name head, an indexed store subscripted by the variable, and the five
refusals -- assigned, captured, a non-integral limit, a limit at the i31
ceiling, and the hand-written `let` + `while` spelling that must answer the
same. Both run at `--optimize=size` too.

## Re-evaluation triggers

- **A non-literal bound is still the open half**, and todo-521 re-decided it the
  same way for `loop`'s head, where a computed limit
  (`for i from 0 below (length v)`) is common. It cannot reuse this shape: a
  runtime limit needs an entry guard ("is it an integer inside the i31 range?"),
  and the guard's failing arm needs a generic loop to bail into, which means
  emitting the body TWICE. Every no-duplication alternative was considered and
  none is sound -- clamping the limit changes which iterations run; a generic
  exit test still needs the counter itself bounded; trapping instead of bailing
  turns a legal (if absurd) program into an error. So it is a speed-for-size
  trade after all, and the way in is to gate the duplicated version on
  `WasmIntFusionCompiler.speedTradesEnabled` exactly as
  `.kb/wasm-unboxed-locals.md` gates its own duplicated fallback, keeping the
  variable's post-loop reads on ONE representation (write the counted slot back
  into the boxed local at the counted arm's exit). Measure before believing it
  pays.
- **A counted read feeding a float coercion still round-trips through a box.**
  `pi_approx`'s `(* 2.0 i)` emits `i32.wrap_i64; ref.i31` and then calls
  `_as_f64` on it. An `f64.convert_i64_s` straight off the slot would delete two
  instructions and a call per iteration; it needs `castFloatGetF64` to learn
  about raw operands, so it is a `.kb/wasm-shared-coercion.md` change, not one
  here.
- If the counted flavour ever needs a THIRD behaviour at one of the five
  `counted()` branches, that is the signal that `RawLocal` should become a
  sealed interface with two implementations rather than a record with a flag.

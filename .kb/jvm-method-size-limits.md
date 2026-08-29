# JVM backend: no emitted method may outgrow the 64 KB code limit (branches past the 16-bit offset relax to goto_w), and no class its 65534-entry constant pool

Scope: the **JVM backend** (`codegen.jvm`). The WASM sibling constraint (one
function body's superlinear compile cost) is
[wasm-function-body-size.md](wasm-function-body-size.md); this file is about
HARD format limits, not performance.

## The invariant

Two JVM class-file ceilings bound every emitted method:

- **A branch offset is a signed 16 bits** (`goto`/`if*`) — but since todo-256
  this is RELAXED, not a hard limit: `am.ik.jvm.BranchRelaxer` rewrites each
  out-of-range branch as a `goto_w` (an unconditional `goto`) or as its
  inverted-condition short branch over a `goto_w` (a conditional), iterating
  the sizing to a fixpoint because widening moves every later offset, then
  remapping the exception table. The mechanics: an out-of-range
  `JvmEmitHelper.patchBranch` records the pair in `Ctx.deferredBranches`
  instead of throwing, and `JvmLispCompiler` runs the relaxer over every
  Ctx-compiled body (main, top chunks, defuns, lambdas) just before assembly.
  A method with no deferred branch is untouched, byte for byte. The raw-list
  `JvmRuntimeBuilder.patchBranch` still throws: the runtime builders
  (dispatch/lookup segments) stay under budget by construction and never
  defer. `StackMapAugmenter` decodes `goto_w` (its `interpret` treats it like
  `goto`). fast-http's generated `parse-header-field-and-value` (36.7 KB body)
  is the real-world trigger.
- **A method's code array is at most 65535 bytes** (JVMS 4.7.3): nothing can
  rescue a larger body — not even `goto_w`. This one remains hard:
  `am.ik.jvm.ByteCodeWriter.writeCode` rejects an over-limit body loudly.

`-Drontolisp.jvm.debug-method-sizes=true` prints the 40 largest emitted
defun/lambda bodies plus every computed-typep expansion — run that first when a
large program trips the 64 KB guard.

### The third signed-16-bit field: `sipush` (2026-08-07)

`sipush` takes a SIGNED 16-bit operand, so `JvmEmitHelper.emitIntConst` truncated
and sign-extended anything outside `[-32768, 32767]` — silently, into a class that
VERIFIES and computes the wrong number. It is the same failure shape as an
out-of-range branch offset, minus the guard.

The reachable case is a CHARACTER above the BMP: a Lisp character is an `int[]{cp}`
holding a full code point (`.kb/characters-code-points.md`), so
`(string (code-char 128512))` pushed 0x1F600 and got -2560
(`IllegalArgumentException: Not a valid Unicode code point: 0xFFFFF600` from
`Character.toString`). It was latent because a supplementary code point only ever
reached that emitter as a computed value until the pure-builtin literal fold
(`.kb/pure-builtin-fold.md`) started producing folded `#\U+1F600` LITERALS — the
"a newly reachable path finds the latent bug" case, found by
`JvmLispCompilerTest.compileCharBeyondBmpCodePoint` and `Uax15E2eTest`.

`emitIntConst` now falls through to `ldc`/`ldc_w` of an `Integer` constant, which
also covers its counting callers (lambda ids, quoted-vector lengths, slot indices —
each of which would need an absurd program to reach 32768, but shares the fix). The
pool-free `JvmRuntimeBuilder.emitIntConstStatic` cannot mint a constant, so it
throws loudly instead, exactly like `JvmRuntimeBuilder.patchBranch`.

## What is kept bounded, and how

The bodies that grow with PROGRAM or REGISTRY size are each bounded by
construction (measured at cl-postgres scale — 165 registered classes, ~2600
defuns/lambdas — where every one of these had crossed or neared a limit):

- **Registry-proportional expansions** (computed `typep` 37 KB/site, runtime
  `subtypep` 59 KB, computed-`error` dispatch 90 KB) became shared injected
  defuns over quoted DATA tables; mechanics in [clos.md](clos.md). The shared
  defun must itself stay bounded: `%error-runtime` was one cond over every
  condition class, and a cond lowers to NESTED ifs, so the outermost arm's
  else-branch spans every remaining arm and hit the signed-16-bit BRANCH limit
  (~195 bytes/class, overflow at 143 classes -- long before the 64 KB code
  limit the segmentation budgets were sized for). Chained since todo-247
  (`%error-runtime` → `%ER-1` → ..., the generated-Lisp `chainedDispatchDefuns`
  shape, all four backends; todo-211's measurement). The AMBIGUOUS literal
  `slot-value` dispatch had the same registry-proportional shape inlined PER
  SITE and now outlines onto the shared `%slot-value(-set)-runtime` defuns
  ([clos.md](clos.md), the mito-core batch).
- **`_invoke_<arity>`** (the indirect-call dispatcher, one `if` case per
  callable of that arity — variadics match every arity above their required
  count; 66 KB at arity 9): `JvmRuntimeBuilder.buildDispatchMethods` splits the
  case chain into chained segments (`_invoke_9`, `_invoke_9$1`, ...) of ~24 KB,
  each falling through to the next with the already-resolved function value.
- **`_invoke_v`** (the SPREAD dispatcher, one case per callable — no per-arity
  duplication, because the case reads its target's required parameters out of the
  argument LIST): the same `buildDispatchMethods` with `spread` set, so it is
  segmented by the same budget. It exists because `_apply` cannot be expressed
  with the per-arity family: those take one physical parameter per Lisp argument
  and therefore stop at `MAX_CALLABLE_ARITY` (7), and `_apply` used to walk the
  argument list, count it and fall off the end of that ladder — silently
  answering nil for an 8+-argument `apply` through a COMPUTED designator (the
  WASM sibling, `FUNC_DISPATCH_SPREAD`, trapped instead). A variadic target gets
  the remaining TAIL verbatim, which is its physical rest parameter. Note the
  size argument: it is CHEAPER than raising the per-arity ceiling would be, since
  a variadic function matches every arity at or above its required count and so
  costs one case PER ARITY in the old family.
- **`_lookup`** (the eval registry's name-to-funcId chain, one string-equals
  per defun; 60 KB): `JvmEvalRuntimeBuilder.buildLookupSegments` splits it the
  same way (`_lookup`, `_lookup$1`, ...).
- **The top level** was already chunked (`_top$0`, `_top$1`, ... under a 40 KB
  budget in `JvmLispCompiler` Pass 2b) — but a chunk is cut only BETWEEN forms,
  so any single form must itself stay bounded; that is why the injected data
  tables are emitted as multiple `defvar`/`setq`-append forms of 48 entries.

Per-arm branches inside a dispatch chain are LOCAL (each case's `if` skips only
its own case), so a chain can legally approach 64 KB without any branch
overflowing — the segmentation budgets (24 KB) leave slack for both limits.

A single enormous USER defun still hits the 64 KB guard — same stance as the
WASM sibling: there is no way to outline what the user wrote as one function.
The loudly-thrown error names the real cause; only the BRANCH ceiling inside a
sub-64 KB body relaxes away silently.

## The third ceiling: 65534 constant pool entries per class

`constant_pool_count` is a u2, so the last usable index is 65534
(`ConstantPool.MAX_INDEX`). This one is NOT per method — it is per class, and it
is the limit a data-heavy program reaches first, because **every distinct
integer literal costs TWO entries** (an integer is a boxed `long`, i.e. a
`CONSTANT_Long`, and a long takes two pool slots). Roughly 25,000 distinct
numbers in one program is enough on top of a library like cl-postgres.

Crossing it used to be diagnosed at the WRONG place. Every emit site writes an
index as `(short) index`, so index 65832 was written as 296 and the instruction
silently referenced an unrelated entry; the first thing to notice was the
operand-stack model, which read a `Fieldref`'s `Ljava/lang/Object;` as if it
were an argument list and reported `operand-stack model: underflow at 31`
inside an unrelated cl-ppcre lambda. `ConstantPool.add` now refuses the entry
that would cross the limit (counting both slots of a long/double before
accepting it), so the failure names the real cause; `OperandStack.invoke` also
rejects an operand whose entry is not a method descriptor, as a backstop for any
other way an index could go wrong. `toByteArray`'s check is now unreachable and
kept only as a serialization-time backstop.

The design consequence for generated data: bulk tables travel inside STRING
literals decoded at run time, not as thousands of literals of their own. The
`--optimize` chunking that keeps a data form under the 64 KB METHOD limit (one
`defun` per 250 entries) does nothing for the pool — it makes it worse, by
adding a name and descriptor per chunk. `Uax15Tables`' derived forms are built
this way for exactly this reason (`.kb/asdf.md`; they are derived forms, not
`ShimLibraries` leaf modules — uax-15 has none of those). Its constraints were
that the literals are **many short chunks, not a few long ones** (`(char s i)`
was then O(i) on the compile paths, so scanning one long literal was quadratic —
no longer true since `.todo/185`, `.kb/string-index-cost.md`) and that the scan
runs on **first read of the table, not at load**, so a program that never
normalizes never pays for it.

**Read the text back; do not scan it** (`ClUnicodeTables`, todo-545). cl-unicode
is 25x uax-15's problem — ~140,000 numbers and ~68,000 character names, ~208,000
pool entries against the 65534 ceiling — and the decimal-run shape does not
survive that size. A Lisp-level character scan costs ~8 µs PER CHARACTER
interpreted, which is 60 s of load for these tables, while `read-from-string`
over the same data is ~1.5 µs per ELEMENT because the reader is native on all
four backends. (Everything else measured on the way there is slower still:
`parse-integer` 36 µs a call, `remove` on a 22-character string 156 µs, and
`position` with `:start` is quadratic because it materializes the string as a
list per call.) So the generated components carry each table as its own PRINTED
TEXT — `("LATIN CAPITAL LETTER A" . 65) ...` — inside ~230 string literals and
read it back: 570 pool entries instead of 208,000, and a load no slower than the
literal dump's. Two constraints on the cut, both real: a string constant may not
exceed 65535 UTF-8 bytes, and the reader recurses per element (a 30,000-element
chunk overflows the stack), so a chunk is capped at 20,000 characters AND 1,000
elements.

A package-qualified symbol read at run time is `eq` to the literal the compiler
resolved — checked on all four backends, and what lets a range table's VALUES be
text too rather than an index into a literal symbol vector. What the technique
costs is the READER, which now travels into any program holding such a table:
+390 KB of class and ~2,450 pool entries, measured on a program whose only
content is one `read-from-string`. For a library that is itself megabytes of data
that is the cheaper half of the trade; for a small table it is not, which is why
uax-15's runs are left as they are.

## Symbol function designators (same session, adjacent seam)

`(funcall f args...)` where `f` holds a SYMBOL at run time (cl-postgres passes
`'list-row-reader` through `exec-query`) resolves through `_lookup` in the
dispatcher's segment 0: a String funcval is looked up (its `Object[]{funcId,
arity}` record carries the id in slot 0 exactly like a function value), a miss
throws `The function X is undefined` — the interpreter's late binding.
`_lookup` is therefore emitted whenever the program has indirect calls, not
only under eval. The WASM dispatch mirrors this: a `TYPE_STRING` funcval
resolves through the wasm `_lookup` (the eval registry's name-offset scan;
without eval that is the always--1 stub and the miss arm traps). A funcall
whose ARITY exceeds the WASM backend's fixed dispatch range
(`MAX_CALLABLE_ARITY` = 7) compiles to a call-time signal instead of silently
calling the neighboring runtime helper (which produced an invalid module).

## Pinning tests

`JvmLispCompilerTest#compileAndRunABranchSpanningPastTheSigned16BitOffset`
(the relaxer end to end: a 36 KB then-branch),
`JvmLispCompilerTest#compileAndRunTypepWithComputedSpecifier` /
`#compileAndRunErrorWithComputedConditionType` /
`#compileAndRunEmptyPrognIsNilInValuePosition`, the
`runtime-type-dispatch-and-symbol-designators` ci-spec case, and — as the
scale witness — the cl-postgres full stack (quickloads + all driver files)
compiling and running a live query on the JVM backend (todo 115's session
records). The 255-local-slot ceiling is no longer a ceiling: past it a load or
store takes the `wide` prefix and a two-byte index (todo-562,
[stackmap-augmenter.md](stackmap-augmenter.md), "The `wide` prefix"), and the
hard limit is the u2 `max_locals`, which `Ctx.allocTemp` refuses to cross by
name. What is left of `.todo/137` is the slot COUNT -- a temporary's slot is
never reused, which now costs bytes rather than correctness. The pool ceiling is pinned by
`am.ik.jvm.ConstantPoolTest#refusesTheEntryThatWouldCrossTheFormatLimit` /
`#refusesATwoSlotEntryThatWouldStraddleTheFormatLimit`, and the read-back-text
answer to it by `ClUnicodeTablesTest` (the emitted shape, and the decoders run
through the evaluator over a chunked table with a hole at the top of the code
space).

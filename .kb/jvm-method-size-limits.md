# JVM backend: no emitted method may outgrow the 64 KB code limit (branches past the 16-bit offset relax to goto_w), and no class its 65534-entry constant pool

Scope: JVM backend (`codegen.jvm`), HARD format limits. WASM sibling (per-body compile cost):
[wasm-function-body-size.md](wasm-function-body-size.md).
`-Drontolisp.jvm.debug-method-sizes=true` prints the 40 largest defun/lambda bodies plus every
computed-typep expansion -- run it first when a large program trips the guard.

## The four format limits

- **Branch offset, signed 16 bits** -- RELAXED. `am.ik.jvm.BranchRelaxer` rewrites an
  out-of-range branch as `goto_w`, or (conditional) as an inverted short branch over a
  `goto_w`, iterating sizing to a fixpoint and remapping the exception table.
  `JvmEmitHelper.patchBranch` records the pair in `Ctx.deferredBranches` instead of throwing;
  `JvmLispCompiler` runs the relaxer over every Ctx-compiled body (main, top chunks, defuns,
  lambdas) before assembly. No deferred branch => byte-identical output. The raw-list
  `JvmRuntimeBuilder.patchBranch` still throws (builders stay under budget by construction).
  `StackMapAugmenter.interpret` treats `goto_w` like `goto`.
- **Code array <= 65535 bytes** (JVMS 4.7.3). HARD; `am.ik.jvm.ByteCodeWriter.writeCode`
  rejects loudly. A single enormous USER defun cannot be outlined.
- **`sipush` operand is signed 16 bits.** `JvmEmitHelper.emitIntConst` used to truncate
  silently into a class that VERIFIES and computes the wrong number. Reachable via a character
  above the BMP (a Lisp character is an `int[]{cp}`, `.kb/characters-code-points.md`):
  `(string (code-char 128512))` gave -2560. Latent until the pure-builtin literal fold
  (`.kb/pure-builtin-fold.md`) produced folded `#\U+1F600` LITERALS. Now falls through to
  `ldc`/`ldc_w` of an `Integer`; the pool-free `JvmRuntimeBuilder.emitIntConstStatic` cannot
  mint a constant and throws.
- **65534 constant-pool entries per CLASS** (`ConstantPool.MAX_INDEX`). Every distinct integer
  literal costs TWO entries (boxed `long` = `CONSTANT_Long`); ~25,000 distinct numbers on top
  of a library like cl-postgres is enough. Emit sites write `(short) index`, so crossing it
  used to alias an unrelated entry and surface far away (`operand-stack model: underflow`).
  `ConstantPool.add` now refuses the entry that would cross (counting both slots of a
  long/double first); `OperandStack.invoke` rejects a non-method-descriptor operand as a
  backstop; `toByteArray`'s check is serialization-time only.

## Bodies bounded by construction

- Registry-proportional expansions (computed `typep` 37 KB/site, runtime `subtypep` 59 KB,
  computed-`error` dispatch 90 KB) are shared injected defuns over quoted DATA tables
  ([clos.md](clos.md)). The shared defun must stay bounded too: a cond over every condition
  class lowers to NESTED ifs, whose outermost else-branch spans every remaining arm and hit the
  BRANCH limit at ~195 bytes/class (143 classes), far below 64 KB. Hence chained dispatch --
  `%error-runtime` -> `%ER-1` -> ..., the generated-Lisp `chainedDispatchDefuns` shape on all
  four backends. Ambiguous literal `slot-value` dispatch outlines onto shared
  `%slot-value(-set)-runtime`.
- `_invoke_<arity>` (one `if` case per callable of that arity; variadics match every arity
  above their required count; 66 KB at arity 9) is split by
  `JvmRuntimeBuilder.buildDispatchMethods` into chained ~24 KB segments (`_invoke_9$1`, ...)
  falling through with the already-resolved function value.
- `_invoke_v` (SPREAD dispatcher, one case per callable, reading required parameters out of the
  argument LIST) uses the same method with `spread` set. Needed because the per-arity family
  takes one physical parameter per argument and stops at `MAX_CALLABLE_ARITY` (7): `_apply`
  used to fall off that ladder and silently answer nil for an 8+-argument `apply` through a
  COMPUTED designator (WASM's `FUNC_DISPATCH_SPREAD` trapped instead). A variadic target gets
  the remaining TAIL verbatim. Cheaper than raising the per-arity ceiling.
- `_lookup` (eval registry name-to-funcId chain, one string-equals per defun; 60 KB) is split by
  `JvmEvalRuntimeBuilder.buildLookupSegments` (`_lookup$1`, ...).
- The top level is chunked (`_top$0`, ..., 40 KB budget, `JvmLispCompiler` Pass 2b), but a chunk
  is cut only BETWEEN forms, so injected data tables are emitted as multiple
  `defvar`/`setq`-append forms of 48 entries.

Per-arm branches inside a dispatch chain are LOCAL, so a chain can approach 64 KB without a
branch overflowing; the 24 KB budget leaves slack for both limits.

## Generated data must not become pool entries

Bulk tables travel inside STRING literals decoded at run time. `--optimize` chunking (one
`defun` per 250 entries) helps the 64 KB METHOD limit but makes the pool WORSE (a name and
descriptor per chunk). `Uax15Tables`' derived forms are built this way (`.kb/asdf.md`; derived
forms, not `ShimLibraries` leaf modules), in many SHORT chunks, scanned on FIRST READ of the
table rather than at load.

**Read the text back; do not scan it** (`ClUnicodeTables`): ~140,000 numbers + ~68,000
character names = ~208,000 pool entries. A Lisp-level character scan costs ~8 us PER CHARACTER
interpreted (~60 s of load); `read-from-string` is ~1.5 us per ELEMENT because the reader is
native on all four backends. So each table travels as PRINTED TEXT --
`("LATIN CAPITAL LETTER A" . 65) ...` -- in ~230 string literals: 570 pool entries, load no
slower. Hard cut constraints: a string constant may not exceed 65535 UTF-8 bytes, and the
reader recurses per element, so a chunk is capped at 20,000 characters AND 1,000 elements. A
package-qualified symbol read at run time is `eq` to the literal the compiler resolved (all
four backends), so a range table's VALUES can be text too. Cost: the READER travels into any
program holding such a table (+390 KB class, ~2,450 pool entries) -- worth it for megabytes of
data, not for a small table, which is why uax-15's runs stay runs.

## Symbol function designators

`(funcall f ...)` where `f` holds a SYMBOL at run time (cl-postgres passes `'list-row-reader`
through `exec-query`) resolves through `_lookup` in dispatcher segment 0: a String funcval is
looked up (its `Object[]{funcId, arity}` carries the id in slot 0 like a function value), a miss
throws `The function X is undefined`. `_lookup` is therefore emitted whenever the program has
indirect calls, not only under eval. WASM mirrors it: a `TYPE_STRING` funcval goes through the
wasm `_lookup` (without eval that is the always--1 stub and the miss arm traps); a funcall past
`MAX_CALLABLE_ARITY` (7) compiles to a call-time signal instead of calling the neighbouring
runtime helper (which produced an invalid module).

## Local slots (was a silent trap, now relaxed)

`ALOAD`/`ASTORE` once carried a one-byte index, so slot 256 became slot 0 -- a wrong answer, not
a crash, when the wrapped slot had the same type. Reachable because `ctx.nextLocal` only grows
(a temp is never freed) and a boxed `setq` costs one temp per SITE: a few hundred straight-line
assignments into a CAPTURED variable is ~4 KB of code, under the 8000-byte `HugeMethodLimit`
that `AstOutliner` reacts to, so nothing rescued the frame; the low slots are load-bearing
(slot 0 = first parameter, 1..n = the enclosing `let`'s capture cells). Past 255 a load/store
now takes the `wide` prefix and a two-byte index
([stackmap-augmenter.md](stackmap-augmenter.md), "The `wide` prefix"); the hard limit is the u2
`max_locals`, which `Ctx.allocTemp` refuses to cross by name. Still open: a temporary's slot is
never reused (costs bytes, not correctness).

## Pinning tests

- `JvmLispCompilerTest#compileAndRunABranchSpanningPastTheSigned16BitOffset`
- `JvmLispCompilerTest#compileAndRunTypepWithComputedSpecifier`,
  `#compileAndRunErrorWithComputedConditionType`, `#compileAndRunEmptyPrognIsNilInValuePosition`
- `JvmLispCompilerTest#aCapturedLetVariableAssignedInlineInASiblingBranchPastTheSlotCeiling`
- `JvmLispCompilerTest.compileCharBeyondBmpCodePoint`, `Uax15E2eTest`
- `am.ik.jvm.ConstantPoolTest#refusesTheEntryThatWouldCrossTheFormatLimit`,
  `#refusesATwoSlotEntryThatWouldStraddleTheFormatLimit`
- `ClUnicodeTablesTest`; ci-spec `runtime-type-dispatch-and-symbol-designators`; scale witness:
  the cl-postgres full stack running a live query on the JVM backend.

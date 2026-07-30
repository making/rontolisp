# JVM backend: no emitted method may outgrow the 16-bit branch / 64 KB code limits, and no class its 65534-entry constant pool

Scope: the **JVM backend** (`codegen.jvm`). The WASM sibling constraint (one
function body's superlinear compile cost) is
[wasm-function-body-size.md](wasm-function-body-size.md); this file is about
HARD format limits, not performance.

## The invariant

Two JVM class-file ceilings bound every emitted method:

- **A branch offset is a signed 16 bits** (`goto`/`if*`): any single branch
  spanning more than 32767 bytes cannot be encoded. `GOTO_W` exists in
  `am.ik.jvm.Opcode` but no emitter uses it and there is no relaxation pass.
- **A method's code array is at most 65535 bytes** (JVMS 4.7.3): nothing can
  rescue a larger body — not even `GOTO_W`.

Both used to fail SILENTLY: `JvmRuntimeBuilder.patchBranch` truncated the
offset to a short (surfacing later as a bewildering
`StackMapAugmenter: Index -31123 out of bounds`), and an over-64 KB body
surfaced only at class-load time as `ClassFormatError: Invalid method Code
length`. Both now throw AT THE SOURCE: `patchBranch` rejects an overflowing
offset, and `am.ik.jvm.ByteCodeWriter.writeCode` rejects an over-limit body.
`-Drontolisp.jvm.debug-method-sizes=true` prints the 40 largest emitted
defun/lambda bodies plus every computed-typep expansion — run that first when a
large program trips either guard.

## What is kept bounded, and how

The bodies that grow with PROGRAM or REGISTRY size are each bounded by
construction (measured at cl-postgres scale — 165 registered classes, ~2600
defuns/lambdas — where every one of these had crossed or neared a limit):

- **Registry-proportional expansions** (computed `typep` 37 KB/site, runtime
  `subtypep` 59 KB, computed-`error` dispatch 90 KB) became shared injected
  defuns over quoted DATA tables; mechanics in [clos.md](clos.md).
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

A single enormous USER defun still hits the guards — same stance as the WASM
sibling: there is no way to outline what the user wrote as one function. The
loudly-thrown error now names the real cause.

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

The design consequence for generated data: encode bulk numeric tables as STRING
literals scanned at run time, not as thousands of numeric literals. The
`--optimize` chunking that keeps a data form under the 64 KB METHOD limit (one
`defun` per 250 entries) does nothing for the pool — it makes it worse, by
adding a name and descriptor per chunk. `Uax15Tables`' derived forms are built
this way for exactly this reason (`.kb/asdf.md`; they are derived forms, not
`ShimLibraries` leaf modules — uax-15 has none of those).

Two follow-on constraints that came out of the same data, both in `.kb/asdf.md`:
the literals are **many short chunks, not a few long ones** — `(char s i)` is
O(i) on the compile paths, so scanning one long literal is quadratic — and the
scan runs on **first read of the table, not at load**, so a program that never
normalizes never pays for it.

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

`JvmLispCompilerTest#compileAndRunTypepWithComputedSpecifier` /
`#compileAndRunErrorWithComputedConditionType` /
`#compileAndRunEmptyPrognIsNilInValuePosition`, the
`runtime-type-dispatch-and-symbol-designators` ci-spec case, and — as the
scale witness — the cl-postgres full stack (quickloads + all driver files)
compiling and running a live query on the JVM backend (todo 115's session
records). The 255-local-slot ceiling is the remaining unguarded sibling
(`.todo/137`). The pool ceiling is pinned by
`am.ik.jvm.ConstantPoolTest#refusesTheEntryThatWouldCrossTheFormatLimit` /
`#refusesATwoSlotEntryThatWouldStraddleTheFormatLimit`.

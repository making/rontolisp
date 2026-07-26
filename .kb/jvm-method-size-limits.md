# JVM backend: no emitted method may outgrow the 16-bit branch / 64 KB code limits

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
(`.todo/137`).

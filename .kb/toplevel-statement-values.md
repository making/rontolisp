# A top-level form is a STATEMENT: nothing may be emitted only to be dropped

Scope: both compile paths (`codegen.wasm` and `codegen.jvm`). The interpreter is
unaffected -- it discards a top-level form's value without building anything.

`_start` (wasm) and `main` (JVM) drop the value of every top-level form. So a value a
top-level form produces *only because a form has to produce something* is code no run can
observe. `am.ik.rontolisp.compiler.ToplevelStatements` is where the two shapes that do
exactly that are recognized, once, for both backends:

| shape | what it is | what happens |
| --- | --- | --- |
| the form IS a constant | `'CHIPZ` (what `PackageResolver` leaves for `in-package`/`defpackage`), `nil` (an unselected `eval-when`, a `declaim`), a stray docstring, a keyword | `ToplevelStatements.prune` deletes the form from the top-level list |
| the form is a name-valued definer | `defvar`/`defparameter`/`defconstant` -- bind, then return the name symbol | the top-level emitter OFFERS the form the chance to emit no name; the defvar compiler takes it |

### The offer, and why it is an offer

The definer is still compiled through the ordinary `compileExpr`, so it keeps its
source-position attribution (`SourceProvenance.noteFailure`, wrapped in `compileCons`) and,
on wasm, its async-spine handling. What changes is one context field,
`Ctx.definerNameDropped`, set to the form itself just before the call:
`Wasm`/`JvmDefvarCompiler` clears it and skips the name; the emitter then reads it back and
emits the `drop`/`pop` only if the offer was NOT taken.

That read-back is the point. The offer is keyed on the operator name, and the two
backends' special-form dispatch is a `switch` on the RAW symbol name -- so a spelling that
passes the offer's test but is not routed to the defvar compiler (a package-qualified one,
a future user-macro interception) leaves its value on the stack, and the emitter still
drops it. Getting that wrong does not mis-optimize, it emits an invalid module; the
protocol is arranged so the failure cannot happen rather than so it is unlikely. The
identity key (`== cons`, not a boolean) is what keeps a definer nested inside the init
expression from taking an offer meant for its parent.

**This is not behind `--optimize`.** Neither shape is a speed/size trade with a losing
side: the removed code cannot be observed at any level. Same standing as
[pure-builtin-fold.md](pure-builtin-fold.md) -- always on, both compile paths.

**Only constants are pruned.** A bare non-keyword symbol at top level is NOT effect-free:
evaluating it can signal an unbound-variable error, and signalling is an effect. Neither
is a call whose arguments happen to be literals -- whether *that* is pure is
`PureBuiltinFolder`'s question, and anything it answers arrives here already folded to a
constant and is caught by the first rule. `prune` only DELETES, so the cons-identity rule
source positions depend on ([source-positions.md](source-positions.md)) holds trivially.

## The audit that produced it (zlib, `--optimize=size`)

The program is three forms -- one `ql:quickload "chipz"`, one `let*` reading stdin, one
`write-sequence` -- and its top-level chunk was **7,020 B, the single largest function in
the artifact**, with 159 `call` instructions of which **71 were `_str_build`**.
Reading the chunk back (`-Drontolisp.wasm.debug-func-sizes=1` for the name,
`wasm-tools print` for the body) answered the three questions the item asked:

- **How many were the same string built twice?** 40 of 71: `CL-USER` 26 times and `CHIPZ`
  14 times, at ONE static offset each. The string table's deduplication was working
  perfectly -- it deduplicates *bytes in the data section*, and it had. What it cannot
  deduplicate is the *build*: `_str_build(off,len)` copies `linear[off..off+len)` into a
  fresh `$str_bytes` array on every call ([wasm-gc-strings.md](wasm-gc-strings.md)), so N
  occurrences of one name are N allocations no matter how few bytes back them.
- **How many were symbol names interned for a name registry / an accessor spelling / a
  `PRINT-OBJECT` label?** **None.** Every one of the 71 was a form's discarded RETURN
  value: 40 the quoted package name each `(in-package ...)` / `(defpackage ...)` resolves
  to -- the splice brackets every file of the system, so they arrive as repeated
  `CL-USER`, `CL-USER`, `CHIPZ` runs -- 30 the
  names of chipz's `defconstant`s and `defparameter`s, one the `'chipz` left by
  `ql:quickload`. The 71st -- `CHIPZ:GZIP`, the argument to `decompress` -- was the only
  live one. So there is **no overlap with the report-floor work**: nothing here is
  interned because a registry row needed it.
- **Were they constant-foldable?** The question does not arise, and that is the finding.
  A fresh mutable string must not be shared, so folding these into one shared instance
  would have been wrong ([pure-builtin-fold.md](pure-builtin-fold.md)). But **70 of the 71
  were followed immediately by `drop`** -- a value nobody reads does not need to be
  correct, it needs to not exist.

## Measured

zlib, `--optimize=size`, before -> after:

| | before | after |
| --- | ---: | ---: |
| top-level chunk body | 7,020 B | **6,206 B** (-11.6%) |
| `_str_build` calls in the chunk | 71 | 1 (the live one) |
| `_str_build` call sites, whole module | 227 | 157 |
| static data section | 8,229 B | 7,453 B |
| `eqref` locals in the chunk | 146 | 117 |
| module | 109,290 B | **107,695 B** (-1.46%) |

Roughly half the win is code (the `i32.const off; i32.const len; call; drop` at each site)
and half is data: a name that nothing references any more is not emitted at all, and 30 of
chipz's constant names average 26 bytes. Every `zlib` row of
[`size-report/results/wasm-flags.md`](../size-report/results/wasm-flags.md) moved by about
the same absolute amount (-1,645 plain, -1,640 at `--optimize`, -1,595 at
`--optimize=size`, -1,644 for the component), and the rows' gunzip check still passes byte
for byte. `hello_world` and `pi_approx` are byte-identical -- they have no such form.

### The dead `local.tee` that came with it

A top-level `defvar` staged its assigned value in a temp local so
`WasmSetqCompiler.mirrorTopLevelGlobal` could read it back -- but that mirror emits nothing
unless the program uses `eval` at top level, so a program that does not eval paid a
`local.tee` AND a local per top-level binding for a reader that was not there. The tee is
now emitted only when `WasmSetqCompiler.mirrorsTopLevelGlobal(ctx)` says the mirror will
be. `setq` keeps its tee unconditionally and must: there the staged value is the form's own
result. That change alone is the 29 locals and 202 of the 1,595 bytes above.

## What is left in that chunk, and why it is not this item's

6,206 B, and still the largest body in the artifact. It is now almost entirely chipz's own
data: the `defparameter`s for `*fixed-block-code-lengths*`, `*fixed-literal/length-table*`
and friends build their vectors element by element (91 `array.set`), and the CRC32 /
distance tables behind them are genuine program content. There is no further dead value in
it -- 1 `_str_build`, 6 `ref.null eq; drop` inside nested expressions. Shrinking it
further means changing how a large literal vector is CONSTRUCTED (the
[packed-integer-vectors.md](packed-integer-vectors.md) baking already does this for
`(unsigned-byte N)` element types; these are general vectors), not removing dead code.

## Re-evaluation trigger

The prune list is deliberately short -- constants and the three definers. Widen it only
for a shape whose evaluation provably cannot signal: that is the whole safety argument, and
"the function looks pure" is not it. If a NEW definer is added whose value is nothing but
the name it bound, it belongs in `isNameValuedDefiner`, and its backend compiler has to
take the offer the way the defvar compilers do, or its name will be built and dropped like
these were.

## Pinned by

- `ToplevelStatementsTest` (`compiler`) -- what is pruned, what is not, that surviving
  forms keep their identity, and the definer/rebind classification.
- `WasmLispCompilerTest.aTopLevelFormThatIsNothingButAConstantEmitsNothing` -- a program
  padded with constant top-level forms compiles BYTE-identically to one without them.
- `WasmLispCompilerTest.aTopLevelDefinerDoesNotBuildTheNameSymbolItReturns` -- lengthening
  a top-level `defparameter`'s name does not change the module's size, and the name does
  not appear in the bytes.
- `JvmLispCompilerTest.aDefinerWhoseValueIsReadStillYieldsTheNameSymbol` -- the for-effect
  path is the top-level statement position and nothing else; read the value and the form
  still answers its own name.

## Related

- [pure-builtin-fold.md](pure-builtin-fold.md) -- the other always-on compile-time
  reduction; it is what turns a constant-argument call into the constant this pass then
  deletes.
- [optimize-dead-code-elimination.md](optimize-dead-code-elimination.md) -- the
  `--optimize` shakers, which work by NAME reachability and could never see a value that
  is built and immediately dropped inside one live body.
- [wasm-gc-strings.md](wasm-gc-strings.md) -- why `_str_build` allocates per call site
  even when the bytes behind it are shared.
- [wasm-function-body-size.md](wasm-function-body-size.md) -- the chunker whose one
  top-level function this shrinks.

# `--optimize` (levels; dead-code elimination, WASM + JVM)

Opt-in (CLI `--optimize[=LEVEL]`; `WasmLispCompiler(dynamic, component, noWasi, optimize)` / `JvmLispCompiler(className, dynamic, optimize)` / `NoGcWasmCompiler(optimize)`, all taking a `compiler.OptimizeLevel`).

## The levels

**Invariant: the bare `--optimize` is `DEFAULT` and emits exactly the bytes it always did.** It is in every doc page, every `.kb` passage, the CI jobs, the examples and the README, so it is not a level that may be redefined later. Pinned by `OptimizeLevelTest.theBareFlagIsTheDefaultLevel` (`parse("")` -> `DEFAULT`) plus `CliOptionsTest.theBareFlagKeepsItsEmptyValue`; measured directly, `postgres-hello --component` at `--optimize` and at `--optimize=default` byte-identical at 7,609,611.

> **Absolute byte counts in this file were re-measured 2026-08-07**, after `.kb/wasm-shortest-encoding.md` took 2.4%-5.7% out of every module the project emits, and again the same day after todo-271 unpinned the printer prologue (another ~18% of a hello core). The three tables the changes moved (the level table below, the literal-fold table, the component breakdown) carry fresh numbers. Where a number is part of a HISTORICAL progression -- a "before X / after X" pair measured in one earlier session -- it is left as it was and marked, because the pair's ratio is what it records and neither half can be rebuilt.

| spelling | `eliminatesDeadCode()` | `prefersSizeOverSpeed()` |
| --- | --- | --- |
| (flag absent) — `NONE` | no | no |
| `--optimize`, `--optimize=default` — `DEFAULT` | yes | no |
| `--optimize=size` — `SIZE` | yes | yes |

Those two predicates ARE the level: `OptimizeLevelTest.everyLevelIsDistinguishableAndOnlyNoneHasNoSpelling` fails if two levels answer both the same way, which is the rule "**do not ship a level that is an alias of another**" made mechanical. It is why there is no `high`: nothing in the compiler is held back for being too aggressive, so a third level would have nothing to switch on, and a synonym would teach a reader that levels are decoration. An unknown value is an `IllegalArgumentException` naming the accepted set, not a silent fallback.

**Why a VALUE rather than a second flag.** A separate `--optimize-size` / `-Os` sitting next to `--optimize` does not say how the two relate — a reader hitting it in a build script cannot tell whether it replaces `--optimize`, adds to it, or contradicts it. A value cannot be read that way. The CLI consequence is that `--optimize` stays in `CliOptions.noValueKeys` and the parser learned the `--key=value` form instead (`CliOptions.build`): moving it out of that set would make `rontolisp app.lisp --optimize -o out.wasm` read `-o` as the level.

### What `SIZE` declines, and what that costs

Only the wasm-GC backends (Preview 1 AND `--component`) have anything to trade: the two emissions that deliberately spend bytes on speed, both **on even without `--optimize`** — integer expression-tree fusion (`.kb/wasm-int-fusion.md`; a fused site emits its tree TWICE, raw plus the generic fallback) and unboxed dual-representation locals (`.kb/wasm-unboxed-locals.md`). One predicate switches both: `WasmIntFusionCompiler.speedTradesEnabled(ctx)`, read at the three fusion entry points and at `WasmLetCompiler`'s eligibility scan. The JVM backend and `--no-gc` accept the level and emit byte-identical output (pinned by `theSizeLevelIsADocumentedNoOpOnThisBackend` in `JvmLispCompilerTest` and `NoGcWasmCompilerTest`) — accepted rather than rejected so one build script can pass it for every target.

Sizes measured 2026-08-07, `--optimize` vs `--optimize=size`; run times 2026-08-06,
wasmtime 47.0.2 (best of three):

| program | default | size | run time |
| --- | --- | --- | --- |
| `examples/db/postgres-hello` (`--component`) | 7,609,611 | 6,150,090 (**-19.2%**) | — |
| `examples/asdf/ironclad-demo` (SHA-256/HMAC/PBKDF2, 4096 rounds) | 1,962,292 | 1,502,363 (-23.4%) | 1.38 s -> 5.21 s (**3.8x**) |
| `examples/ml/nn-vec` (`vec:` kernels) | 257,391 | 205,745 (-20.1%) | 1.07 s -> 1.26 s (+18%) |
| `examples/ml/mlp` (float, no `vec:`) | 152,466 | 121,266 (-20.5%) | 5.58 s -> 6.06 s (+9%) |
| `examples/ml/heat3d` (`linalg:` stencil) | 172,541 | 134,791 (-21.9%) | 0.03 s either way (too short to resolve) |
| the whole `ci-spec.yaml` corpus (321 cases) | 5,819,288 | 4,891,849 (-15.9%) | 2,132 output lines, byte-identical, exit 0 at both |

(The run-time column is unchanged by the size re-measurements — neither the string/type
shake below nor the encoding minimization touches what the module computes. Note the SIZE
win narrowed by ~1.5 points everywhere: `=size` emits fewer fused sites, so it had fewer
of the padded local references the encoding fix retired, and had less to give back.)

The run-time column is why the level must be asked for and why the docs carry it beside the size one. Note what the spread says: the SIZE win barely varies (-16% to -24% across every program measured, library-heavy or not), while the run-time price varies more than thirtyfold (+9% to +280%), because only INTEGER arithmetic fuses -- a `vec:`/`linalg:` kernel pays it on its loop indices, an ironclad round pays it on every operation. So the level's cost cannot be stated as one number, and a program that is not integer-hot gets the size win nearly free.

### Why the two trades are ONE level and not two switches

Measured on `postgres-hello --component`, all four combinations. The absolute bytes here
predate the string/type shake below and the encoding minimization (the two off-diagonal
rows need a debug switch and a throwaway patch to reproduce, so the set is kept as one
self-consistent session); the ratios are the point and both later passes move every row
alike:

| fusion | unboxed locals | bytes | vs default |
| --- | --- | --- | --- |
| on | on (`=default`) | 8,033,507 | — |
| on | off | 6,973,056 | -13.2% |
| off | on | 7,096,879 | -11.7% |
| off | off (`=size`) | 6,408,277 | **-20.2%** |

The separate savings sum to 24.9 points against 20.2 combined, so they overlap — but the decisive row is "off/on": it is **dominated on both axes**. Larger than `=size`, measured above; and necessarily slower than `=default`, because an unboxed local's whole payoff is being read raw inside a fused tree and stored raw from one — with fusion off the arithmetic is generic anyway, and every assignment additionally bails into the boxed shadow while every read goes through `_ub_read`, which `=size` does not pay either. A configuration nobody can want does not deserve a spelling.

`-Drontolisp.debug.norawlocals=true` still switches the unboxed locals alone; it exists for A/B profiling and is what produced the "on/off" row above (the "off/on" row needed a throwaway local patch, since no shipped switch produces it).

## WASM

A **post-pass relocating tree-shaker** (`am.ik.wasm.WasmTreeShaker`, language-independent) runs on the finished **core module** bytes in `WasmLispCompiler.compile` just before returning — **including under `--component`**, where it runs right after `WasmImportInjector.inject` and before the component wrapper is built (see "Why the component path is safe" below; it was skipped there until todo-259, on a constraint that turned out not to exist). It parses the module sections, builds a call graph from the actual `call` (and `ref.func`) immediates in every body, computes the functions reachable from the roots (exported functions + `_start`/start section), drops the rest **including unused WASI function imports**, and renumbers every surviving function reference. Reachability is exact, not a manual table: when `eval`/`load`/`apply` is used, the dispatch bodies contain real `call`s to every registered function, so nothing dynamically-reached is pruned. It renumbers **function** AND **type** indices (see the next section; the memory and global sections keep their own index spaces untouched). This is the one place the fixed-index invariant is deliberately broken, and only because every reference site is rewritten in lockstep.

### Type section (todo-268, 2026-08-06)

The type section used to be copied verbatim, which cost every module the whole fixed table
(60 entries / 578 B — every runtime struct, array and helper signature) however little of
the runtime survived. The same forward walk that finds the `call` immediates now records
every **type** immediate too, so unreferenced definitions go and the survivors renumber:

- **roots** — the function-section entry of each surviving defined function, each surviving
  function import's `typeidx`, every tag (the tag section is copied verbatim, so its type
  is always live), the global section's value types and initializer expressions, and every
  type immediate in a surviving body: GC-op `typeidx`es, `ref.test`/`ref.cast`/`ref.null`
  heap types, block types and the locals' declarations;
- **edges** — the type-to-type references inside the type section itself: a struct/array
  field's `(ref null $t)`, a func type's params/results, a `sub` clause's supertypes;
- **a `rec` group is atomic.** Its members take consecutive indices, and its structural
  identity under wasm-GC canonicalization is a property of the whole group — which is
  exactly what makes `ref.test $limbs` (`array (mut i32)` inside the `[limbs, bigint]`
  pair) discriminate against `TYPE_I32ARR` (the same `array (mut i32)` inside the
  `[i8arr, i16arr, i32arr]` triple). Naming one member keeps them all, and then every
  member's own references are live too.

Encoding matters on the way back out: a `typeidx` immediate is an unsigned LEB, a
`heaptype`/blocktype is a signed s33 whose NEGATIVE values are the abstract shorthands
(`eq`, `i31`, …) and name no definition — `WasmTreeShaker.RefKind` carries which, and only
non-negative heap types become rewritable references. Two decoder gaps were closed in the
same pass, both "throw rather than emit a corrupt module": a `table`/`element` section
(reference types and function indices this pass does not renumber) and the four GC ops
carrying a `dataidx`/`elemidx` (`array.new_data` & co.) — the pass DROPS data segments, so
silently accepting one would corrupt the module. The backend emits none of them.

`(print "Hello World!")` at `--optimize`: 60 type entries / 429 B -> 7 entries / 61 B
(578 B / 79 B before the type SPELLING shrank too — `.kb/wasm-shortest-encoding.md`).
Pinned by `WasmTreeShakerTest.dropsTypesTheSurvivorsNoLongerName` and
`keepsTheTypesAnEhModeModuleStillNames`, plus `WasmTreeShakerCorpusTest`, which runs
`wasm-tools validate` over the whole `ci-spec.yaml` corpus in both WASI modes.

### Owned data segments (landed 2026-08-06)

The data section is copied verbatim EXCEPT for segments the compiler declares as owned:
`WasmTreeShaker.OwnedDataSegment(segmentIndex, ownerFuncIndices)` names a segment whose
bytes are referenced exclusively by the given functions, and `shake(module, owned)` drops
the segment when every owner is unreachable. The shaker cannot verify the exclusivity
claim (a linear-memory reference is an indistinguishable `i32.const`), so the claim is
the CALLER's invariant — today's only claimants are the two Unicode case-fold range
tables (~16.4 KB combined, `WasmCaseFoldRuntimeBuilder`), each emitted as its own active
segment at the same addresses `appendBlob` used to give them, owned by `_char_upcase` /
`_char_downcase` respectively (`WasmLispCompiler.compile`, "caseFoldSegments"; owner
indices are shifted by the injected host-import count because the shake runs after
`WasmImportInjector`). Dropping leaves an all-zero hole in linear memory that nothing
reachable reads; segment indices carry no other references because the backend emits no
bulk-memory ops. Pinned by `WasmTreeShakerTest.orphanedCaseFoldTableSegmentsAreDropped`.

### String blob: droppable ranges (todo-268, 2026-08-06)

One segment holds the whole `StringTable` blob, so whole-segment ownership cannot express
it: the builtin WRAPPER bodies Pass 2a compiles intern their literals — `FIND-PACKAGE`'s
package-alias alist (`expandRuntimeFindPackage` over `PackageResolver.runtimePackageTable()`)
alone was ~676 B of a hello module's 871 B — and the shaker then deletes the wrappers,
leaving the bytes behind. `WasmTreeShaker.DroppableDataRange(segmentIndex, start, end)` is
the sub-segment form: the pass cuts the range out and re-emits the segment as one active
segment per surviving run, **each at the address it already had**, so nothing relocates and
no reference is rewritten.

Which ranges die is decided by OBSERVATION, not by a declared owner — the same choice the
call graph makes: a range survives when any SURVIVING body (or a global initializer) holds
an `i32.const` in `[address, address + length)` — the HALF-OPEN interval. A linear-memory
reference is an indistinguishable `i32.const`, and that cuts the safe way here: an
unrelated constant landing in a range only KEEPS bytes. This is why a declared-owner scheme
was NOT used — attribution would have to be right at all ~20 `addString` call sites and at
every emitter that bakes a cached `entry.offset()` into a body other than the one that
interned it, whereas the scan reads what the module actually contains.

**Why half-open, and not the closed interval it shipped with (todo-271, 2026-08-07).** The
extra byte was there to let a one-past-the-end pointer keep its range, but no emitter
produces one: a body cannot use a range from its end alone (it needs the base to read
from), so every real consumer holds a const INSIDE the range as well — the only computed
pointer anywhere in the backend is `WasmLiteralPrint`'s `framed.offset() + 1`, which is
interior. What the closed interval did instead was pin every range whose end abutted a
LIVE neighbour's start, and the string blob is one dense run of abutting entries: hello's
`" . "` was kept only by `"\n"`'s start pointer, and a dead builtin-wrapper literal only
by the start of the literal the program actually prints. Ranges with `end <= start` are
skipped before the probe, so a zero-length entry is never a candidate either way.

What the scan cannot see is a citation from DATA, so the COMPILER decides candidacy, and
the rule is a window rather than a list: `StringTable.attributing(true/false)` brackets
passes 2a-2c (defun bodies, the top level, lambda bodies), where a body's own `i32.const`
is the only consumer of what it interns. `StringTable.addBodyString` is the same grant
spelled explicitly, for the runtime-body interns that happen outside that window (the
printer prologue below, and the cached symbol `T` its four helper sites share). Interning a
string with the window CLOSED retracts its candidacy for good, whichever came first — and
that is exactly what every blob-citing caller does: the instance-layout blob (built BEFORE
Pass 2a), the `_lookup` registry rows (`stringTable.addString(defun.name)` after it), the
`eval` special-form offsets, the reader's char-name and struct-directory tables. The one
blob that cites EVERY entry is the runtime intern table (`buildInternBlob` over
`stringTable.entries()`, scanned by `_intern` on offset equality), so a program with
`usesIntern` offers no candidates at all — `WasmLispCompiler.compile`, "stringRanges".

**The printer prologue is not exempt (todo-271, 2026-08-07).** `StringTable`'s constructor
interns 28 fixed entries — `NIL`, the list punctuation `(` `)` ` ` `" . "`, `\n`, the
`#<function>` / `#<FUTURE>` tags, the array prefixes `#(` `#` `A(` `#d(` `#f(`, the number
pieces `-` `.` `/` `NaN` `Infinity` `E`, the `#\` prefix and the eight character names
`Space`..`Rubout` — plus `T` right after it. Every one is read by a RUNTIME body
(`_print_val` / `_princ_val` / the float, character and array printers, the newline
writers, the `T`-returning helpers), and every one of those bodies bakes the offset as its
own `i32.const`: the exact shape the scan reads. So they are interned through
`addBodyString` and stand or fall with the bodies that address them — a hello that never
reaches the generic printer keeps none of them (`\n` is the one a literal write reaches
directly, `WasmLiteralPrint.emitNewline`). The ordinary retraction rule still covers the
blob citers: the reader's char-name table re-interns `Space`..`Rubout` with the window
closed, and `_lookup`/the intern blob do the same for `T`.

**Re-evaluation trigger:** the `usesIntern` bail is the coarse half. It could become
per-entry (drop a range when `_intern` itself is unreachable, or filter the intern blob's
rows post-shake), but `usesIntern` and a live `_intern` almost always coincide, so it was
not worth the machinery; revisit only with numbers showing a program that interns AND has a
large dead-wrapper string set.

`(print "Hello World!")` at `--optimize`: data 909 B -> 168 B, module 1,886 B -> 645 B
(**622 B** once the encoding shrank too, **511 B** once the prologue and the half-open
interval landed — its data section is down to 58 B and holds exactly the three seed cells,
`"\n"` and the framed literal, nothing dead at all). `(princ "Hello World!") (terpri)`
moved 625 B -> 514 B the same way, and its component 1,668 B; the whole spelling table
below re-measured 430-560 B core / 1,583-1,718 B component. A program on
the same gate that reaches the runtime `find-package` keeps the alist. Pinned
by `WasmTreeShakerTest.dropsStringsOnlyDeadBodiesInterned` /
`keepsEveryStringAProgramCanInternAtRunTime` /
`dropsThePrinterPrologueNoLiveBodyReads` / `aBareOnePastTheEndPointerDoesNotKeepARange`,
and behaviorally by
`WasmLispCompilerIntegrationTest.optimizedProgramKeepsEveryStringALiveBodyStillAddresses`
plus `optimizedModulesPrintExactlyWhatTheUnoptimizedOnesDo` — a differential run, because
a wrongly-cut range prints garbage rather than trapping.

### The print family's literal fold

An output built-in whose argument is a LITERAL does not call the generic printer at all:
the text is a compile-time constant (every printer-control variable that could change it
is inert — `.kb/pretty-printer.md`), so `WasmLiteralPrint` interns the pre-rendered form
and writes it through `FUNC_WRITE_STR`, which keeps the `*standard-output*` redirect
semantics. The `print-object` hook cannot fire on this path (it is inside
`compilePrintOperator`'s print-object-free gate). The point is reachability: the generic
printer's integer arm alone pins the whole bignum print chain (9 functions), plus the f64
renderer and the ratio accessors — for a program that only prints literals, all of it
shakes out.

**It is the FAMILY, not `print`.** `print` / `prin1` / `princ` are one emitter
(`WasmPrintCompiler`, switched on readable-vs-display and newline-vs-not) precisely so the
fold cannot exist for one spelling and not the others; `write-string` / `write-line` fold a
string literal the same way, through the same helper. Folded types: string, fixnum, bignum,
character, ratio, `nil`, `t` — everything whose `LispVal.print()` / `display()` the emitted
renderer reproduces exactly. **A FLOAT is deliberately excluded**: `_print_f64` and
`LispDouble.print()` disagree at large magnitudes, so folding one would give a program two
spellings of the same value. That is the re-evaluation trigger — fold floats when those two
renderers agree.

The fold costs no data for strings: an escape-free readable form re-uses the literal's own
interned bytes verbatim, and a DISPLAY rendering points at the interior of the same framed
literal (`offset + 1`, `length - 2`), which is what `_princ_val` computes at run time.

Measured at `--optimize`; the "after" columns 2026-08-07 (re-measured once the printer
prologue stopped being pinned, todo-271), the "before" column the pre-fold state of
2026-08-06 (it cannot be rebuilt, and it is ~4% high in today's encoding — the ratio is
what it records). Preview 1 bytes:

| program | before the fold | after | `--component` after |
| --- | --- | --- | --- |
| `(print "Hello World!")` | 645 | 511 | 1,665 |
| `(princ "Hello World!") (terpri)` | 4,823 | **514** | 1,668 |
| `(format t "Hello World!~%")` | 4,826 | **517** | 1,671 |
| `(write-line "Hello World!")` | 993 | **511** | 1,665 |
| `(write-string "Hello World!")` | 1,767 | **497** | 1,649 |
| `(format t "Hello, ~a!~%" "World")` | 5,031 | **560** | 1,718 |
| `(format t "~a~%" 42)` | 4,890 | **430** | 1,583 |
| `(format t "~s~%" "Hello World!")` | 6,609 | **517** | 1,671 |

The component column is the same core module plus the wrapper floor, re-measured after
todo-273 narrowed that floor to ~1,175 B (it was ~1,500 when the fold landed) and again
after the encoding minimization took it to ~1,154; the two
spellings the change was reported against went 6,346 / 6,349 -> 1,779 / 1,782 there.

The last two needed the format lowering to stop hiding its constants — a literal argument
is no longer bound to a temp, and a `~a`/`~s` piece prints straight to the destination
instead of building a string first (`.kb/format.md`, "What the LITERAL path lowers to").
Pinned by `WasmTreeShakerTest.everySpellingOfHelloWorldReachesTheSameFloor` (< 1 KB for
every spelling), its `…ComponentFloor` twin (< 2 KB, and the imported-interface set)
and behaviorally by
`WasmLispCompilerIntegrationTest.aFoldedLiteralPrintsWhatTheRuntimePrinterWouldHave`, a
differential run of every folded literal against the same value passed through a function
parameter (the only thing that keeps the runtime printer in the picture).

### The `name` section is DROPPED, not copied (todo-270, 2026-08-06)

A `name` custom section maps **function and type indices** to names, and this pass has just
renumbered both — copying it through described the module's old shape. It is now dropped
(`WasmTreeShaker`, `SEC_CUSTOM`); every other custom section is index-free and still copied.
The rontolisp backend emits none, so this is invisible on a compiled core module and decisive
on the hand-written WAT blobs the component wrapper embeds: the base adapter's name section
alone is 1,438 B of its 3,953. Pinned by `WasmTreeShakerTest.dropsTheNameSectionRenumberingHasInvalidated`.

### The component WRAPPER: adapter + WASI surface (todo-270, 2026-08-06)

Until this pass the wrapper was fixed cost: whatever the core shrank to, a component carried
the whole 9-shim preview1 adapter and all eleven `wasi:*` interface declarations. Both now
follow the core, through one chain in which every link is exact and every step is *observed*
rather than declared (`WasmComponentBuilder.fixedSurface`, the base variant only):

1. the core's surviving `wasi_snapshot_preview1` imports (`WasmImports.functionFields`) are
   the adapter entry points that still have a caller;
2. `WasmExports.retain` makes exactly those the adapter's exports, and `WasmTreeShaker.shake`
   then deletes everything unreachable from them — **including the adapter's own `"w"`
   imports**, which is the measurement the rest reads;
3. the surviving `"w"` names select their `canon lower` / built-in entries out of one
   declarative table (`W_MEMBERS`), which names the WASI functions to alias (`BLOCK_FUNCS`)
   and the component types to declare (`PROJECTED_TYPES` / `DEFINED_TYPES`, closed over their
   own dependencies);
4. those name the interfaces, and `ComponentImportBlock.prune` cuts the import blob down to
   them — closing over the projection edges (`preopens` aliases `filesystem/types`'
   `descriptor`, so it cannot outlive it) and renumbering what is left.

**Nothing downstream may hold a fixed index any more.** The old `INST_*` / `T_*` constants are
gone: the block's instance indices, the first free component type, and the count the user
imports and the `run`/export wiring shift by all come back from the prune. That is the one
real hazard here — a stale constant yields a component that *validates* while binding the
wrong interface — so `ComponentImportBlock.Pruned` returns the maps rather than the bytes alone.

**The adapter needed splitting to make the filesystem droppable.** `fd_write` dispatches on a
runtime fd (1 stdout, 2 stderr, else a file), so the call graph reaches `append-via-stream`
from *any* printing program and `wasi:filesystem` — the block's single biggest group, 1,229 B
— never left. `adapter.wat` now factors the two fd-polymorphic shims into
`$fd_write_stdio`/`$fd_write_file` and `$fd_read_stdin`/`$fd_read_file` over shared helpers,
and exports the narrow halves as well. **`path_open` is the only writer of the adapter's fd
table**, so a core that does not import it can never present a file fd: the wrapper then
retains `fd_write_stdio` UNDER THE NAME `fd_write` (`WasmExports.retain` renames) and the
whole filesystem surface goes. This is the one place the component reads an adapter export
whose name differs from `adapter.wat`'s — deliberately, because the alternative (a
`from-exports` renaming instance) costs bytes and index churn in exactly the case being
optimized. `wasi:cli/stderr` used to stay for every printing program — retired by todo-273
below, which answers "can this program present fd 2" from the SOURCE instead.

**A narrow half must be no more PERMISSIVE than the wide one.** `$fd_write_stdio` answers fd 1
and 2 and TRAPS (`unreachable`) on anything else; `$fd_read_stdin` traps on any fd but 0.
Without those arms the pruning would have converted a loud failure into a silent one: a SOCKET
fd (>= 200) also reaches `fd_write` whenever a write form escapes `WasmSocketsRewrite`'s
dispatch table (`format` is one such form today), and under the wide adapter that walked off
the fd table and trapped inside the host — which is how that class of gap has always been
found. A guard-less narrow `fd_write` would instead have written those bytes to STDERR and
returned success, so `--optimize` alone would have turned a crash into a protocol desync.
Pinned by `WasmLispCompilerIntegrationTest
.anOptimizedComponentFailsAsLoudlyAsAPlainOneOnAnFdItCannotServe`, which runs one socket
program at both levels and requires both to fail. The rule for any future narrow/wide pair:
the narrow one rejects what it does not implement, it never approximates it.

**The blob grammar is decoded, not pattern-matched.** `ComponentImportBlock` classifies every
byte of the block (instance-type declarators, the whole defvaltype set, extern descriptors,
aliases) and throws on anything else; only three immediates in it point outside their own
entry (an alias section's instance index, an `alias outer` type index, an import's
instance-type index) and only those are rewritten. Checked against `wasm-tools` itself rather
than against its own idea of the grammar: pruning `import-block.bin` to `{wasi:cli/types,
wasi:cli/stdout}` is **byte-identical to `import-block-nogc-print.bin`**, which that tool
generated independently for exactly that world (`ComponentImportBlockTest`, which also runs
all 2,047 non-empty subsets back through the parser).

**`--emit-wit` moves with it.** A pruned surface means the world names fewer interfaces, so
`WitEmitter` filters the variant document's world imports and drops the package definitions
nothing references — from the SAME set the builder prunes to (`WasmComponentBuilder
.wasiInterfaces`). `WitOracleE2eTest` gained its first `--optimize` legs here; every case in
it compiled at `OptimizeLevel.NONE` before, so this whole path had no oracle.
Pruning also exposed an ordering rule that had been true by accident: `wasm-tools component
wit` prints package DEFINITIONS in the order the world first names them (imports in order,
then exports), and the templates matched only because `wasi:cli/types` is always the first
import. Drop every `wasi:cli` import — a program that reaches no stdio at all — and the
package survives only through the fixed `export wasi:cli/run`, which the tool then prints
LAST. `WitEmitter.orderPackagesByFirstReference` now derives the order from the world instead
of from the template, which also covers the appended user-import and exported-interface
blocks (`aPrunedWorldWithNoWasiCliImportStillOrdersItsPackagesLikeWasmTools`).

**Serve is deliberately NOT pruned** (`WasmServeComponentBuilder` keeps its fixed block
constants and embeds the preview1 bridge whole). Its block declares nine interfaces that
http.lisp's own glue reaches anyway, and its floor is the ~280 KB core, so the measurable win
is the bridge's ~0.7 KB — under 0.3%. Re-evaluation trigger: if the serve core ever stops
reaching the `wasi:cli`/`wasi:clocks`/`wasi:random` halves (a handler that neither prints nor
times nor randomises), or if the serve floor drops by an order of magnitude, the same three
steps apply unchanged — the machinery is variant-independent.

**Combined effect** (`(print "Hello World!")`, wasmtime 47), the changes above in the order
they landed — the case-fold segment split and the literal-print fold, then the type section
and the string blob, then the wrapper, then todo-273's two narrowings, then the shortest-
encoding pass (`.kb/wasm-shortest-encoding.md`, 2026-08-07; every step before it measured
2026-08-06):
Preview 1 `--optimize` 22,355 -> 1,886 -> 645 -> 622 -> **511 bytes** (the first two:
-16,368 data, -4,078 code; the next two: the type section 578 -> 79 B and the data section
909 -> 168 B; the last, todo-271's unpinned printer prologue and half-open range probe,
that data section 168 -> 58 B);
`--component` 29,430 -> 8,930 -> 7,690 -> 2,138 -> 1,820 -> 1,776 -> **1,665**.
The component is now shaken core 516 + adapter 547 (from 3,624) + the import block 197 +
the shared-memory module 25 (from 158) + 380 B of wiring (component types, aliases,
canonical functions, core instances, the preamble and the module framing). The
core was 8% of the component and is 31% of it now -- the wrapper is still the majority of a
HELLO component, but it is no longer fixed cost: it shrinks with the program instead of
standing under it. Two ends of the same measurement: a component with no I/O at all (only
`wasm-export`s) imports ZERO interfaces; a program that opens a file, lists a directory,
reads the clock, draws random bytes and reads the environment keeps the whole
eleven-interface surface and lands where it always did.

**Decoder correctness** rests on the backend emitting (a) no `call_indirect`/element segments — first-class calls go through dispatch functions with direct `call`, so `call` is the only function reference; and (b) a finite, enumerated opcode set (incl. the `0xFB` GC ops, the `0xFD` fixed-width SIMD ops — `skipSimd`, needed since the `--no-gc` `vec:` kernels emit `v128`/`f64x2`/`f32x4` — the `0xFC` misc-prefix saturating truncations the float->integer conversions emit, and `block (result …)` blocktypes — including the ONE-BYTE `eqref` spelling of one, `.kb/wasm-shortest-encoding.md`) — an unknown opcode (or SIMD sub-opcode) throws rather than emit a corrupt module. With `--no-wasi --optimize` a pure-compute reactor (`fact`) drops 318,560 -> 3,727 bytes (2026-08-07). Tests: `WasmTreeShakerTest` (structural, no Docker: shrinkage, import drop, well-formedness via a mini-parser, idempotence) + optimize cases in `WasmLispCompilerIntegrationTest` (`wasmtime` behavior parity, incl. `--no-gc --optimize` f64x2/f32x4 vec kernels).

### The wrapper's last two fixed costs, and the floor under them (todo-273, 2026-08-06)

Two things the todo-270 chain could not reach, because neither is an edge in any module:

**`wasi:cli/stderr`, 185 B measured.** `fd_write` dispatches on a runtime fd, so `$fd_write_stdio`
reaches `stderr-write` from *any* printing program and the whole interface — its 69-byte
instance type, its import, its `error-code` alias, its `write-via-stream` alias, its
`canon lower` and its `"w"` entry — rode along. But fd 2 is the RESERVED `*error-output*`
handle (`.kb/standard-output-redirect.md`), and the compiled core materializes it in exactly
three places, all of them `StreamDesignators.STANDARD_ERROR_HANDLE`: a read of that
variable, `warn`'s report, and the `_start` seed a binding of the variable installs. So
"can this program present fd 2" is a question about the SOURCE, and `WasmLispCompiler`
answers it (`programUsesSymbol` over `*ERROR-OUTPUT*` / `WARN` / `%WARN`, plus `--dynamic`,
where any symbol is reachable at run time) and hands the answer to the wrapper as
`WasmComponentBuilder.Narrowing`. `adapter.wat` gained a third `fd_write` —
`$fd_write_stdout`, fd 1 only, `unreachable` on anything else — and the retain table picks
between the three. **The narrow/wide rule from todo-270 holds unchanged: the stdout-only
half rejects fd 2 rather than approximating it**, so a wrong answer is a trap, never bytes
silently written to the wrong descriptor. A program with `path_open` keeps the WIDE
`fd_write` and therefore keeps stderr regardless — a fourth "files but no fd 2" variant
would buy those bytes back on a component already orders of magnitude past this budget.

*This is a dependency on a list being complete.* Anything new that can put handle 2 into a
stream designator must join that gate, which is why the producer list lives in
`.kb/standard-output-redirect.md` and says so there.

**The shared `cabi_realloc`, 142 B measured.** The mem module exists for its MEMORY: the `"w"`
lowerings' canonical options name a core memory, and that memory must belong to an instance
older than the adapter they are grouped for — a circularity no other module can break. Its
allocator half is a separate question, answered by whether any canonical option actually
references it. For a print-only program none does (`stdout-write` lowers bare; the
stream/future/waitable built-ins take `(memory 0)` only), so the alias goes, `nextCoreFunc`
starts at 0 instead of 1, and the module is retained-and-shaken down to a bare
`(memory 6) (export "memory")` — 158 B to 34, and to 25 once the shaker stopped writing
back the three sections it had emptied (`.kb/wasm-shortest-encoding.md`).
`WMember.realloc()` cannot drift from the
encoders because the realloc index is reachable only through the `lowerRealloc` /
`builtinRealloc` factories that also set the flag. Block-bound and user interface imports
are answered with a plain yes (`needsSharedRealloc`): every one of their lowerings stages
through it, and such a component is nowhere near this budget. A `wasm-export`'s string ABI
is NOT part of the question — it lifts through the CORE module's own `cabi_realloc`.

**The floor: WASI 0.3 streams are asynchronous, and the blocking spelling is gated.** The
adapter builds a stream, a future and a waitable set for one constant write, and that is
~279 B of the remaining wrapper (the `waitable-set-new` / `waitable-join` /
`waitable-set-wait` canons, their `"w"` entries and imports, and `$ensure_ws` /
`$await_waitable` / the two BLOCKED-retry wrappers). The component model does have the
synchronous `stream.write` / `future.read` built-ins that would delete all of it — measured
by hand on the emitted component of the day, 1,820 -> ~1,541 — but they sit behind the spec's
"more async builtins" tier: `wasm-tools validate` and wasmtime 47 both reject them without
`component-model-more-async-builtins`, which is NOT default-on (`wasmtime run -W gc=y`
alone fails to parse; adding `-W component-model-more-async-builtins=y` runs it and prints
correctly). rontolisp's contract is that a component runs with **zero** flags, so this is
the floor today. **Re-evaluation trigger: when more-async-builtins becomes default-on, drop
the waitable trio from `adapter.wat` and the async keyword from those two canon encodings.**

What is left in the 1,665 B, for whoever comes next: shaken core 516, adapter 547, import
block 197, shared memory 25, wiring 380. (The core was 627 until todo-271 unpinned the
printer prologue and made the range probe half-open; the other four are untouched by it,
and the core's own data section is now down to what the program writes.)

**The `"w"` field names, ~232 B, deliberately left long — decided, and re-affirmed
2026-08-07.** Of the adapter's import section and the synthesized `"w"` core instance,
~232 B is the field NAMES, and each is spelled twice: once as the adapter's
`(import "w" "<name>" …)` and once as the `(export "<name>" (func n))` of the instance
built to satisfy it. For the nine members a printing program keeps —

```
stdout-write(12) stream-new(10) stream-write(12) stream-drop-w(13)
future-read-cli(16) future-drop-cli(16) waitable-set-new(16)
waitable-join(13) waitable-set-wait(17)          = 125 chars x 2
```

— one character each would leave 9 x 2, saving ~232 B: **13.1% of the whole component**,
taking hello from 1,665 to roughly 1,433. It is available, cheap and safe. `"w"` is a
private linkage between two artifacts this repo ships together, so the names carry no
information the reader cannot get elsewhere (`adapter.wat` names every import with a
`$symbolic` local right beside it, and `W_MEMBERS` is keyed by the descriptive name), and
`fixedSurface` already throws when the adapter imports a `w` member the wiring does not
declare, so a table/WAT drift is a build failure, not a silent bug.

Against it: `wasm-tools print` on a shipped component goes opaque (`(import "w" "3" …)`);
a name-to-ordinal mapping has to be read in two places; and `adapter-http-server-p1.wat` /
`WasmServeComponentBuilder.BRIDGE_FUNCS` would have to follow or the two adapters diverge
in convention. **And the decisive one: the bytes are coming back anyway.** The ~279 B the
gate above holds is a strictly larger win on the same component, and an upstream default
will hand it over for free. Spending legibility now for bytes that are already owed is the
wrong trade.

Re-open it only when one of these is true, and record which. **(a) The gate above opens** —
then do THAT first (the trigger and recipe are the paragraph above), re-measure, and re-read
this one; it may be enough on its own. **(b) A host makes the component floor matter more
than its legibility** — a wasmCloud-shaped registry where transfer size is the cost; then
take the ~232 B, and take it for BOTH adapters in one pass. Both were checked 2026-08-07 and
both failed: a hand-written component whose only content is a synchronous `canon
stream.write` is still rejected by wasm-tools 1.254.0 and by wasmtime 47.0.2 — the version
this repo pins for CI — with "requires the component model more async builtins feature", and
no size-driven host requirement has arrived. If it is ever taken, keep `W_MEMBERS` keyed by
the descriptive name and give each member an explicit wire field, so the mapping lives in
exactly one table next to the encoders (the shape `WMember.realloc()` already uses). What is
NOT part of that question either way: the core module (its own budget), the serve variant's
floor (a ~280 KB core, where 232 B is noise), and how what is emitted is SPELLED — which is
the next paragraph.

The ~44 B that used to sit here as non-minimal ENCODING (and 2.4–5.7% of
EVERY module this project emits, Preview 1 included) is gone: every module and component
the project writes is now a `wasm-tools` round-trip FIXPOINT, so what is left above is
content, not spelling — `.kb/wasm-shortest-encoding.md`.

### Why the component path is safe (todo-259)

Every core <-> component linkage is **by name**, in both directions, so renumbering the core's functions is invisible to the wrapper:

- the wrapper reaches into the core only through `alias core func (instance N) "name"` (`ComponentWriter.aliasCoreFunc`, encoded as `sort=core func, target=0x01 <instance> <name>`) — `run`, `handle`, `async_cb`, each `wasm-export` wrapper, `cabi_realloc`, `cabi_post_*`. **All of them are core EXPORTS, hence already shaker roots**, including the two the core never `call`s itself (the serve `handle` and its callback `async_cb`, reached only from the `canon lift ... async (callback ...)` declaration);
- the core's imports are satisfied by `core:instantiate <module> vec((name, instanceidx))` with per-interface instances built `from-exports` as `(field name -> func)` maps, so a dropped function import just leaves one unused name in the map — nothing is positional;
- the Preview-1 adapters (`adapter.wat`, `adapter-http-server-p1.wat`) never reference the core at all: they are instantiated BEFORE it and the core binds them by name. (The retired claim in this file said the opposite.)

The same by-name linkage is what lets todo-270 run the shaker on the ADAPTER too, and read the answer back off its import section — see "The component WRAPPER" above. The one thing that is NOT by name is the component's own index spaces, which is why the wrapper's fixed instance/type constants had to go with it.

`WasmComponentBuilder.memModuleFor` reads the core's `mem`/`memory` **memory** import, which the shaker keeps verbatim along with every other non-function import.

Effect: a non-serve component is where the shaker earns its keep (`(print "hi")`: 357 KB -> 29 KB), because such a program never reaches the arity dispatch. A **serve** component was long the counter-example (the Clack model `funcall`s the handler, so the dispatch bodies were live and they `call`ed every registered builtin wrapper — a ~4% drop): each of the three rontolisp-owned gate blockers had to be retired first, the keyword-intern exemption below being the last. With it (todo-260) the trivial serve component is 569,842 no flag / **273,547** at `--optimize` (-52.0%; 54 of 367 defuns dispatchable) / 225,024 at `--optimize=size` (2026-08-07; 594,477 / 280,256 / 225,683 before the encoding minimization). Note what the bytes do NOT buy on wasmtime serve: rps at `--max-instance-reuse-count` 1 and 128 is unchanged within noise vs the 641,599-byte pre-fix module (measured 2026-08-06, best of three: ~1760 vs ~1780, ~4900 vs ~4990) — the module is compiled once per server run, so per-instance cost is the pre-grow plus fixed instantiation work, not module bytes. The size win is transfer, disk, and compile-time cold start (wasmCloud-shaped hosts), not the reuse loop.

## The funcall-dispatch gate (what makes `--optimize` reach library code)

**A function gets an arity-dispatch case, and a `_lookup` registry row, only when the program can actually reach it as a function VALUE.** Without this the shakers are nearly inert on any program that loads a library: the ladders `call` every registered function, so everything is reachable and `--optimize` on a `(ql:quickload "cl-postgres")` component dropped **22 of 2618 functions (-1.5%)**. With it, an `md5` program drops **-49.3%** (1,177,653 -> 597,641).

`WasmLispCompiler.dispatchableFuncIds` / `JvmLispCompiler.dispatchableFuncIds` compute the set; `WasmRuntimeBuilder.buildDispatchBody` and `JvmRuntimeBuilder.buildDispatchMethods` filter their targets by it, and the registry (the WASM data blob / `JvmEvalRuntimeBuilder.lookupSegments`) filters its rows by the SAME set — they are computed together precisely so they cannot drift: a row whose funcId has no case would resolve and then fall through to the ladder's default arm.

Two sources, both EXACT rather than heuristic:

- **`Ctx.valueFuncIds`** — the funcIds Pass 2 actually materialized as a closure: `WasmFunctionFormCompiler.compileNamed` / `JvmFunctionFormCompiler` (`#'name`), `WasmLambdaCompiler.emitClosureValue` / `JvmLambdaCompiler` (every `(lambda ...)` value), and `WasmAsyncEmit`'s waiter closure over a resume function. Collected DURING emission, not from a pre-scan, which is the whole point: a `#'identity` or `%seq-string` reference a macro synthesizes during Pass 2 is invisible to any scan of the source program, and that is exactly what `.todo/260` recorded a naive attempt dying on. Every body is emitted before the ladders are built, so the set is complete when it is read.
  **Trap, and it bit once:** `WasmAsyncEmit.freshCtx` rebuilds a `Ctx` field by field and also builds the SYNCHRONOUS top level. Omitting `valueFuncIds` there silently lost every closure the top level makes, and `(funcall f 1)` trapped. Any module-wide MUTABLE `Ctx` field must be listed there.
- **the names a runtime SYMBOL designator can resolve.** `_lookup` matches interned offsets (WASM) / string constants (JVM), so a registry row is reachable only when the program already put that exact name there for another reason — a quoted symbol, a string literal, an `intern` of a literal. `StringTable.isInterned` and `ConstantPool.hasStringConstant` are the two probes, and the name is tried three ways: canonical, the `::`->`:` alias row's spelling, and the bare member name after the last colon.

**The gate turns itself off entirely** (every function stays dispatchable) under `--dynamic` and whenever `compiler/RuntimeNameProducers.anyNameResolvable` holds — the program contains `eval`/`read`/`read-from-string`/`load` (it can evaluate data) or `intern`/`find-symbol`/`make-symbol`/`symbol-function`/`fdefinition`/`fboundp`/`uiop:symbol-call` (it can build a name). That class is shared by both backends on purpose: a name that stops resolving on one has to stop resolving on the other. Compile with `-Drontolisp.debug.dispatchgate=true` to have the offending operator NAMED, and to see how many functions stayed dispatchable.

**The one form the scan skips is rontolisp's own** (`RuntimeNameProducers.isCompilerScaffolding`): the generated slot-name fold `(defun %slot-name-key (n) (intern (symbol-name n)))`, which every runtime-slot-name dispatcher (`%slot-value-runtime` & co.) calls on its name argument. It re-spells a symbol the program already holds and its result reaches nothing but a `member` test. It is matched by structural equality against `LispMacroExpander.slotNameKeyDefun()` — the builder that emits it — so an edit there cannot leave a stale pattern here, and a user-written defun of that name with any other body is scanned normally. The fold used to be inlined in each dispatcher; it was pulled out into one named defun precisely so the exemption could be an identity rather than a shape. Boundary: a program that calls `%slot-name-key` itself and funcalls the answer forges a name the gate no longer sees — the same carve-out (and the same `--dynamic` escape) the gate already documents for a name assembled out of computed strings.

**The scan also exempts the keyword-package intern** (todo-260): an evaluated `(intern NAME :keyword)` — judged by the same predicate the compile-path lowering branches on (`LispMacroExpander.isKeywordPackageDesignator`), so the exemption covers exactly the forms that lower to `internKeywordForm` — is not a name producer, whatever NAME computes. This one is a SHAPE, not an identity, and it is sound against the probes rather than by intuition; the reasoning, so the next visitor can re-check it: (a) the lowering spells the result `":" + NAME`; (b) the runtime match is exact-spelling equality against the registry's row keys (offset equality on WASM — `_intern` dedupes against the static table, so equal spelling = equal offset); (c) no row key can begin with a colon, because a keyword can never name a function on any backend — the defun's implicit block rejects it (`BLOCK: block name must be a symbol, got :FOO`, `LispMacroExpander.blockName` / `LispEvaluator.blockName`). The earlier worry that the bare-member probe (probe three) reaches a keyword ran the probe backwards: that probe widens which functions GET a row from pool strings, it never strips the designator's spelling at run time, so `:CAR` still fails to match the row `CAR`. Funcalling a runtime-built keyword is therefore an undefined-function error with the gate on, off, or absent — behavior does not move. Inside QUOTED data the shape stays a trigger (the `intern` symbol itself could be extracted and funcalled). This is what re-opened the gate for the serve component: the HTTP libraries' `%http-method-keyword` / `%http-protocol-keyword` / `%serve-method-keyword` intern runtime method strings into `:keyword`, and were the third rontolisp-owned blocker after the `~/name/` renderer arm and the slot-name fold. Pinning tests: `keywordInternDoesNotHoldTheFuncallDispatchGateOpen` (WasmTreeShakerTest), `keywordInternDoesNotHoldTheDispatchGateOpen` (JvmClassShakerTest, incl. the quoted-data conservatism), `keywordInternStaysInternedInAGateShakenModule` (WasmLispCompilerIntegrationTest).

Without the bail the gate is not sound, and the failure is a trap rather than a diagnosis — measured: 32 tests across both backends, every one of them a name assembled at run time (`(eval (read))`, `(intern "EX-FN" :pkg)`, `uiop:symbol-call`).

**What the bail costs, measured 2026-08-06** (`--optimize`, wasm-GC), after todo-261 retired the two blockers rontolisp itself was contributing:

| program | before todo-261 | after | gate |
| --- | --- | --- | --- |
| `md5` via `ql:quickload` | 597,641 | 597,641 | applies |
| pure compute (no library) | 25,201 | 25,201 | applies |
| `split-sequence` | 619,722 | **234,745 (-62.1%)** | applies |
| `cl-ppcre` | 2,419,247 | **1,890,497 (-21.9%)** | applies |
| `com.inuoe.jzon` | 1,432,415 | 1,414,105 | bails |
| `examples/db/postgres-hello` (`--component`) | 8,085,309 | 8,033,507 | bails |

Both columns are the same probe program measured on the same day; the `jzon` and pure-compute rows sit at a different absolute size than todo-260's table because the probe programs are not the same ones (a row is comparable across its own two columns, not across tables). Every absolute here — and the `1,177,653 -> 597,641` above — predates the encoding minimization and is ~4% high today (`md5` re-measured 2026-08-07: 582,131 with the gate applying); the RATIOS, which are what this table is about, are unmoved.

The two blockers were BOTH rontolisp's own code, and each masked the next:

1. the spliced runtime `format` renderer's `%fmt-function-designator` (the `~/name/` directive resolves its target out of the control string and funcalls it), now split into a separately-injected arm — `.kb/format.md`, "The `~/name/` arm is injected SEPARATELY";
2. with that gone, the generated slot-name fold's `intern` became the blocker for `cl-ppcre` (worth the whole -21.9% above) — now the scan's one exemption, above. Its earlier rejection was recorded as "harmless, but not what holds the gate open"; retiring blocker 1 retired that reason, which is why it was re-taken in the same session.

The two rows that still bail do so **correctly** — the forge is in the library, not in rontolisp:

- `jzon` calls `(fdefinition key-fn)` on a runtime designator (`src/jzon.lisp`);
- `postgres-hello`: `cl-postgres::initiate-ssl` does `(setf make-ssl-stream (intern (string '#:make-ssl-client-stream) :cl+ssl))` and funcalls it. Dead at run time (guarded by a `find-package :cl+ssl` that fails here), but a trigger-shaped gate cannot see that. **Its `read` half is gone for a different reason**: the one `read` in that whole program was ironclad's `array-reader`, a `#@` reader macro registered with `set-dispatch-macro-character` — a registration rontolisp's reader can never fire. `LibraryDefunPruner` and `expandSetDispatchMacroCharacter` now both skip the `#'name` hook argument (`LispMacroExpander.isDeadReadtableHook`), so the defun is pruned and the reader runtime is not emitted at all. The todo's "postgres-hello needs both halves fixed" was therefore an incomplete diagnosis: three causes, two now gone, the third genuine.

One refinement was tried and REJECTED on measurement (do not re-propose without new numbers): judging the `intern` ARGUMENT shape (shrank nothing — every real program reaching there computes the name anyway — and broke `internIntoALiteralPackage` on both backends, because the two-argument lowering folds the literal into the qualified symbol before either probe can see it).

Tests: `componentCoreIsTreeShakenUnderOptimize` (shrinkage + a scalar and a string-returning export invoked under wasmtime, i.e. the canonical-ABI helpers survived) and `optimizedServeComponentStillServesUnderWasmtimeServe` (a shaken serve component actually answers a request), both in `WasmLispCompilerIntegrationTest`; `FormatRendererTest.theFunctionDesignatorArmIsInjectedOnlyForAProgramThatSpellsTheDirective` for the renderer half.

## JVM

The counterpart post-pass is `am.ik.jvm.JvmClassShaker`, run at the end of `JvmLispCompiler.compile`. It parses the finished class, builds the call graph from the `invoke*` constant-pool immediates, keeps methods reachable from `main` (plus `_apply` as an extra root when the program uses `java:` interop — the embedded bridge looks `_apply` up REFLECTIVELY, an edge bytecode cannot show), drops unreachable methods and any static field only they referenced, and **compacts the constant pool**, rewriting every CP index immediate in the surviving bytecode in place (sizes never change: u2 stays u2, an `ldc` u1 index only shrinks because compaction preserves order — so exception-table pcs and switch padding stay valid; no method renumbering is needed since JVM methods are referenced by name). Dispatch methods keep eval/funcall/`#'` targets alive exactly as on WASM. The shaker throws on anything it does not recognize (unknown opcode/constant tag, any attribute other than a single `Code` per method) rather than emit a corrupt class; `fact` drops ~46 KB -> ~4.6 KB.

Tests: `JvmClassShakerTest` (structural + behavior, incl. the `_apply` root) and `JvmClassShakerCorpusTest` (compiles the whole `ci-spec.yaml` corpus with `--optimize`, asserts shrink + identical run output — the decoder-completeness guard, like `WasmTreeShakerCorpusTest`). Limitations (README "Optimize").

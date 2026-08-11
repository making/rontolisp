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

## Before the shakers: a `typecase` clause no call can select (todo-311, 2026-08-10)

Both shakers are name reachability -- the wasm one over `call` immediates, the JVM one over
method references -- so neither can see that a `typecase` clause is dead because of what the
CALLER passed. clack's `clackup` is the standing example:

```lisp
(flet ((buildapp (app)
         (let* ((app (typecase app
                       ((or pathname string) (eval-file app))   ; (clackup "app.lisp")
                       (otherwise app))))                       ; (clackup #'app)
           ...)))
  (let ((app (buildapp app))) ...))
```

Every Worker calls `(clack:clackup #'app ...)`, so the first clause cannot match -- and
`CLACK::%LOAD-FILE`, `CLACK:EVAL-FILE`, `PROBE-FILE` and the `NoWasiFilesystemStubs` error
string behind it rode into every clack Worker module anyway.

`compiler/DeadTypeBranchPruner` DELETES such a clause from the AST before Pass 1, so the
shakers then drop what it held by the reachability they already have. Gated on
`eliminatesDeadCode()`, called from `JvmLispCompiler.compile` (right after
`flattenTopLevel`) and `WasmLispCompiler.compile` (**after** `NoWasiFilesystemStubs`, which
is what closes the funcall-dispatch gate on a clack Worker -- the pruner declines while the
gate is open). It shares its whole decision, `ArgumentShapes.maySatisfy`, with the
`--no-wasi` build warning that had the same blind spot
(`.kb/wasm-export-no-wasi.md`); the two walks differ only in what they do with the answer.

Deletion is the ONLY rewrite, which is why it needs no evaluation-order reasoning: the
surviving form is the same form with fewer clauses. An `(if (typep x 'pathname) ...)` is
left alone here even though the warning pass skips it -- rewriting a test means deciding
what happens to the operand's own evaluation, and the branch holds no bytes of its own.

**What makes it sound.** A rewrite needs the whole program to agree, not one call chain:

- a parameter's shape is the JOIN over EVERY call site -- one `(clackup "app.lisp")`
  anywhere keeps the clause;
- a name taken as a VALUE (`#'clackup`, or any occurrence inside quoted data, which is how
  a designator reaches `funcall`) has no known call sites, so its parameters state nothing;
- a name with two definitions, or one that is also a `defmacro`/`defmethod`/`defgeneric`,
  is left alone, and a user macro's ARGUMENTS are rewritten from the top-level scope (the
  expansion may drop them inside a binding of its own);
- the pass declines entirely under `RuntimeNameProducers.anyNameResolvable` -- `(eval
  (read))` can call anything with anything.

Over-COUNTING a call site is harmless (a shape that is not really passed only widens the
join toward UNKNOWN, which prunes less), so the call scan is deliberately dumb: any cons
whose head names a known function counts. Missing one is the unsafe direction, and the
escape rule above is what covers it.

Shapes flow through `let`/`let*`/`do`/`do*`, a `lambda`'s parameters (unknown -- whoever
calls it decides) and `flet` locals, whose parameters are joined over the call sites in the
`flet` body. That last one is not a refinement but the case itself, since clack's `typecase`
is inside a local. A `labels` local stays unknown: its siblings can call it, so the body is
not the whole call set.

Measured, `--no-wasi --optimize=size`, 2026-08-10 (five functions and ~1.2 KB out of every
clack Worker; the numbers in `size-report/results/cloudflare-workers.md` are the tracked
ones):

| worker | before | after |
| --- | --- | --- |
| `hello-clack` | 249,828 | 248,587 |
| `hello-tiny-routes` | 273,450 | 272,208 |
| `httpbin-clack` | 265,812 | 264,565 |
| `httpbin-tiny-routes` | 290,560 | 289,312 |
| `hello-ningle` | 2,662,831 | 2,662,835 |

`hello-ningle` is the one that does not move, and it is the useful row: yason puts a `read`
in the program, so `anyNameResolvable` holds and the pruner declines -- while the build
WARNING still goes quiet there, because that walk is per-call-chain and needs no
whole-program agreement. Two analyses, one predicate, and each is precise where the other
cannot be. Pinned by `DeadTypeBranchPrunerTest`; every worker verified answering under node
after the prune.

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
`eval` special-form offsets, the reader's char-name and struct-directory tables.

**A baked packed-vector literal is a candidate too** (`StringTable.appendShakeableBlob`,
todo-319). A literal `(unsigned-byte 8|16|32)` table of 16+ elements goes into the same
segment as raw little-endian bytes, and its only reader is the copy loop's own
`i32.const base` — the same shape the window grants a body string, so the blob joins the
candidate list directly instead of needing a window. A blob is never deduplicated, so one
append is one range. That is also why the loop takes its base from an `i32.const` and not
from the load's memarg offset: the probe reads `i32.const` values and skips memargs, so a
base hidden there would let the shaker cut a table its own reader still addresses
(`.kb/packed-integer-vectors.md`).

**An OVERLAPPING entry is never a candidate** (`StringTable.addTailOf`, todo-317): a
layout's print name is interned as a view into its own `%class-` tag's bytes, and a range
the shaker cuts has to own its bytes outright, so the reuse is declined when the container
is already a candidate and the shared entry never becomes one. Any future byte sharing
owes the same rule, or a cut takes a live string's bytes with it.

**A GENERATED string literal that spells a function's name arms the dispatch gate**
(todo-317, measured the hard way). `dispatchableFuncIds` reads a framed literal equal to a
defun's name — or to its bare member name — as something `intern` could hand to `funcall`,
which is right for a literal the USER wrote and wrong for one the compiler synthesized: it
gives that defun a ladder case, and the ladder's call edge then keeps it and everything it
reaches. Shortening a dispatcher's `"No applicable method: X on "` literal to just `"X"`
did exactly that to every generic in the program — **+11.5 KB on the hello-clack Worker**,
where the whole Gray-stream protocol came back — so the literal keeps its `" on "`
separator (`LispMacroExpander.noApplicableMethod`). Re-evaluation trigger: if the gate ever
learns to tell a generated literal from a user-written one, the bare name is 94 bytes
better on the zlib row; until then, no generated literal may spell a name exactly.

**The runtime intern table is handled structurally, not by standing down (2026-08-09).**
The one blob that cites EVERY entry is the intern table (`buildInternBlob`, scanned by
`_intern` on offset equality), and until 2026-08-09 that citation disqualified every
candidate of a program with `usesIntern` wholesale. Now each candidate's 8-byte
`(offset, length)` row is offered as a droppable range OF ITS OWN, probed on the STRING's
interval — the five-argument `DroppableDataRange` form, whose extra interval is a
caller claim in the `OwnedDataSegment` sense: the only reader of the cut bytes must
tolerate them reading as zeros once the probed interval is dead. Row and bytes therefore
fall together, and `_intern` skips any row whose offset word is 0 (no real entry sits at
address 0 — static entries start at the data base, runtime ones in the heap; without the
skip a zero-length probe would match the first hole, and `'||` holds a live zero-length
entry to diverge onto). Rows are sorted by string offset before the blob is built, so a
run of dead entries cuts as one hole beside its bytes instead of fragmenting the
re-emitted segment into per-row runs (`_intern` matches at most one row, so the order is
free to choose). A candidate first interned AFTER the blob snapshot has no row and offers
only its bytes — such an entry is runtime-invisible by construction, which is why `T` is
interned before the snapshot. What a runtime intern can still need stays pinned exactly
as before, by the closed-window retraction: registry row names, the special-form offsets,
the reader tables.

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

**The trigger this section used to carry fired 2026-08-09** (the clack Workers were
exactly "a program that interns AND has a large dead-wrapper string set"), and the
per-entry form above is what it became. What it is worth, measured that day at each
example's own build settings against a same-day develop baseline (NOTE: those baselines
are well under the 2026-08-08 tables elsewhere in this file — the seq-conversion and
CLOS-lowering sessions moved every clack module in between):
`examples/cloudflare-workers/hello-clack` 370,901 -> 365,865 B (-5,036, gzip -1,386),
`httpbin-clack` 383,804 -> 378,768 (-5,036), `httpbin` 182,767 -> 180,350 (-2,417), the
todo-295 routed probe at `--optimize` 1,080,837 -> 1,076,014 (-4,823) — all verified
request-for-request on node against the baseline modules, and the hello-clack
`check.lisp` full stack prints byte-identically at `--optimize=size` and no flag under
wasmtime. The structural pin moved with it:
`anInterningProgramOffersPerEntryRangesRowsFallingWithTheirBytes` (WasmTreeShakerTest)
replaced `keepsEveryStringAProgramCanInternAtRunTime`, which pinned the old bail (the
interning hello's data section: >24 KB then, 431 B now), and
`optimizedModulesPrintExactlyWhatTheUnoptimizedOnesDo` gained three interning programs —
a runtime intern canonicalizing to a LIVE literal, a never-spelled name staying
self-consistent through the runtime table with cut holes present, and the zero-length
probe against `'||`.

`(print "Hello World!")` at `--optimize`: data 909 B -> 168 B, module 1,886 B -> 645 B
(**622 B** once the encoding shrank too, **511 B** once the prologue and the half-open
interval landed — its data section is down to 58 B and holds exactly the three seed cells,
`"\n"` and the framed literal, nothing dead at all). `(princ "Hello World!") (terpri)`
moved 625 B -> 514 B the same way, and its component 1,668 B; the whole spelling table
below re-measured 430-560 B core / 1,583-1,718 B component. A program on
the same gate that reaches the runtime `find-package` keeps the alist. Pinned
by `WasmTreeShakerTest.dropsStringsOnlyDeadBodiesInterned` /
`anInterningProgramOffersPerEntryRangesRowsFallingWithTheirBytes` /
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

### The print family's static-TYPE shortcut

The fold above needs a literal. The same reachability argument covers an argument
that is not a literal but whose TYPE the compiler knows, and there the numbers are
bigger, because what the generic dispatch drags in is not the renderer for the type
at hand -- it is every other renderer. Measured on the `size-report pi_approx` loop at
`--optimize`: the loop ending in `(princ "done")` is 1,770 bytes and the same loop
ending in `(princ <the f64 result>)` was **6,307** -- +3,777 bytes to print one
float, of which `_print_f64_no_nl` is **379**. The rest is `_princ_val` itself
(1,540), the character-vector normalizer it calls on every value before dispatching
(`_charvec_to_str`, 653) and the bignum / ratio / character / cons / array printers
reachable only from it. Two shortcuts, both in `WasmPrintCompiler`, both above the
literal fold:

- **`princ` of a certainly-STRING form is compiled as `write-string`**
  (`compiler/StringValuedForms.certainlyString`, the predicate `write-string`
  already consults to skip `_charvec_to_str`). Same text, same returned object,
  and it works with an explicit stream too because `write-string` takes one.
- **`princ` / `prin1` / `print` of a certainly-DOUBLE form unboxes the
  `TYPE_FLOAT` struct and calls `_print_f64_no_nl` directly**
  (`compiler/DoubleValuedForms.certainlyDouble`: an immediate literal-double
  argument of `+ - * /`, which is strictly narrower than the backends' own
  `hasDoubleLiteral` f64-path predicate, so every form it accepts is one the
  arithmetic already compiled to a boxed double). That IS the arm both dispatches
  take for a float, so the output is identical by construction. Only for the
  hard-coded standard output: an explicit stream, or an active
  `*standard-output*` rebinding, renders to a string first and keeps the general
  path.

Printing one float now costs **522** bytes over the same loop (6,307 -> 2,292 for
the whole module), and a module whose only output is a computed string is 504
bytes. Pinned by
`WasmLispCompilerIntegrationTest.staticallyTypedPrintArgumentsPrintWhatTheValueDispatchWouldHave`
and the `statically-typed-print-arguments` ci-spec case (all four backends).

**Re-evaluation trigger:** the two predicates are the whole risk surface -- a form
wrongly admitted prints as the wrong type rather than failing -- so a new entry is
earned by checking every backend's emission for that operator, not by "it usually
returns a string/float". The obvious next entries are the stream-carrying spellings
(a float would need a float-to-string helper the string case gets for free) and
`format`'s `~A` runtime path.

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

**A COMPUTED argument reaches this fold too, when the computation is itself constant**
(`.kb/pure-builtin-fold.md`, 2026-08-07): a pure built-in over literal arguments is
evaluated by the compiler before either backend sees the print, so `(princ (* 6 7))`
and `(princ (length "Hello World!"))` compile to the same 410-byte module as
`(princ 42)` — byte for byte — instead of 5,740 / 5,103 B. That composition — a general fold feeding the
print/format literal folds — is where the bytes are; the fold's own arithmetic is
worth three instructions.

### The `name` section is DROPPED, not copied (todo-270, 2026-08-06)

A `name` custom section maps **function and type indices** to names, and this pass has just
renumbered both — copying it through described the module's old shape. It is now dropped
(`WasmTreeShaker`, `SEC_CUSTOM`); every other custom section is index-free and still copied.
The rontolisp backend emits none, so this is invisible on a compiled core module and decisive
on the hand-written WAT blobs the component wrapper embeds: the base adapter's name section
alone is 1,438 B of its 3,953. Pinned by `WasmTreeShakerTest.dropsTheNameSectionRenumberingHasInvalidated`.

### Identical bodies are emitted once (todo-322, 2026-08-11)

`am.ik.wasm.WasmBodyFolder` runs as the tail of `WasmTreeShaker.shakeWithRemap`, so every
shaken artifact gets it (GC Preview 1, the `--component` core, the shaken adapter,
`--no-gc`) at every `eliminatesDeadCode()` level: when two or more defined functions
declare canonically-equal types and carry byte-for-byte identical code entries, one body
survives and every `call`/`ref.func`/export/start/global-initializer reference is
redirected to it. The pass iterates to a fixpoint -- folding the twins can make their
CALLERS byte-identical in turn (measured: the `--no-gc` export wrappers of two identical
defuns differ only in their `call` target, so they fold on the second pass) -- and then
one more `dropUnreachable` run collects any type entry the fold orphaned. "Canonically
equal" is same index, or same position in byte-identical `rec`-group entries neither of
which references its own members: byte-identical entries name identical EXTERNAL indices,
so their closures are equal under wasm-GC canonicalization, while inside a
self-referential group byte equality proves nothing (the same immediate resolves to a
different group). The distinction only matters on `--no-gc`, which declares one type
entry per function; the GC writer shares signature entries, so there the key degenerates
to "same declared index".

**The identity question, answered (the reason folding is sound):** nothing in the emitted
module shapes can observe a function's identity through its code index. A first-class
function value is a closure STRUCT whose dispatch id is plain `i32` data -- two
definitions that fold keep distinct funcIds, distinct `_lookup` rows and distinct
dispatch-ladder arms; the arms just `call` the same body. `eq` on WASM is `ref.eq` plus
char/bignum/string value fallbacks and has NO closure arm (`WasmEmitHelper
.emitEqComparison`), so `(eq #'f #'g)` is NIL for two identically-bodied defuns on every
backend, fold or no fold -- pinned four-backend by the ci-spec case
`identical-function-bodies-keep-distinct-identity` and under `--optimize` by the
fold program in `optimizedModulesPrintExactlyWhatTheUnoptimizedOnesDo`. (`(eq #'f #'f)`
already diverges interpreter-vs-compilers -- fresh struct per `#'` -- and stays out of
the pin.) `ref.func` values have no comparator, and multiple exports may alias one
function index; the component wrapper reaches core functions by export NAME only.

Measured 2026-08-11 on the zlib rows (the todo's probe: 362 bodies, 28 duplicate groups,
6,639 B redundant at `=size`), each module gunzipping the fixture byte-for-byte after:

| zlib row | before | after |
| --- | --- | ---: |
| `--optimize` | 171,312 | 162,340 (**-5.2%**) |
| `--optimize=size` | 137,430 | 130,658 (**-4.9%**, 362 -> 314 bodies) |
| `--component --optimize=size` | 142,110 | 135,316 (-4.8%) |

Every Worker row moved with it (`hello-ningle` raw -176 KB / -6.9%, the full-tiny-routes
rows ~-4.6%, the rest -0.4..-0.7%), with one honest nuance: identical bodies were bytes
gzip already compressed almost to nothing, so on the small clack Workers the RAW win
comes with a few hundred bytes MORE gzip (renumbered call immediates compress a little
worse); the big modules win on both axes. Raw is what wasmtime/V8 compile and hold, gzip
is the Cloudflare transfer budget -- the rows in `size-report/results/` carry both.

Structural pins: `WasmBodyFolderTest` -- a module with N identical bodies emits one and
the emitted module holds NO duplicate (type, body) pair at all (the fixpoint), on the GC
backend at both levels and on `--no-gc` (where both export names end up aliasing the one
wrapper). The corpus stays covered by `WasmTreeShakerCorpusTest` (wasm-tools validation)
and `JvmClassShakerCorpusTest`'s output equality. The `-Drontolisp.wasm.debug-func-sizes`
dump labels a folded group by its survivor (first pre-image wins in `dumpFuncSizes`).

**The JVM twin is measured, not implemented** (`.todo/327`): the zlib class at
`--optimize` has the same shape -- 353 `Code` methods, 48 duplicates, 8,331 B redundant
(5.2% of code bytes), the same accessor tail. Folding there means redirecting
`invokestatic` constant-pool immediates and letting `JvmClassShaker` drop the orphaned
method, but methods are reachable BY NAME (the dispatch/eval roots, the reflective
`_apply` edge), so the survivor set needs its own soundness argument -- the todo carries
the numbers and the design sketch.

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
- **the names a runtime SYMBOL designator can resolve.** On WASM this source is live when the registry is (`usesEval || usesRuntimeDesignator || usesApplyRuntime` since todo-315 -- the apply tier keeps symbol designators resolving without the interpreter, `.kb/eval-runtime.md`). `_lookup` matches interned offsets (WASM) / string constants (JVM), so a registry row is reachable only when the program already put that exact name there for another reason — a quoted symbol, a string literal, an `intern` of a literal. `StringTable.isInterned` and `ConstantPool.hasStringConstant` are the two probes, and the name is tried six ways: canonical, the `::`->`:` alias row's spelling, the bare member name after the last colon — and (2026-08-08) the FRAMED string-literal spelling of the full name and of the member (`"NAME"`, quotes included: a string literal interns via `LispString.literal()`, so `(intern "RUN" pkg)` — clack's handler discovery — spells `"RUN"`, not `RUN`; before this the probe missed every literal-string designator on both backends), plus the keyword spelling `:member` (`uiop:symbol-call :pkg :member` spells both halves as keywords).

  **The three widened spellings apply only while the program contains a symbol BUILDER
  at all (2026-08-09)** — `RuntimeNameProducers.anySymbolBuilder`: `intern`,
  `find-symbol`, `make-symbol`, `uiop:symbol-call`. Without one, no runtime path can turn
  a string or keyword constant into a designator, so a gate-closed program stops paying
  rows for defuns whose member name merely collides with an unrelated literal (the
  over-approximation cost the 2026-08-08 table below records as its two `+` rows).
  `make-symbol` is in the set as the safe over-approximation: its product can never match
  a registry row on WASM (a fresh, never-canonicalized offset), but the JVM registry
  compares string VALUES. Two of the compiler's own emissions are shape-exempt from the
  trigger, each provably unable to produce a FUNCTION designator: the literal
  keyword-package intern `(intern X :keyword)` (http-server.lisp interns the request
  method and protocol this way, which otherwise holds the probes open for every
  Worker/serve program — it only makes keywords, and no row key begins with a colon) and
  the injected `(defun %slot-name-key (n) (intern (symbol-name n)))` (the identity
  exemption that fold was made a separate defun to allow,
  `LispMacroExpander.slotNameKeyDefun`). Claw-back measured on the gate-closed `httpbin`
  worker: `--no-wasi --optimize=size` 180,350 -> 178,971, `--component --optimize=size`
  194,801 -> 193,430; the clack workers are BYTE-identical (lack's `find-symbol` is a
  real builder, and their probes must stay on). Pinned by
  `widenedProbesApplyOnlyWithASymbolBuilderPresent` and
  `theCompilersOwnInternShapesDoNotWidenTheProbes` (WasmTreeShakerTest, paired-difference
  function counts) plus `aFramedSpellingWithoutABuilderDoesNotHoldARow`
  (JvmClassShakerTest, method survival + the run).

**The gate turns itself off entirely** (every function stays dispatchable) under `--dynamic` and whenever `compiler/RuntimeNameProducers.anyNameResolvable` holds — the program contains a DATA EVALUATOR: `eval`/`read`/`read-from-string`/`load`, whose function names arrive from outside the module, or the injected `~/name/` renderer arm (`FormatRenderer.FUNCTION_DESIGNATOR` — a control string is runtime data, and the arm is injected exactly when a control string in the program spells the directive, so its presence IS the trigger; the stub a directive-free program gets never fires it). That class is shared by both backends on purpose: a name that stops resolving on one has to stop resolving on the other. Compile with `-Drontolisp.debug.dispatchgate=true` to have the offending operator NAMED, and to see how many functions stayed dispatchable.

**The symbol BUILDERS no longer bail (2026-08-08)** — `intern`, `find-symbol`, `make-symbol`, `symbol-function`, `fdefinition`, `fboundp`, `uiop:symbol-call` used to turn the gate off wholesale, and were the reason a clack program shipped every defun dispatchable (lack's `locate-symbol` is `(find-symbol "RUN" pkg)`, and it is LIVE — clack's whole handler-discovery protocol runs through it). The split is sound against the probes rather than by intuition: a symbol a builder produces is built FROM A STRING, and any string the program holds is a compile-time constant the widened probes above already read, in every spelling the lowerings emit. What escapes them is a name assembled out of COMPUTED pieces, and that is verbatim `LibraryDefunPruner`'s documented carve-out — the ordinary undefined-function error, `--dynamic` to restore late binding. This retired both scan exemptions with the trigger that made them necessary: the slot-name-fold identity match (`%slot-name-key`'s `intern` is just an intern now) and the keyword-package-intern shape (todo-260) — an `(intern NAME :keyword)` is as gate-neutral as any other intern, and funcalling a runtime-built keyword still fails because no row key begins with a colon (a keyword can never name a defun; the defun's implicit block rejects it). Pinning tests: `internDoesNotHoldTheFuncallDispatchGateOpen` (WasmTreeShakerTest — computed intern shakes like keyword intern; `(eval (read))` still bails), `internDoesNotHoldTheDispatchGateOpen` (JvmClassShakerTest — incl. the quoted-intern shape now shaking, the framed-string resolution of a literal-intern funcall, and the eval bail), `keywordInternStaysInternedInAGateShakenModule` (WasmLispCompilerIntegrationTest).

Without the data-evaluator bail the gate is not sound, and the failure is a trap rather than a diagnosis — the original measurement was 32 tests across both backends; after the split every one of them passes because its name is spelled in the module (`(intern "EX-FN" :pkg)` folds to a qualified symbol, `uiop:symbol-call`'s keywords probe as `:member`) or its program carries a data evaluator and still bails (`(eval (read))`).

**What the split is worth, measured 2026-08-08** (`--no-wasi --optimize`, node-verified request-for-request; the clack rows also need the `--no-wasi` filesystem stub — `.kb/wasm-export-no-wasi.md` — which removes clack `%load-file`'s dead `read`+`eval` so the data-evaluator bail stops firing, and drops the reader+eval runtimes with it):

| program | before | after |
| --- | --- | --- |
| `examples/cloudflare-workers/hello-clack` | 1,133,471 | **514,999 (-54.6%)** — 172 of 750 defuns dispatchable |
| `examples/cloudflare-workers/httpbin-clack` | 1,156,633 | **534,777 (-53.8%)** |
| `examples/cloudflare-workers/httpbin` (no clack; its gate already closed) | 245,525 | 248,956 (+1.4%) |
| the same `worker.lisp` at `--component` | 260,134 | 263,557 (+1.3%) |

The two + rows are the price of the widened probes: a program whose gate was already closed now keeps a few more rows (any defun whose member name coincides with a keyword or string literal somewhere in the module). That over-approximation is the safe direction — a kept row costs bytes, a missing one costs a resolution — and 2026-08-09 clawed most of it back by probing the widened spellings only when a symbol BUILDER is present at all (the bullet above): the same `httpbin` worker gave back 1,379 B (`=size`) / 1,371 B (`--component =size`) while the clack modules stayed byte-identical. The clack runtime path this all exists for — `clackup` → `find-handler` → `find-package-or-load` → `(find-symbol "RUN" pkg)` → `apply` — resolves through the framed-string probe (`"RUN"` is a string literal in `clack.handler:run`), verified request-for-request on node against the shaken module, on the JVM, and on the interpreter, plus the full `ci-spec.yaml` native run (1,300 cases, 4 backends).

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
2. with that gone, the generated slot-name fold's `intern` became the blocker for `cl-ppcre` (worth the whole -21.9% above) — exempted by identity then, moot since the 2026-08-08 split (an `intern` no longer triggers at all). Its earlier rejection was recorded as "harmless, but not what holds the gate open"; retiring blocker 1 retired that reason, which is why it was re-taken in the same session.

The two rows that bailed in that table did so through library-side symbol builders — `jzon`'s `(fdefinition key-fn)`, `cl-postgres`'s `(intern (string '#:make-ssl-client-stream) :cl+ssl)` — and the 2026-08-08 split re-classified exactly this class: both now gate. `jzon`'s runtime `key-fn` designator arrives as a quoted symbol at every real call site (spelled, so its row survives); cl-postgres's SSL forge is dead at run time (guarded by a `find-package :cl+ssl` that fails here) and its name now simply stops resolving — the documented carve-out, loud if ever reached. (The `read` half of postgres-hello had already gone separately: ironclad's `#@` `array-reader` hook is skipped by `LispMacroExpander.isDeadReadtableHook`, so the defun is pruned and the reader runtime is not emitted.)

One refinement was tried and REJECTED on measurement, and its lesson is now baked into the probes rather than the trigger: judging the `intern` ARGUMENT shape shrank nothing and broke `internIntoALiteralPackage` on both backends, because the two-argument lowering folds the literal into the qualified symbol before either probe can see it — which is WHY the split judges nothing per-site and lets the six-spelling probe set carry the whole weight.

Tests: `componentCoreIsTreeShakenUnderOptimize` (shrinkage + a scalar and a string-returning export invoked under wasmtime, i.e. the canonical-ABI helpers survived) and `optimizedServeComponentStillServesUnderWasmtimeServe` (a shaken serve component actually answers a request), both in `WasmLispCompilerIntegrationTest`; `FormatRendererTest.theFunctionDesignatorArmIsInjectedOnlyForAProgramThatSpellsTheDirective` for the renderer half.

### A designator the compiler can READ never enters `valueFuncIds` (todo-323, 2026-08-11)

The gate above decides which VALUES get a case. This is the other half: an operator that
funcalls a function argument no longer MAKES a value out of a designator it can read.
`Wasm/JvmDesignatorCall` is the one decision -- `compiler.FunctionDesignators.literalName`
(a literal `#'name` / `'name`, `normalize`d) plus the backend's own registry at the arity
in hand -- and the six sites that ask it are `funcall`, `mapcar`, `mapc`, `mapcan`,
`reduce` and `sort` on BOTH compile backends. A resolved site emits the direct call its
head-position spelling would have emitted; the funcId is never materialized as a closure,
so it joins neither `valueFuncIds` nor the ladder.

**Why the direct call is the same call.** A ladder case IS that instruction sequence:
`WasmRuntimeBuilder.buildDispatchBody` pushes the closure's env (null, for a `#'name`
value), the arguments, and -- for a variadic target -- the surplus arguments linked into
the rest list, then `call`s; `JvmRuntimeBuilder.renderCase` is the same minus the env,
which the JVM's defuns do not take. `WasmDesignatorCall.emitCall` reproduces exactly that,
which is why the two variadic shapes are its only real code: reached at exactly the
required count it appends the empty rest list, wider than it it evaluates every argument
into a temp first (left to right, as the dispatching route does) and links the surplus.

**What is deliberately NOT resolved**, all three keeping the dispatcher: a computed
designator; a name no registry answers (a car/cdr composition that synthesizes a lambda,
a `--dynamic` deferral); and **an arity the callee cannot take**. That last is the one to
not "fix": the arity contract of these operators is a RUN-time one, so `(mapcar #'cons
'(1 2))` must fail where it failed before -- a WASM trap, the ladder's default arm (nil)
on the JVM -- rather than becoming the compile error the head position would give. On WASM the resolution is asked
BEFORE the dispatch ceiling check (`WasmFunctionCallCompiler.compileFuncall`), because a
ceiling on the dispatchers cannot bind a call that uses none.

Lisp-2 shadowing needs no handling here: `flet`/`labels` rewrite both `(f x)` and `#'f`
into their binding VARIABLE before any backend sees the form (`.kb/flet-labels.md`), so a
surviving `(function name)` names the global by construction.

Measured on the `zlib` rows, each module still gunzipping the fixture byte for byte:

| zlib row | before | after |
| --- | --- | ---: |
| (no flag) | 342,942 | 341,789 (-0.3%) |
| `--optimize` | 162,340 | 158,708 (**-2.2%**) |
| `--optimize=size` | 130,658 | 127,026 (**-2.8%**) |
| `--component --optimize=size` | 135,316 | 131,677 (-2.7%) |
| the JVM class, `--optimize` | 202,708 | 196,914 (**-2.9%**) |
| the JVM class, no flag | 370,492 | 372,223 (+0.5%) |

The last row is the honest cost and it is only there: a variadic callee reached wider than
its required count links the rest list AT the call site now, where the ladder case used to
hold that code once for every caller. It is a trade the tracked configurations take
happily, and `--optimize` is where it pays back.

What moves the bytes is NOT the callee -- a mapped function is called from the site either
way -- but what the ladder stops fanning out to. `STRING=` (2,449 B in the zlib probe) is
the shape: `expandRuntimeFindPackage` emits `(assoc key '(...) :test #'string=)` on a path
this program never runs, and the live arity-2 ladder kept it anyway. Reading the designator
turns that into a direct call FROM DEAD CODE, and dead code shakes. 137 -> 136 of 498
defuns dispatchable, 111 -> 105 funcIds materialized as values
(`-Drontolisp.debug.dispatchgate=true`).

The lever this half does not reach on its own is a designator BOUND to a temp, which the
next section closes.

Pins: `WasmTreeShakerTest.aLiteralDesignatorSiteBuysNoLadderCase` and
`JvmClassShakerTest.aLiteralDesignatorSiteBuysNoDispatchCase` -- the same paired
difference on each backend, a literal `mapcar` designator against the same designator
behind a variable, where the ladder's absence is what drops a function the program only
SPELLS. Behaviorally `WasmLispCompilerIntegrationTest.literalFunctionDesignatorsCompileAndRun`
/ `JvmLispCompilerTest.compileAndRunLiteralFunctionDesignators` (both variadic shapes, both
routes answering alike), `compileAndRunLiteralDesignatorOfTheWrongArityKeepsTheDispatcher`
for the declined arity, and the four-backend ci-spec case
`literal-function-designators-answer-like-computed-ones`.

**A gate test's scaffolding is affected, and silently.** Five of the tests above used
`(print (funcall 'f))` purely to keep a ladder emitted at all; that spelling is now a
direct call, so the ladder disappeared and their probes had nothing left to keep -- one
turned red (`aFramedSpellingWithoutABuilderDoesNotHoldARow`) and the paired-count ones
would have gone vacuously green. They funcall a COMPUTED designator now (`(funcall (car
(list #'f)))`; a variable was the first fix and stopped working when the section below
landed). Any future test about what the ladders keep alive owes the same care.

### A designator BOUND to a temp is not a value either (todo-328, 2026-08-11)

The section above reads the designator AT the call site, and every expander that NAMES one
to avoid re-evaluating it undoes that: `LispMacroExpander.expandMap` binds `(let
((__map_fn #'identity)) ... (funcall __map_fn (elt ...)))` -- and the `coerce` lowering
emits `(map 'list #'identity x)`, so every program that coerces a string carried one --
as do `expandMapFamily` (`maplist`/`mapcon`/`mapl`) and `expandEverySomeFamily`. The
binding materialized the closure, the ladder got its case back, and everything that case
reaches was pinned again.

`compiler.LetBoundDesignators.propagate` closes it in ONE place for every such expander,
present and future: **a `let` binding whose init is a literal designator naming a
registered function, and whose every use in the body is a function-designator position, is
propagated into those uses and the binding dropped.** `Jvm/WasmLetCompiler` call it on the
way in, so a hand-written `let`, the nested lets `let*` lowers to, and every
macro-generated binding all go through the same rule; the resolved sites are then ordinary
written-out literals to `Jvm/WasmDesignatorCall`.

**Why the backends and not the expanders.** Leaving the literal AT the funcall site in
`expandMap` was measured first -- 75 bytes on the zlib rows -- and declined for two
reasons that still hold: the interpreter would then evaluate the designator once per
element instead of once, and a designator naming an UNDEFINED function would stop
signalling over an empty sequence (the loop body never runs). Rewriting in the backends
keeps the interpreter out of it entirely -- it never sees the rewrite -- and one rule
covers every binder instead of one edit per expander.

**The safety argument is a COUNT, not a walker.** The pass certifies the occurrences it
understands (the designator argument of the six operators the backends resolve) and
separately counts EVERY occurrence of the name in the body with a deliberately shape-blind
scan -- quoted data, binding lists, dotted tails and all. It rewrites only when the two
agree, which is what lets the substitution then be shape-blind too. Everything the
certifying walk does not understand therefore shows up as an occurrence with no
certification and simply keeps the binding: a use as a plain VALUE (which must keep its
ladder case, or the value stops resolving), a `setq`, an inner binding or lambda parameter
of the same name, a `(funcall f ...)` shaped list that is a datum rather than a call. The
walk stays opaque at the heads that carry data (`quote`, `declare`, the `def*` family, a
`case` clause's keys, a lambda list) for exactly one reason: descending somewhere
non-evaluated is free (the occurrence is uncertified and the count refuses the binding),
but CERTIFYING something non-evaluated would corrupt it.

**Three guards beyond the count.** A SPECIAL name is never dropped -- a dynamic binding is
one a callee reads, so it is not the body's alone to see. A name bound twice in the same
binding list is left alone. And the designator must name a function the backend's registry
answers (`ctx.functions`): that is what makes the substitution value-identical -- both
spellings compile to the same static funcId, `--dynamic` included, since
`Jvm/WasmFunctionFormCompiler` defer to the runtime only for a name the registry does NOT
hold -- and it is also what keeps `#'cadr` out, whose value is a car/cdr composition
SYNTHESIZED per site (`.kb/core-representation.md`), where duplicating would cost more
than the binding saves.

The WASM fusion registry is untouched by construction: it registers a `__FLET*` binding
whose init is an eligible integer-tree LAMBDA (`.kb/wasm-int-fusion.md`), and this pass
only ever takes a binding whose init is a literal designator.

**What the table deliberately does not list, and the trigger to revisit it.** The
certified positions are the six operators the backends RESOLVE, not every operator that
ends up funcalling its argument. `map`/`maplist`/`mapcon`/`mapl`/`every`/`some` bind the
designator in their own expansion, so a LITERAL written at one of those sites is taken
here anyway -- at the generated binding, which is the whole point. What stays outside is a
literal that reached them through a HAND-WRITTEN variable (`(let ((f #'oddp)) (every f
xs))`): the generated binding then holds the variable, not a literal, and both bindings
stay. Adding those operators' designator slots to `designatorSlot` is the lever if that
shape turns up in a real program; it was left out because every slot in that table is a
claim about an expander that has to keep being true.

Measured against the same commit, every zlib row still gunzipping its fixture byte for
byte and every Worker still answering:

| row | before | after |
| --- | --- | ---: |
| zlib, `--optimize` | 136,135 | 136,068 |
| zlib, `--optimize=size` | 107,695 | 107,628 |
| zlib, `--component --optimize=size` | 112,199 | 112,135 |
| **the JVM class, `--optimize`** | **194,772** | **193,720 (-0.5%)** |
| `hello-tiny-routes` Worker, `--optimize=size` | 269,259 | 268,832 |
| `httpbin` Worker, `--optimize=size` | 162,658 (51,201 gz) | 162,640 (50,597 gz) |
| `hello_world`, no flag | 126,057 | 126,033 |

Small on wasm, ten times that on the JVM class, and nothing regressed anywhere. Two things
the spread says. The JVM pays more for a ladder case (a variadic callee's rest-list
linking lives in the case, not at the site), so dropping the value is worth more there.
And `hello_world` -- which maps nothing -- moving at all is the shared runtime's own
`let`-bound designators going direct; at `--optimize` those functions are shaken anyway,
which is exactly why the flagged rows move less than the unflagged one. What a dropped
binding BUYS is a ladder case, so the lever pays where the ladder is otherwise dead (the
shape that made `STRING=` shakeable in the section above) -- and it now pays there
whichever way the program spells the designator.

Pins: `WasmTreeShakerTest.aDesignatorBoundToATempIsTheSameDirectCall` (the bound spelling
is the written-out literal's own module, byte for byte) and
`JvmClassShakerTest.aDesignatorBoundToATempIsTheSameDirectCall` (the same method set, the
same output), each paired with the same binding plus a VALUE use, which still dispatches.
`LetBoundDesignatorsTest` covers the rule itself. Behaviorally the four-backend
`literal-function-designators-answer-like-computed-ones` case grew the three shapes that
KEEP the binding -- value use, `setq`, shadowing -- because the interpreter answers them
without ever seeing the rewrite.

## What ROUTING costs a clack module: cl-ppcre, measured and decided (todo-295, 2026-08-08)

Adding tiny-routes to a Clack reactor nearly triples the module, and the extra is
not tiny-routes (72 functions, ~72 KB of code) — it is **cl-ppcre, its only
dependency**: a route template is compiled to a scanner at RUN time
(`path-template.lisp` even builds one at LOAD time, `*path-token-scanner*`), so
the whole regex pipeline (lexer -> parser -> converter -> optimizer -> closure
compiler) is genuinely reachable and the shaker is right to keep it. Probe =
`examples/cloudflare-workers/hello-clack`'s worker plus three tiny-routes routes
(one `:id` template), compiled on that example's own build line (`--no-wasi`),
node-verified request-for-request; gzip = `gzip -9 -n`:

| routed Worker probe | raw | gzip | functions | code | data |
| --- | --- | --- | --- | --- | --- |
| clack base (`hello-clack`, `--optimize`) | 514,999 | 140,786 | 544 | 457,849 | 55,943 |
| + tiny-routes, `--optimize` | 1,373,470 | 336,399 | 1,229 | 1,290,334 | 81,003 |
| + tiny-routes, `--optimize=size` | 1,219,894 | 286,621 | 1,223 | 1,136,783 | 81,003 |

Five levers were measured in one session; one taken, the rest recorded here with
the reason so they are not re-derived:

1. **TAKEN — the Worker examples build at `--optimize=size` now** (all four
   wasm-GC `build.sh` lines; `hello` stays `--optimize` because it is `--no-gc`,
   where the level is a documented no-op). Same day, same machine, node 24, the
   `httpbin` README's own harness: `httpbin` 248,956 -> 200,155 raw / 76,063 ->
   58,793 gzip, `httpbin-clack` 534,777 -> 474,150 / 146,688 -> 124,756,
   `hello-clack` 514,999 -> 459,059 / 140,786 -> 121,525, the component build's
   three core modules -> 212,219 B. The price on a Worker is the right trade in
   both directions: warm requests +24-42% RELATIVE but 3-11 µs ABSOLUTE
   (`httpbin-clack` GET 0.0179 -> 0.0241 ms, POST 0.0418 -> 0.0531 ms; the
   routed probe 0.0095 -> 0.0124 ms), while `_initialize` gets FASTER (13.3 ->
   12.5 ms; the routed probe 53 -> 47 ms) because V8 has less code to compile —
   and isolate startup, not the microseconds, is what Cloudflare budget-checks.
2. **cl-ppcre's eight `define-compiler-macro`s never fire on the routing path,
   and firing would not shrink anything.** The routed module is BYTE-IDENTICAL
   with all eight stripped from the source: every regex designator
   `path-template.lisp` passes is a variable or a computed `concatenate`, never
   `constantp`. And where one does fire (a user's literal `(ppcre:scan "…" x)`),
   it ADDS 179 B — the `load-time-value` slot — and removes nothing, because the
   scanner BUILDER still ships to run at load time. They are worth having for
   run/start-up time (`.kb/compiler-macros.md`), never for size.
3. **REJECTED AS A DEFAULT, then delivered AS AN OPT-IN (todo-296) — a
   leaf-module substitution of tiny-routes' `path-template.lisp`**
   (the `ShimLibraries.leafModuleForms` tier, `.kb/asdf.md`). Measured with a
   ppcre-free segment matcher swapped into the quicklisp tree: the shim ALONE
   buys **-0.9%** (1,373,470 -> 1,360,484) — see the next lever for why — so
   delivering the win also requires a replacement `.asd` that DROPS the
   `:cl-ppcre` dependency. That two-tier substitution reaches 536,895 / 146,809
   (-60.9%, routing then costs +21,896 B over the clack base, and `_initialize`
   drops 54 -> 14 ms) — but as a DEFAULT it breaks `:regex t` (documented
   upstream API, would have to signal), silently changes keyword-template
   semantics (upstream interprets the NON-token template text as regex —
   mid-segment tokens, metachars), and any program that touches `ppcre:`
   anywhere gets the engine back WITH routes now matching by different rules
   than the user's own regexes. On a library whose value is loading verbatim
   (the point of the ASDF work), that is the wrong trade for a silent change —
   so todo-296 shipped exactly this two-tier substitution as the OPT-IN system
   **`tiny-routes/lite`**, with both objections dissolved by construction: the
   user asks for it by name in their own source, the matcher signals at
   route-build time on everything it does not reproduce (metachar templates,
   `:regex t`), the accepted `:name`-token subset is pinned
   template-for-template against the real engine, and co-loading the two
   systems is refused. Mechanics + pinning tests in `.kb/asdf.md`; user docs in
   the asdf-systems guide. Measured 2026-08-08 on this same probe:
   **1,219,894 -> 487,146 B raw / 286,621 -> 128,888 B gzip**, and the
   committed `examples/cloudflare-workers/httpbin-tiny-routes` Worker
   (httpbin-clack's endpoints + a `/status/:code` template) is
   **501,689 / 132,886** where the identical file over full tiny-routes is
   1,236,811 / 290,230 — the under-1,000,000-B-raw target met with ~0.5 MB of
   headroom.
4. **Where the bytes really are: loaded-but-unreferenced cl-ppcre is anchored by
   its CLOS surface — 823,589 B on this module.** With the ppcre-free shim in
   place and the dependency still loaded, ZERO references remain, yet only 0.9%
   leaves: `LibraryDefunPruner` KEPT every `defgeneric`/`defmethod`/`defclass`/
   `define-condition`/`defstruct` as a root (`.kb/library-defun-pruning.md`) and
   cl-ppcre's API and node tree are exactly that; at the module level every
   method body is materialized as a closure at load time, so it is in
   `valueFuncIds`, dispatchable, and live through the ladders. The shim-vs-nodep
   delta — 648 functions, 800,319 B code, 22,392 B data — was therefore the
   measured ceiling of the CLOS-aware-shaking lever on this module (collected in
   todo-290, whose other levers all landed 2026-08-09). **The lever itself
   LANDED 2026-08-09**: the pruner's CLOS candidates + per-method gates
   (`.kb/library-defun-pruning.md`, "The CLOS definition kinds are candidates
   too") collect dead CLOS at the AST level, before either module half sees it
   — which took ~30% out of every clack Worker (lack-util's ironclad/core rides
   in unreferenced) and mooted a separate `valueFuncIds` half: a pruned method
   is never emitted, and a kept one is genuinely dispatchable. What the AST
   argument still cannot touch on THIS probe is the `let`-over-`defmethod` root
   (build-replacement-template's binding initform calls `create-scanner`).
   The string-blob lever's cap was the 58,756 B `data[3]` segment
   of an 81,003 B data section — an order of magnitude smaller, and the landed
   per-entry form (see "String blob" above) took 4,823 B of it on this probe:
   most of the segment is LIVE rows and literals, so the ceiling was never
   collectable.
5. **Splitting cl-ppcre's parse half from its match half: investigated, not a
   plan.** It would pay only if a scanner could be built at COMPILE time and the
   builder left out of the module; a cl-ppcre scanner is a tree of closures
   closing over each other (`closures.lisp`), which no mechanism can serialize
   into an artifact — `load-time-value` runs INSIDE the module at load time, so
   the builder ships regardless (that is also why lever 2 cannot shrink
   anything).

The half these levers leave OPEN — an application that calls `ppcre:` itself
and therefore cannot drop the engine the way a routed one now can — is
`.todo/297`, which starts from the numbers above (the engine's measured module
share, the 823,589 B zero-reference anchor, and the two settled non-levers).

## What a cl-ppcre-USING application costs, per feature (todo-297, 2026-08-08)

Per-feature probes, each `(ql:quickload "cl-ppcre")` plus the named calls,
compiled to wasm-GC Preview 1 at `--optimize=size` and node/wasmtime-verified
(**EH mode: quickloading cl-ppcre alone puts the module in EH mode**, so both
wasm run lines need `-W exceptions=y`); gzip = `gzip -9 -n`:

| probe | raw | gzip | Δ raw over zero-reference |
| --- | --- | --- | --- |
| no-ppcre baseline (`(print "loaded")`) | 505 | 385 | — |
| zero-reference (quickload only, no call) | 747,882 | 175,635 | 0 |
| (a) one literal `ppcre:scan` | 748,168 | 175,749 | +286 |
| (b) = a + `scan-to-strings` (register groups) | 757,373 | 178,905 | +9,491 |
| (c) = b + `regex-replace-all` with `\1` | 757,664 | 179,051 | +9,782 |
| (d) `create-scanner` over runtime input + `scan` | 748,060 | 175,755 | +178 |
| (e) `split` | 761,050 | 179,785 | +13,168 |
| all five together | 770,201 | 183,182 | +22,319 |

(At `--optimize` instead of `=size`: zero-reference 859,012, probe (a)
859,297 — the same +285 B story. Consistent with the routing session's
823,589 B zero-reference anchor, which was a delta between two routed modules
at `--optimize`.)

The absolute numbers above are PRE-`%seq-to-*`-trio; the same probe (a)
measures 678,977 raw after the trio landed (same day,
`.kb/seq-conversion-runtime.md`), and 585,940 raw after the follow-up session
outlined the dispatchers' no-applicable-method tail and aligned the CLOS
`apply` forwarding (below). The per-feature DELTAS and every verdict below are
unchanged — these levers move the shared floor, not the increments.

**The map's verdict is the branch the item predicted: scanner building is live
even for (a), so the whole engine cost IS the anchor and the shaking levers
cannot pay.** A single literal `scan` costs +286 B over merely loading the
engine; the spread between the cheapest and the richest API usage is 22 KB on a
~748 KB module. Consequences, lever by lever (the todo-297 numbering):

- **Lever 3 (defun-level pruning over spliced trees) is ALREADY DEPLOYED and
  is why the increments are this small.** `LibraryDefunPruner` has covered
  ASDF-spliced third-party trees since 2026-07-27 (`.kb/library-defun-pruning.md`,
  "Prunable set, part 2"; measured -2.8% on a cl-ppcre demo) — the todo's
  "today the pruner covers only rontolisp's own bundled libraries" premise was
  stale when filed. Behaviorally visible above: probe (a) does not pay split's
  +13,168 or the replace machinery — the unused API surface leaves at the AST
  level (and the funcall gate + shaker behind it). Its residual on a USING app
  is ~zero: what stays is CLOS-anchored, not defun-anchored.
- **Lever 2 (CLOS-aware shaking) cannot pay on a USING app.** The engine's 27
  defgenerics (`convert-compound-parse-tree`/`convert-simple-parse-tree` keyed
  by parse-tree token, the `flatten`/`gather-strings`/`compute-min-rest`/
  `compute-offsets`/`start-anchored-p`/`end-string-aux` optimize walkers,
  `create-matcher-aux` + the seven repetition-closures matcher builders,
  `copy-regex`/`regex-length`/`case-mode`/..., and the `scan`/`create-scanner`
  API generics themselves) ARE the build pipeline. A method-aware reachability
  from any one used entry point keeps essentially all of them, because the
  parse tree is runtime data — every node class is instantiable from
  `create-scanner`, so every method is reachable. The only method surface a
  scan-only app could shed is the replace family's
  (`build-replacement-template`, a few KB) — against a ~748 KB live core. The
  823,589 B zero-reference ceiling collapses to noise the moment one entry
  point is real. (For a program that loads the engine but never calls it, the
  answer is not a shaker — it is not loading the engine (the todo-296 routing
  case), and since 2026-08-09 the AST pruner's CLOS candidates collect the
  loaded-but-unreferenced side anyway — see lever 4 in the routing section
  above.)
- **What CAN move a module that keeps the REAL engine is code DENSITY, not
  shaking** (user-redirected goal, 2026-08-08): the probe's wasm composition
  is 93% code section (696,108 B in 863 functions, five of them 93 KB
  together), and the diagnosed mechanism was `.todo/288`'s per-site
  sequence-dispatch inlining. **That lever LANDED the same day**
  (`.kb/seq-conversion-runtime.md`, the `%seq-to-*` conversion trio): probe
  (a) went 748,091 -> 678,977 raw (-9.2%) — the yield is bounded by the
  engine's sites being spread across ~440 KB of defun bodies whose residual
  scan loops (0.5-0.9 KB/site) stay inline, where the wrapper-catalog-heavy
  modules halved. The follow-up session (2026-08-08, same todo) measured the
  candidate per-OPERATOR callee lever OUT for this module — the engine has
  only ~28 generic-sequence call sites (reverse 4, mapcar 6, position family
  3, find 3, count 4, one or two each of the rest) at 0.1-0.9 KB residual
  each, a ~2% bound — and found the REAL remaining density in the CLOS
  lowering instead, both halves landed the same session: (1) the generic
  dispatchers' inlined no-applicable-method error tail became the shared
  `%no-applicable-method` defun (each synthesized slot accessor carried its
  own condition-construction + class-naming render, 1,721 -> 389 B of
  bytecode per reader on the JVM twin), and (2) the variadic dispatchers'
  `apply` forwarding stopped building-then-unpacking its argument list (the
  ALIGNED apply fast path, `.kb/clos.md`; 230 of the probe's 259 method-call
  branches carried the ceremony). Probe (a): 678,977 -> 585,940 raw
  (-13.7% on top; cumulative 748,091 -> 585,940, **-21.7%**), JVM twin
  1,120,321 -> 843,568 class bytes (-24.7%). The residual per-OPERATOR
  callee idea stays recorded in `.kb/seq-conversion-runtime.md`'s
  re-evaluation trigger for a module whose sites are denser than this one's.
  An opt-in engine
  subset in the todo-296 pattern was built, parity-pinned (a five-feature
  probe was 135,476 B raw / 43,893 gzip against the real engine's
  770,201 / 183,182, zero-reference 542 B, no EH mode — the numbers stand as
  the measured cost of a subset engine) and then REJECTED by user decision
  the same day; the record is in `.todo/297`. Lever 5 (compile-time lowering
  of literal regexes so no runtime builder ships) stays un-taken: identical
  semantics cannot be promised beyond a pinned subset, and the
  one-dynamic-regex cliff brings the whole engine back silently.

**A finding these probes surfaced, distinct from size:** running the map's
probe sequences exposed a compile-path correctness hole — a `return-from`
crossing a lambda boundary skips the special-binding restore, which corrupts
cl-ppcre's own scanners (a zero-register scan after a failing register-regex
loop returns stale `*reg-starts*`; interpreter correct, JVM + both wasm-GC
wrong). Reproducer, mechanism and the reason `ClPpcreE2eTest` still passes (case
order) are recorded in `.todo/192` (fourth hole). Until it is fixed, the
interpreter is the only backend that runs the real engine's scan SEQUENCES per
the standard.

## JVM

The counterpart post-pass is `am.ik.jvm.JvmClassShaker`, run at the end of `JvmLispCompiler.compile`. It parses the finished class, builds the call graph from the `invoke*` constant-pool immediates, keeps methods reachable from `main` (plus `_apply` as an extra root when the program uses `java:` interop — the embedded bridge looks `_apply` up REFLECTIVELY, an edge bytecode cannot show), drops unreachable methods and any static field only they referenced, and **compacts the constant pool**, rewriting every CP index immediate in the surviving bytecode in place (sizes never change: u2 stays u2, an `ldc` u1 index only shrinks because compaction preserves order — so exception-table pcs and switch padding stay valid; no method renumbering is needed since JVM methods are referenced by name). Dispatch methods keep eval/funcall/`#'` targets alive exactly as on WASM. The shaker throws on anything it does not recognize (unknown opcode/constant tag, any attribute other than a single `Code` per method) rather than emit a corrupt class; `fact` drops ~46 KB -> ~4.6 KB.

Tests: `JvmClassShakerTest` (structural + behavior, incl. the `_apply` root) and `JvmClassShakerCorpusTest` (compiles the whole `ci-spec.yaml` corpus with `--optimize`, asserts shrink + identical run output — the decoder-completeness guard, like `WasmTreeShakerCorpusTest`). Limitations (README "Optimize").

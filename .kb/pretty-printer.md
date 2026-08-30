# The printer entry points and the pretty-printer subset

**Invariant: every CL printer-control variable EXISTS and holds the value the printer
actually behaves as, and the pretty-printing operators produce the text a wide enough
line holds -- identically on the interpreter, the JVM and both WASM GC backends.** What
they do NOT do is change the LAYOUT: no rontolisp stream carries a column, so nothing
wraps. The variables that change the TEXT when bound are `*print-escape*` /
`*print-readably*` (which of the two conversions runs), `*print-pretty*` (the mandatory
line break) and `*print-case*` (the section below).

Pinned by `LispEvaluatorTest.evalWriteAndPprintDispatch` /
`evalPprintLogicalBlock`, the `esrap-enablement-language-group` ci-spec case (all four
backends end to end) and `EsrapE2eTest`, whose parse-error report is the real consumer.

Landed with `.todo/248` (esrap), which is also the only library in the loadable set that
uses any of it.

## What is real

- **`write`** -- the printer entry point, a `LispPreludeLibrary` defun. CL defines its
  keywords as BINDINGS of the printer control variables around one print, and that is
  literally the expansion: `(let ((*print-escape* escape) ...) (write-string (if ... )))`.
  Only `:escape` / `:readably` change the text (they pick between `prin1-to-string` and
  `princ-to-string`, the two conversions every backend has); the others are inert because
  the variable they bind is inert. **`write-to-string` did NOT gain the same keywords**:
  it is a per-backend primitive with its own compiler case, and the
  `with-output-to-string` the obvious lowering would want is exactly what must not appear
  in a shared source -- it flips the WASM exception-handling gate (see `.kb/format.md` on
  `~/name/`). Spell it `(with-output-to-string (s) (write x :stream s ...))`;
  `.todo/041` owns closing the gap.
- **`pprint`** -- a fresh line then `write` with `:escape t :pretty t`, returning no
  values.
- **The pprint DISPATCH tables** -- `copy-pprint-dispatch` / `set-pprint-dispatch` /
  `pprint-dispatch` over a real entry list with real `typep` matching and real priority
  ordering. A table is a one-element LIST holding its entries, so `set-pprint-dispatch`
  can mutate a table it was handed (`rplaca`) -- which is the whole point of the
  `(copy-pprint-dispatch)` + `set-pprint-dispatch` idiom esrap builds its result printer
  with. `pprint-dispatch` answers `#'%pprint-dispatch-default` (a `(stream object)`
  `write`) and `nil` when nothing matches, as CL specifies.
- **`pprint-logical-block`** -- a `LispMacroExpander` lowering (`expandPprintLogicalBlock`,
  in `CL_MACROS`, wired into the evaluator + both compilers + `FreeVarAnalyzer`): writes
  the prefix, runs the body, writes the suffix. CL's non-list rule is honored -- an ATOM
  is printed with `write` and the body is skipped -- which is what makes the macro safe to
  wrap around a value that may or may not be a list.
- **`pprint-newline` `:mandatory`** -- a real line break (gated on `*print-pretty*`), and
  so is the format directive `~:@_`.
- **`~<...~>` / `~<...~:>`** -- see `.kb/format.md`. The SECTION rules are real (a
  justification's `~;` segments consume arguments in turn; a logical block's first section
  is the prefix, its last the suffix when there are three, and a block without `@` takes
  ONE argument -- a list -- as its whole argument list).

## `*print-case*`: one shared renderer, gated on the program naming the variable

Landed 2026-08-15 (`.todo/041`; the consumer is rove's `(let ((*print-case* :downcase))
(princ-to-string name))`, `.todo/372` row 12). Binding the variable to `:downcase` or
`:capitalize` converts the case of every SYMBOL the printer spells -- `princ` / `prin1` /
`print` / `princ-to-string` / `prin1-to-string` / `write-to-string` / `write` and the
`~A` / `~S` directives, which lower to the two conversions -- on all four backends, with
text identical to SBCL 2.2.9 (`LispEvaluatorTest.evalPrintCase`,
`JvmLispCompilerTest.compileAndRunPrintCase`,
`WasmLispCompilerIntegrationTest.printCase` + `printCaseOnTheComponentPath`, the
`print-case` ci-spec case).

- **One implementation, not four.** `%print-cased` is a `LispPreludeLibrary` defun (with
  `%print-case-fold` beside it), so the interpreter and the three compiled backends run
  the SAME recursive renderer -- the alternative, a case pass inside each backend's
  symbol-spelling arm (two of them hand-emitted bytecode), is three implementations that
  have to be kept identical. It walks the VALUE rather than the rendered text because
  only symbol spellings are cased: a string element keeps its own characters
  (`(a "Str")` under `:downcase`), and a character prints as itself.
- **The gate is "the program MENTIONS `*print-case*`"** (`LispMacroExpander.usesPrintCase`)
  -- the same scan that gives the variable its `defvar` (`injectMvSpillGlobal`), roots the
  prelude splice (`LispPreludeLibrary.referencedBySurfaceForm`, `LibraryDefunPruner`) and
  flips `Ctx.printCase` in both compilers. A program that never names it is
  BYTE-IDENTICAL to a pre-`*print-case*` build (checked over `size-report/programs` and
  `examples/console`). The interpreter gates on the CURRENT VALUE instead (it has no
  whole-program pass to run the scan in); the two agree because `%print-cased` re-reads
  the variable itself and takes the raw conversion under `:upcase`.
- **It sits UNDER the `print-object` route, not beside it** -- the same
  `expandPrintObjectHook` seam rewrites both, and `%print-cased` is what the route's
  fallback (and, with no method defined, the operator itself) becomes. A program with
  both gets the method first and the case only where no method applies.
  `%print-cased`'s own leaves are the RAW (`%princ-to-string` / `%prin1-to-string`)
  conversions, which is what terminates the recursion.
- **The pure-builtin fold stands down for `princ-to-string`/`prin1-to-string`** in such a
  program (`PureBuiltinFolder.shadowedOperators`): `nil` and `t` render as SYMBOLS, so
  `(princ-to-string nil)` is `"nil"` under `:downcase` and no longer a compile-time
  constant.
- **`:capitalize` is not `string-capitalize`.** CLHS 22.1.3.3 converts only the UPPERCASE
  characters, so a word's first character is kept AS IT STANDS (never upcased) and the
  rest of the word is downcased: `foo-BAR` prints `foo-Bar` where `string-capitalize`
  answers `Foo-Bar`. A word is a run of alphanumerics (`*FOO*` -> `*Foo*`,
  `A1B2-C3` -> `A1b2-C3`). `:downcase` IS `string-downcase` (`char-downcase` leaves a
  lower-case character alone).
- **Known gap, and the re-evaluation trigger:** a symbol nested in a STRUCTURE, a CLOS
  instance, a hash table or an array of rank != 1 (or a packed float vector) keeps its
  stored spelling -- the walk covers symbols, conses and general rank-1 vectors and
  delegates those containers to the raw conversion, whose rendering is a runtime form of
  its own (SBCL prints `#S(pt :x a)`). Re-evaluate when a container's rendering becomes
  reachable from Lisp: the walk gains a branch, nothing else moves. `%print-object-str`'s
  walk (`.kb/clos.md`, todo-437) carries the SAME guard and the same gap; the two are
  never both live in one program -- a program with a print-object route walks there and
  hands `%print-cased` leaves only -- so they have to be read together to stay in step. The same gate would carry `write`'s `:case` keyword and
  `write-to-string`'s keyword set (below), which are deliberately still absent: adding
  `:case` to the prelude `write` would make every `write` user MENTION `*print-case*` and
  so pull the renderer into modules that never bind it.

## A cyclic value prints finitely instead of overflowing the stack

**Invariant (todo 584 for instances, todo 585 for conses and arrays, 2026-08-30): no
default renderer runs without bound on a cycle.** One shared mechanism
(`RenderCycleGuard`), two disciplines:

- **The rendering path + depth cap.** An instance, a cons chain or an array
  (general OR packed -- a packed one cannot cycle but opens the same frame, so the cap
  truncates at the same frame on every backend) opens ONE render frame when its
  rendering begins. A value already on the current path -- a car reaching back to a
  list still being rendered, an instance graph's parent/children pair, a vector holding
  itself -- prints as **`#`**, CL's `*print-level*` cutoff marker, in both escape
  modes; so does the frame that would open past **256** frames
  (`RenderCycleGuard.MAX_RENDER_DEPTH`), which bounds the render stack for a deep
  FINITE nest too. The check is IDENTITY along the current path, not equality and not
  "rendered before": the same value reachable twice on a finite path still renders
  twice, so every finite rendering under 256 frames is byte-identical to what it was.
- **Floyd over the cdr chain.** A chain is walked ITERATIVELY (todo 585's measurement:
  a cdr cycle was an OutOfMemoryError / an unbounded streamed write, NOT the
  StackOverflowError the todo predicted -- only the car and vector cycles overflowed),
  so the path guard alone cannot see it. The chain's cycle is detected up front
  (constant space, one extra traversal), and the SECOND arrival at the cycle-start cell
  prints as the improper tail **`" . #"`**: `(1 . #)` for `(setf (cdr x) x)`,
  `(1 2 3 . #)` for a tail cycle back into the middle -- every element exactly once,
  then the marker.

Four implementations, one behavior, pinned together:

- interpreter: `RenderCycleGuard` (a `ThreadLocal` path array) entered by
  `LispInstance.render`, `LispCons.render` (+ its `cycleStart` Floyd scan),
  `LispArray.render`, `LispFloatArray`/`LispIntVector.print`;
- JVM backend: the `_renderPath`/`_renderDepth` statics, declared in EVERY class (the
  cons renderer is unconditional), shared by the emitted
  `_instToString`/`_instToDisplayString`, `_consToString`/`_consToDisplayString`
  (`JvmRuntimeBuilder.emitRenderGuardEnter`/`ExitAndReturn` + the Floyd prewalk in
  `buildConsToStringBody`) and `_arrayToString`/`_arrayToDisplayString`;
- both WASM backends: two module globals appended after the hash/equalp recursion
  counters, now unconditional, shared by `emitPrintInstance`, the cons arm
  (`WasmRuntimeBuilder.emitPrintConsList`, one method for both escape modes) and the
  array arm of both printers;
- the two Lisp-level WALKS carry the guard's Lisp twin, since a routed program never
  reaches the raw cons arm: `%print-object-str`'s `%pos-walk` and `%print-cased`'s
  `%pc-walk` thread the path and depth through themselves as arguments and pre-scan the
  chain with Floyd (`%pos-chain-stop` / `%pc-chain-stop`) -- same text, byte for byte.
  Their path is SEPARATE from the raw renderers' (a cycle threading through an
  instance's raw-rendered slots restarts the count at the instance), so only a mixed
  nest past 256 frames can render differently routed vs unrouted -- bounded either way.

The guard is unconditional, so every artifact pays for it (measured 2026-08-30 against
the pre-585 build): `(print (+ 1 2))` +430 B of `.class` and +17 B of `.wasm` (the two
globals alone -- the folded module carries no printer); `(print (list 1 2))` +430 B of
`.class` and +405 B of `.wasm`; the todo-584 instance probe +362 B of `.class` and
+395 B of `.wasm` on top of what it already paid.
Pinned by `LispEvaluatorTest.evalPrintOfACyclicConsIsFinite` (+ the depth-cap,
print-object-route and print-case twins),
`JvmLispCompilerTest.compileAndRunPrintOfACyclicConsIsFinite` (+ the walks twin),
`WasmLispCompilerIntegrationTest.printOfACyclicConsIsFinite` (+ the component twin),
their todo-584 instance siblings, and the `print-cyclic-instance-graph` /
`print-cyclic-cons` ci-spec cases (the latter runs under the print-object route the
concatenated program turns on, so it pins the walk too).

The guard sits UNDER the `print-object` route: a routed instance reaches the raw
fallback and the guard with it, and a `print-object` method that prints only what it
should (geom's, `.kb/geom.md`) never reaches the guard at all. `*print-circle*` proper
(`#1=`/`#1#` labels, honoring the variable) remains unimplemented -- the finite `#`
cutoff is deliberately label-free, consistent between a data cycle and the depth cap.

## What a stream with no column cannot do, and the re-evaluation trigger

**Every conditional line break is a no-op** -- `pprint-newline` with `:linear` / `:fill` /
`:miser`, and the format directives `~_` / `~:_` / `~@_`; so is `pprint-indent`,
`pprint-tab` and `~i`. Deciding one needs the stream's current column, and a rontolisp
stream is an opaque integer handle with no column (`.kb/standard-output-redirect.md`);
`format`'s own `~&`/`~t` only approximate it by scanning the string built SO FAR, which a
logical block cannot do because the block is not the whole output. Consequently
`*print-right-margin*`, `*print-miser-width*` and `*print-lines*` are accepted and
ignored, and a justification never pads to `:mincol`.

**Re-evaluate when a stream gains a column.** That is one field on the stream object plus
a write-through update in every primitive that writes; with it, `pprint-newline` and the
`~_` family become one shared "does the rest fit before the margin" test and every
variable above starts working. Nothing in the loadable library set has needed it: esrap's
report is designed to read the same unwrapped, which is why its expected text in
`EsrapE2eTest` is byte-identical to SBCL's apart from character NAMES (below).

**The ordinary printing operators do NOT consult `*print-pprint-dispatch*`.** `princ` /
`prin1` / `print` / `~A` / `~S` are a per-backend primitive on the hottest path, and the
one hook above them -- `%print-object-str`, the `print-object` seam (`.kb/clos.md`) -- is
gated on the program defining a method (it DOES reach a value nested inside a printed
list or vector since todo-437, but it is not this table). A dispatch table entry
therefore fires only where the program calls the entry function itself, which is what
esrap does
(`(funcall (pprint-dispatch x) stream x)` under a rebound table). Re-evaluate together
with the column: both want the same seam.

**`char-name` answers nil for a graphic character**, which is CL. SBCL additionally
returns the Unicode NAME (`DIGIT_ZERO` for `#\0`), an extension; that is the only
difference between esrap's parse-error report here and on SBCL.

## The printer-control variables

`LispMacroExpander.PRINTER_MODE_VARS` is the single table: name -> global value. The
interpreter seeds it in `Environment.createGlobal` plus `LispEvaluator`'s special-variable
set (they are BOUND, not merely read, and only a proclaimed-special name gets a dynamic
binding); the compile paths get a top-level `(defvar name value)` from
`injectMvSpillGlobal` for each one the program MENTIONS, which runs after
`expandTopLevelDefinitions` so a reference the expansion itself created is in view.

| variable | value | honored? |
| --- | --- | --- |
| `*print-escape*` | `t` | yes -- picks prin1 vs princ, and the `print-object` route binds it |
| `*print-readably*` | `nil` | yes -- forces escaping |
| `*print-pretty*` | `t` | yes -- gates the MANDATORY line break |
| `*print-circle*` | `nil` | no labels -- but every default renderer carries a cycle guard (section below): a cycle prints finitely as `#` / `" . #"` |
| `*print-right-margin*` / `*print-miser-width*` / `*print-lines*` | `nil` | no (no column) |
| `*print-length*` / `*print-level*` | `nil` | the value IS the behavior (no truncation) |
| `*print-base*` | `10` | the value IS the behavior |
| `*print-radix*` | `nil` | the value IS the behavior |
| `*print-case*` | `:upcase` | yes -- `:downcase`/`:capitalize` convert every symbol spelling (below) |
| `*print-array*` / `*print-gensym*` | `t` | the value IS the behavior |
| `*print-pprint-dispatch*` | a fresh empty table | entries and lookup, but see above |

Every default is what the printer ACTUALLY does, so a program that only READS one sees
the truth; binding one to a non-default value is what has no effect. `*print-level*` /
`*print-length*` are not decoration -- esrap's `print-object` on a parse result binds
both, so they have to exist on the compile paths or the module does not compile.

The four remaining standard STREAM variables (`*trace-output*`, `*debug-io*`,
`*query-io*`, `*terminal-io*`) ride the same table with the `t` designator
`*standard-output*` already holds. esrap's rule tracing formats to `*trace-output*` from
inside a closure, which is what forced them: a captured free variable must be a declared
global.

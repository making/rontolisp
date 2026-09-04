# The printer entry points and the pretty-printer subset

**Invariant: every CL printer-control variable EXISTS and holds the value the printer
actually behaves as, and the pretty-printing operators produce the text a wide enough line
holds -- identically on the interpreter, JVM and both WASM GC backends.** None change
LAYOUT: no rontolisp stream has a column, nothing wraps. Text-changing:
`*print-escape*`/`*print-readably*` (which conversion runs), `*print-pretty*` (mandatory
break), and the seven the `%print-cased` walk honors -- `*print-case*`, `*print-length*`,
`*print-level*`, `*print-gensym*`, `*print-base*`, `*print-radix*`, `*package*`. esrap is
the only loadable library using any of it. Pins:
`LispEvaluatorTest.evalWriteAndPprintDispatch` / `evalPprintLogicalBlock`, ci-spec
`esrap-enablement-language-group`, `EsrapE2eTest`.

## What is real

- `write` (`LispPreludeLibrary` defun): its keywords are BINDINGS of the control variables
  around one print, full CL set. `:escape`/`:readably` pick `prin1-to-string` vs
  `princ-to-string`; `:case :length :level :gensym :base :radix` -> `%print-cased`;
  `:pretty :circle :array` + the three widths inert.
- `write-to-string`: same keywords, as a Pass-2 `let` lowering
  (`LispMacroExpander.expandWriteToStringKeywords`), `:escape`/`:readably` substituting the
  escape-picking conditional for the one-argument primitive (always `prin1`). **No
  `with-output-to-string`** -- it flips the WASM exception-handling gate (`.kb/format.md`,
  `~/name/`). Wired into `LispEvaluator.evalConsRareOperator`, the first-class wrapper and
  both `ExprCompiler`s' `write-to-string` case. **Trap:** `#'write-to-string` as a VALUE is
  the one-argument `BuiltinFunctionWrappers` defun, so
  `(apply #'write-to-string (list x :length 1))` silently ignores keywords there.
- `pprint`: fresh line, `write` with `:escape t :pretty t`, no values.
- `copy-pprint-dispatch` / `set-pprint-dispatch` / `pprint-dispatch`: real entries, `typep`
  matching, priority order. A table is a one-element LIST of entries so
  `set-pprint-dispatch` can `rplaca` one handed to it (esrap's idiom); `pprint-dispatch` ->
  `#'%pprint-dispatch-default` (a `(stream object)` `write`), else nil.
- `pprint-logical-block`: `LispMacroExpander.expandPprintLogicalBlock` in `CL_MACROS`,
  wired into evaluator + both compilers + `FreeVarAnalyzer`; prefix/body/suffix, an ATOM
  prints with `write` and skips the body.
- `pprint-newline :mandatory` and `~:@_`: real breaks, gated on `*print-pretty*`.
- `~<...~>` / `~<...~:>` (`.kb/format.md`): SECTION rules real -- a justification's `~;`
  segments consume arguments in turn; a logical block's first section is the prefix, its
  last the suffix when there are three; without `@` it takes ONE list argument as its whole
  argument list.

## One shared renderer: `%print-cased`

`LispPreludeLibrary` defun (`%print-case-fold` beside it): interpreter and all three
compiled backends run the SAME recursive renderer. It walks the VALUE, not rendered text --
only symbol spellings are cased (`(a "Str")` keeps the string's characters); leaves are the
RAW `%princ-to-string`/`%prin1-to-string`, taken directly by the "every default"
(`:upcase`/nil/nil/t/10/nil) fast path, so a routed program is byte-identical to an
unrouted one. Covers `princ` `prin1` `print` `princ-to-string` `prin1-to-string`
`write-to-string` `write` (+ `%princ-piece`/`%prin1-piece`), `~A`/`~S`. Text identical to
SBCL 2.2.9 on four backends.

- Gate = the program MENTIONS a control variable (`LispMacroExpander.usesPrintControls`,
  `PRINT_CONTROL_VARS`, six names; `usesPrintCase` gone). Same scan gives the `defvar`
  (`injectMvSpillGlobal`), roots the prelude splice
  (`LispPreludeLibrary.referencedBySurfaceForm`, `LibraryDefunPruner`), flips
  `Ctx.printControls`; naming none => BYTE-IDENTICAL output (`size-report/programs`,
  `examples/console`). Interpreter gates on the CURRENT VALUE instead
  (`LispEvaluator.printControlsInEffect`).
- It also flips for every `write` user (prelude `write` binds all fifteen variables): the
  compilers scan the SPLICED program (`CompileFrontend` splices before
  `JvmLispCompiler`/`WasmLispCompiler` scan), so `LispPreludeLibrary.process`'s selection
  fixpoint asks `referencedBySurfaceForm` for `%print-cased` over each PULLED entry's forms
  too -- the only entry that does. Large `.wasm` growth then (Unicode case fold ~9 KB
  alone); nothing in `size-report/programs`/`examples/console` calls `write`.
- Sits UNDER the `print-object` route (one `expandPrintObjectHook` seam rewrites both):
  `%print-cased` is the route's fallback and, with no method, the operator itself.
- Pure-builtin fold stands down for `princ-to-string`/`prin1-to-string`/`%princ-piece`/
  `%prin1-piece` (`PureBuiltinFolder.shadowedOperators`): `nil`/`t` are SYMBOLS, so
  `(princ-to-string nil)` is `"nil"` under `:downcase`, not a constant.
- `:capitalize` != `string-capitalize` (CLHS 22.1.3.3 converts only UPPERCASE): first
  character kept AS IT STANDS, rest downcased -- `foo-BAR` -> `foo-Bar`; a word is a run of
  alphanumerics (`*FOO*` -> `*Foo*`, `A1B2-C3` -> `A1b2-C3`). `:downcase` IS
  `string-downcase`.
- Heavy leaves gate on a variable being NAMED: `%pc-fold`/`%pc-radixed` lower to
  `%print-case-fold`/`%print-radixed` only then (`Ctx.printControlVariables` =
  `mentionsPrintControlVariable`, skipping the renderer's own defuns, decided BEFORE
  `expandTopLevelDefinitions` injects the defvars naming them all), else bare text / raw
  conversion; prelude and tree-shaker follow the same fact.

Semantics, as SBCL prints them:

- `*print-length*` n: first n elements then ` ...`, only when the unprinted rest is a CONS
  -- `(1 2 . 3)` under 2 keeps its dotted tail, `(1 2 3 4 . 5)` under 3 -> `(1 2 3 ...)`;
  0 -> `(...)`/`#(...)`.
- `*print-level*` n: list/vector AT depth n prints `#` (top level depth 0; atoms never
  truncate). `'x`/`#'x` is TRANSPARENT to the level (`(a '(b))` under 1 is `(A '#)`,
  `''(b)` is `'(B)`) but still opens a cycle-guard frame, so the walk carries TWO counters,
  `%pc-depth` (256-frame cap) and `%pc-lvl` (level).
- `*print-gensym*` nil strips `#:` under `prin1`. `*print-base*`: integers and ratios in
  upper-case digits (`FF`, `1/FF`; floats keep their text). `*print-radix*` t: `#b`/`#o`/
  `#x` for 2/8/16, trailing `.` for a base-10 INTEGER, `#<base>r` otherwise (`#36r73`,
  `#10r1/2`).
- Both walks carry length+level in lockstep -- `%pc-walk` (`LispPreludeLibrary`) and
  generated `%pos-walk` (`LispMacroExpander.PRINT_OBJECT_CONS_ARM` /
  `PRINT_OBJECT_VECTOR_ARM`), since a routed program walks `%pos-walk` and hands
  `%print-cased` leaves only; gensym and base/radix are leaf facts in `%pc-walk` alone
  (`%print-radixed`, `%print-in-base`). `%pos-walk` reads both variables UNCONDITIONALLY --
  one cons arm to keep in step, not two.

**Known gap (all seven):** a symbol inside a STRUCTURE, CLOS instance, hash table, array of
rank != 1 or packed float vector is neither cased nor truncated -- the walk covers symbols,
conses and general rank-1 vectors, delegating the rest to the raw conversion (SBCL prints
`#S(pt :x a)`). Re-evaluate when such a rendering becomes reachable from Lisp: the walk
gains a branch. `%print-object-str`'s walk (`.kb/clos.md`) has the SAME guard and gap and is
never live in the same program -- read them together. Also inert: `*print-array*` nil (SBCL
`#<(SIMPLE-VECTOR 3) {addr}>`), `*print-circle*` t (`#1=` labels), compile-path
`#'write-to-string`, `~@W` (binds neither variable, `.kb/format.md`).

Tests: `LispEvaluatorTest.evalPrintCase` / `evalPrintLengthLevelGensymBaseAndRadix` /
`evalWriteAndWriteToStringKeywords`; `JvmLispCompilerTest.compileAndRunPrintCase` /
`compileAndRunPrintControls`; `WasmLispCompilerIntegrationTest.printCase` +
`printCaseOnTheComponentPath`, `printControls` + `printControlsOnTheComponentPath`; ci-spec
`print-case`, `print-length-level-gensym-base-radix`.

## Quote/function abbreviation and `|...|` symbol escaping

**Invariant:** a two-element list headed by `quote`/`function` prints `'x`/`#'x` (CLHS
22.1.3.7, unconditional); three or more elements, or an improper tail, print in full. A
symbol whose name is not upcase-invariant, or holds a character the reader rejects in a bare
token, prints `|...|`-framed under `prin1` (`*print-escape*` true) with embedded `|`/`\`
doubled; `princ` never escapes (CLHS 22.1.3.3). Four backends identical, differential-tested
against SBCL 2.2.9 (`LispEvaluatorTest`/`LispReaderTest`, `JvmLispCompilerTest`,
`WasmLispCompilerIntegrationTest`, ci-spec `backquote-quoted-splice`).

- The abbreviation applies under BOTH `prin1` and `princ` (ECL behavior; SBCL gates it on
  `*print-pretty*`, which no backend threads into any render call site); abbreviation and
  escaping gate independently.
- Five copies of the shape check: `LispCons.render` (`am.ik.rontolisp.LispCons`, inline
  inside the same `RenderCycleGuard`-entered section a normal list opens, so
  `(setq x (list 'quote x))` still hits the guard); `_consToString`
  (`JvmRuntimeBuilder.buildConsToStringBody`, shared with `_consToDisplayString`);
  `emitPrintConsList` (`WasmRuntimeBuilder`, shared by `FUNC_PRINT_VAL`/`FUNC_PRINC_VAL` =
  Preview 1 and `--component`) -- the compiled two in raw bytecode ahead of their Floyd/loop
  code, through the SAME guard exit; plus `%pc-walk`/`%pos-walk` by hand.
- The escape lives in `LispSymbol.print()`: a keyword's `:`, an uninterned symbol's `#:`
  and a `pkg:`/`pkg::` qualifier stay verbatim -- only the trailing MEMBER text is tested
  and escaped (`LispSymbol.needsEscape`/`escape`). Compiled twins `_symEsc`
  (`JvmRuntimeBuilder.buildSymEscBody`) and `_sym_esc_gc`
  (`WasmStringRuntimeBuilder.buildSymEscGcBody`, `FUNC_SYM_ESC_GC`), called only from
  `_strEsc`'s / `_print_val`'s bare-symbol arm, never the princ arms.
- The lowercase check is ASCII `a`-`z` only on the three compiled renderers, not the
  interpreter's `Character.toUpperCase(char)` fold: the general fold needs
  `WasmCaseFoldRuntimeBuilder`'s compressed `Character.toUpperCase(int)` range table (no
  JVM equivalent) reachable from EVERY symbol-printing program. **Narrow gap:** a symbol
  whose only non-constituent characters are non-ASCII lowercase letters (Latin-1 `à`)
  prints unescaped on all four backends and mis-reads under the default readtable --
  matched everywhere, not a divergence.
- Non-constituent set (space, tab, newline, CR, form feed, `(`, `)`, `'`, `"`, `;`, `,`,
  `` ` ``, `|`, `\`) = `LispLexer.isSymbolChar`'s terminators restricted to ASCII
  whitespace, plus `|` and `\`; enumerated identically in `LispSymbol.isBareConstituent`,
  `buildSymEscBody` (chained-comparison loop) and `SYM_ESC_FORBIDDEN`
  (`WasmStringRuntimeBuilder`) -- change one, change all three.
- **Trap:** `type-of`/`symbol-package` (`LispPreludeLibrary.java`) read a
  `%class-`/`%struct-` tag off `(prin1-to-string designator)` and strip the lowercase
  prefix by substring match; an unqualified tag round-trips as `"|%struct-PT|"` and breaks
  the match. Fixed by prelude helper `%unescaped-symbol-text`
  (`LispNames.UNESCAPED_SYMBOL_TEXT_INTERNAL`), peeling exactly one leading/trailing `|`;
  `print-unreadable-object :type t` (`LispMacroExpander.typeNameOf`) INLINES the identical
  peel, expanding inside the compilers after the prelude splice pre-pass. **Re-evaluate the
  trio together** if the escaping rule changes shape.
- `LispVal.print()` is reused by ~100 tests as an AST-DUMP convenience: large but shallow
  test blast radius.

## The package qualifier follows `*package*` accessibility

**Invariant:** `prin1` (and `print`, `prin1-to-string`, `write-to-string`, `~S`, `write`
with escape) spells a qualifier only when the symbol is NOT accessible in the current
`*package*` (CLHS 22.1.3.3.1): none for the package's own symbol, one inherited through
`:use` as an external (directly or via re-export), or an imported one; `pkg:name`/
`pkg::name` otherwise. `princ`/`~A` never spell one. Four backends identical and equal to
SBCL 2.2.9: `LispEvaluatorTest.evalPrintDropsTheQualifierOfAnAccessibleSymbol`,
`PackageResolverTest#{printsBareFollowsAccessibilityInTheCurrentPackage,symbolPrintTableCarriesTheCorrectionsForTheSymbolsTheProgramSpells}`,
`JvmLispCompilerTest.compileAndRunPrintDropsTheQualifierOfAnAccessibleSymbol`,
`WasmLispCompilerIntegrationTest.printDropsTheQualifierOfAnAccessibleSymbol` + component
twin, ci-spec `symbol-print-accessibility`, `RoveE2eTest`.

- Rides `%print-cased` as the seventh control: its `prin1` symbol leaf goes through
  `%pc-unqualified` (prelude), which parses the qualifier off the canonical `PKG:NAME`
  (keyword `:`, gensym `#:`, `|...|`-escaped member left alone) and asks
  `%symbol-print-bare-p`. Interpreter: LIVE registry (`PackageResolver.printsBare` -- bare
  when home is the current package, else when an unqualified reference to the member
  resolves to this very symbol, `resolveUnqualified` in quoted-data mode). Compile paths:
  `LispMacroExpander.expandSymbolPrintBareP` lowers onto a `SymbolPrintTable` baked from
  the resolver's FINAL registry (`PackageResolver.symbolPrintTable`, in both `Ctx`s beside
  `packageTable`).
- Table = rule + corrections. Rule: home is `(%princ-to-string *package*)`, or the current
  package uses the home and the colon is single -- one row per registered package (upcased
  name -> own qualifier spelling, then those of the packages it uses). Divergences for
  symbols OCCURRING in the program bake per package into `extra` (`:import-from`, re-export
  through an intermediate package, `export` after the definition) and `excluded`
  (`:shadow`, an earlier used package exporting the same name, `unexport`), computed by
  asking `printsBare` for every occurring qualified symbol x every package; a run-time
  intern follows the rule alone.
- Gate: `LispMacroExpander.printsUnderAPackage` -- a top-level `in-package` leaving
  `cl-user` (resolved to `(setq *package* :P)`), or any other mention of `*package*`. Such
  a program routes through `%print-cased` (`usesPrintControls` =
  `mentionsPrintControlVariable`, or `printsUnderAPackage` with the renderer DEFINED in the
  program), keeps its `*package*` assignments and `defvar` (`injectMvSpillGlobal`), bakes
  the table. The prelude pulls the renderer for a package program only when a `prin1`-style
  conversion is in reach from the surface (`reachesPrin1FromTheSurface`: print operators,
  `format`, the signalling operators, the print-object seam, `%prin1-piece`). **Keying the
  route on the renderer being DEFINED matters:** a program the splice passed over must keep
  raw spellings, not call an absent renderer. Interpreter: `printControlsInEffect` is true
  outside pristine `cl-user` (`PackageResolver.currentPackageIsPristineClUser`), the fast
  path asking the same via `%print-package-raw-p` (lowered to `(eq *package* :CL-USER)`
  while `cl-user` is pristine, to `t` with no table); `princ` always takes the raw
  conversion. A program never leaving `cl-user` is byte-identical.
- Two bugs found alongside: a computed `(string x)` on both compile paths went through the
  routed `%princ-piece`, so `(string 'foo)` under `:downcase` answered `"foo"` where SBCL
  and the interpreter answer `"FOO"` -- `strictStringDesignatorForm` now takes raw
  `%princ-to-string`
  (`JvmLispCompilerTest.compileAndRunStringOfASymbolIsNotFoldedByPrintCase` + WASM twin);
  and a `write-to-string` keyword that was a program's ONLY binding of a printer variable
  failed the JVM compile ("dynamically bound here but has no thread-local store") because
  `SpecialVarCollector` never saw the `let` the Pass-2 lowering creates -- it now walks
  `expandWriteToStringKeywords`'s output
  (`compileAndRunWriteToStringKeywordAloneBindsThePrinterVariable`).
- **Known gap:** the printer only DROPS a qualifier, never ADDS one -- a `cl-user` or
  standard symbol printed where it is inaccessible prints bare, not `COMMON-LISP-USER::FOO`
  / `COMMON-LISP:CAR`. `symbol-package`/`type-of` parse the RAW `%prin1-to-string` spelling
  for the same reason the escape peel does. Re-evaluate with a real intern table: the
  question becomes a field read and the baked table unnecessary.

## A cyclic value prints finitely instead of overflowing the stack

**Invariant: no default renderer runs without bound on a cycle.** One mechanism
(`RenderCycleGuard`), two disciplines:

- Render path + depth cap: an instance, cons chain or array (general OR packed -- a packed
  one cannot cycle but opens the same frame, so the cap truncates identically everywhere)
  opens ONE frame. A value already on the current path prints `#` (CL's `*print-level*`
  cutoff marker) in both escape modes, as does a frame opening past **256**
  (`RenderCycleGuard.MAX_RENDER_DEPTH`), which also bounds a deep FINITE nest. The check is
  IDENTITY along the current path -- not equality, not "rendered before" -- so every finite
  rendering under 256 frames is byte-identical to before.
- Floyd over the cdr chain: a cdr cycle is an OutOfMemoryError / unbounded streamed write,
  not a StackOverflowError, so the path guard cannot see it. The chain is walked
  ITERATIVELY with the cycle detected up front (constant space, one extra traversal); the
  SECOND arrival at the cycle-start cell prints as the improper tail `" . #"` -- `(1 . #)`
  for `(setf (cdr x) x)`, `(1 2 3 . #)` for a tail cycle into the middle.

Four implementations: interpreter `RenderCycleGuard` (`ThreadLocal` path array) entered by
`LispInstance.render`, `LispCons.render` (+ its `cycleStart` Floyd scan), `LispArray.render`,
`LispFloatArray`/`LispIntVector.print`; JVM `_renderPath`/`_renderDepth` statics in EVERY
class (the cons renderer is unconditional), shared by `_instToString`/`_instToDisplayString`,
`_consToString`/`_consToDisplayString`
(`JvmRuntimeBuilder.emitRenderGuardEnter`/`ExitAndReturn` + the Floyd prewalk in
`buildConsToStringBody`), `_arrayToString`/`_arrayToDisplayString`; both WASM backends two
module globals after the hash/equalp recursion counters, unconditional, shared by
`emitPrintInstance`, `WasmRuntimeBuilder.emitPrintConsList` (one method for both escape
modes) and the array arm of both printers; and the two Lisp walks, which carry the guard's
Lisp twin since a routed program never reaches the raw cons arm -- `%pos-walk`/`%pc-walk`
thread path and depth as arguments and pre-scan with Floyd
(`%pos-chain-stop`/`%pc-chain-stop`), same text byte for byte, on a path SEPARATE from the
raw renderers', so only a mixed nest past 256 frames renders differently routed vs unrouted.

The guard is unconditional; every artifact pays a small fixed size. Pins:
`LispEvaluatorTest.evalPrintOfACyclicConsIsFinite` (+ depth-cap, print-object-route,
print-case twins), `JvmLispCompilerTest.compileAndRunPrintOfACyclicConsIsFinite` (+ walks
twin), `WasmLispCompilerIntegrationTest.printOfACyclicConsIsFinite` (+ component twin),
their instance siblings, ci-spec `print-cyclic-instance-graph`/`print-cyclic-cons` (the
latter runs under the print-object route, pinning the walk too).

It sits UNDER the `print-object` route: a routed instance reaches the raw fallback and the
guard with it; a method printing only what it should (geom's, `.kb/geom.md`) never reaches
it. `*print-circle*` proper (`#1=`/`#1#` labels) is unimplemented -- the finite `#` cutoff
is deliberately label-free.

## What a stream with no column cannot do

**Every conditional line break is a no-op** -- `pprint-newline` `:linear`/`:fill`/`:miser`,
`~_`/`~:_`/`~@_`, `pprint-indent`, `pprint-tab`, `~i`. Each needs the stream's column; a
rontolisp stream is an opaque integer handle with none (`.kb/standard-output-redirect.md`),
and `format`'s `~&`/`~t` only approximate it by scanning the string built SO FAR, which a
logical block cannot do. So `*print-right-margin*`, `*print-miser-width*` and
`*print-lines*` are accepted and ignored, and a justification never pads to `:mincol`.
**Re-evaluate when a stream gains a column**: one field plus a write-through update in every
writing primitive, and `pprint-newline` and the `~_` family become one shared "does the rest
fit before the margin" test. esrap's report reads the same unwrapped, so its expected text
in `EsrapE2eTest` is byte-identical to SBCL's apart from character NAMES.

**The ordinary printing operators do NOT consult `*print-pprint-dispatch*`.** `princ` /
`prin1` / `print` / `~A` / `~S` are a per-backend primitive on the hottest path; the one hook
above them, `%print-object-str` (the `print-object` seam, `.kb/clos.md`), is gated on the
program defining a method and is not this table. A dispatch entry fires only where the
program calls the entry function itself, as esrap does
(`(funcall (pprint-dispatch x) stream x)` under a rebound table). Re-evaluate with the
column: both want the same seam.

**`char-name` answers nil for a graphic character**, which is CL; SBCL additionally returns
the Unicode NAME (`DIGIT_ZERO` for `#\0`) -- the only difference between esrap's parse-error
report here and on SBCL.

## The printer-control variables

`LispMacroExpander.PRINTER_MODE_VARS` is the single table: name -> global value. The
interpreter seeds it in `Environment.createGlobal` plus `LispEvaluator`'s special-variable
set (they are BOUND, not merely read, and only a proclaimed-special name gets a dynamic
binding). Compile paths get a top-level `(defvar name value)` from `injectMvSpillGlobal` per
variable the program MENTIONS -- run after `expandTopLevelDefinitions` so a reference the
expansion created is in view; "mentions" includes a `write-to-string` keyword binding it
(`LispMacroExpander.mentionsPrinterVariable`), that lowering being Pass 2, after this scan.

| variable | value | honored? |
| --- | --- | --- |
| `*print-escape*` | `t` | yes -- picks prin1 vs princ; the `print-object` route binds it |
| `*print-readably*` | `nil` | yes -- forces escaping |
| `*print-pretty*` | `t` | yes -- gates the MANDATORY line break |
| `*print-circle*` | `nil` | no labels -- the cycle guard prints a cycle finitely as `#`/`" . #"` |
| `*print-right-margin*` / `*print-miser-width*` / `*print-lines*` | `nil` | no (no column) |
| `*print-length*` / `*print-level*` | `nil` | yes -- `(1 2 ...)` / `#` truncation |
| `*print-base*` | `10` | yes -- integers and ratios re-spelled in the base |
| `*print-radix*` | `nil` | yes -- `#x` / `#b` / `#o` / `#Nr` / trailing `.` |
| `*print-case*` | `:upcase` | yes -- `:downcase`/`:capitalize` convert every symbol spelling |
| `*print-gensym*` | `t` | yes -- nil drops the `#:` under prin1 |
| `*print-array*` | `t` | the value IS the behavior |
| `*print-pprint-dispatch*` | a fresh empty table | entries and lookup, but see above |

Every default is what the printer ACTUALLY does, so a program that only READS one sees the
truth; binding an inert one to a non-default value is what has no effect.

The four standard STREAM variables (`*trace-output*`, `*debug-io*`, `*query-io*`,
`*terminal-io*`) ride the same table with the `t` designator `*standard-output*` holds.
esrap's rule tracing formats to `*trace-output*` from inside a closure: a captured free
variable must be a declared global.

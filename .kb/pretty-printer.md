# The printer entry points and the pretty-printer subset

**Invariant: every CL printer-control variable EXISTS and holds the value the printer actually
behaves as, and the pretty-printing operators produce the text a wide enough line holds —
identically on the interpreter, JVM and both WASM GC backends.** None change LAYOUT: no rontolisp
stream has a column, nothing wraps. Text-changing: `*print-escape*`/`*print-readably*` (which
conversion runs), `*print-pretty*` (mandatory break), and the seven the `%print-cased` walk honors
(`*print-case*`, `*print-length*`, `*print-level*`, `*print-gensym*`, `*print-base*`,
`*print-radix*`, `*package*`). esrap is the only loadable library using any of it.

## What is real
- `write` (`LispPreludeLibrary` defun): keywords are BINDINGS of the control variables around one
  print. `:escape`/`:readably` pick `prin1-to-string` vs `princ-to-string`;
  `:case :length :level :gensym :base :radix` -> `%print-cased`; `:pretty :circle :array` + the
  three widths inert.
- `write-to-string`: same keywords as a Pass-2 `let` lowering
  (`LispMacroExpander.expandWriteToStringKeywords`). **No `with-output-to-string`** — it flips the
  WASM exception-handling gate (`.kb/format.md`). Wired into `LispEvaluator.evalConsRareOperator`,
  the first-class wrapper and both `ExprCompiler`s. `#'write-to-string` as a VALUE takes the
  keywords too (2026-09-27): on the compile paths its wrapper hands a runtime tail to the prelude
  `%write-to-string-keyed`, which judges it with the call position's report and binds all fifteen
  variables. That defun names every variable, so splicing it routes the program (`defvar`s +
  renderer, like `write`): it is spliced only for a program spelling the designator somewhere
  other than an exactly-one-argument position (`(funcall #'f x)`, `(mapcar #'f l)` and siblings
  over one list, `(map 'list #'f s)` -- `LispPreludeLibrary.mayPassMoreThanOneArgument`), and the
  wrapper takes the keyed shape exactly where it is spliced (`BuiltinFunctionWrappers
  .HELPER_WRAPPERS`); `(mapcar #'write-to-string ...)` and every
  program without the designator are byte-identical.
- `pprint`: fresh line, `write` with `:escape t :pretty t`, no values.
- `copy-pprint-dispatch` / `set-pprint-dispatch` / `pprint-dispatch`: real entries, `typep`
  matching, priority order. A table is a one-element LIST so `set-pprint-dispatch` can `rplaca` one
  handed to it (esrap's idiom); `pprint-dispatch` -> `#'%pprint-dispatch-default`, else nil.
- `pprint-logical-block`: `LispMacroExpander.expandPprintLogicalBlock` in `CL_MACROS`, wired into
  evaluator + both compilers + `FreeVarAnalyzer`; an ATOM prints with `write` and skips the body.
  The compilers build that call after the prelude splice, so a block whose object may be an atom selects
  `write` and roots it in the pruner (`LispMacroExpander.mayPprintLogicalBlockOfAnAtom`,
  `LispPreludeLibrary.referencedBySurfaceForm`, `SYNTHESIZED_ENTRIES`), and the program pays for the
  renderer below like any `write` user. An object spelled as a list (`nil`, `'(...)`, lisp-namespace's
  `print-object`) expands without the arm and pays nothing. ci-spec
  `pprint-logical-block-of-an-atom-in-a-program-not-naming-write`, standalone because the concatenated
  corpus names `write`.
- `pprint-newline :mandatory` and `~:@_`: real breaks, gated on `*print-pretty*`.
- `~<...~>` / `~<...~:>` (`.kb/format.md`): SECTION rules real.

## One shared renderer: `%print-cased`
`LispPreludeLibrary` defun (`%print-case-fold` beside it): interpreter and all three compiled
backends run the SAME recursive renderer. It walks the VALUE, not rendered text — only symbol
spellings are cased; leaves are the RAW `%princ-to-string`/`%prin1-to-string`, taken directly by
the "every default" (`:upcase`/nil/nil/t/10/nil) fast path, so a routed program is byte-identical
to an unrouted one. Covers `princ` `prin1` `print` `princ-to-string` `prin1-to-string`
`write-to-string` `write` (+ `%princ-piece`/`%prin1-piece`), `~A`/`~S`. Text identical to SBCL
2.2.9 on four backends.

- Gate = the program MENTIONS a control variable (`LispMacroExpander.usesPrintControls`,
  `PRINT_CONTROL_VARS`). The same scan gives the `defvar` (`injectMvSpillGlobal`), roots the
  prelude splice (`LispPreludeLibrary.referencedBySurfaceForm`, `LibraryDefunPruner`) and flips
  `Ctx.printControls`; naming none => BYTE-IDENTICAL output. Interpreter gates on the CURRENT VALUE
  (`LispEvaluator.printControlsInEffect`).
- It also flips for every `write` user (prelude `write` binds all fifteen variables): the compilers
  scan the SPLICED program, so `LispPreludeLibrary.process`'s selection fixpoint asks
  `referencedBySurfaceForm` for `%print-cased` over each PULLED entry's forms too — the only entry
  that does. Large `.wasm` growth then (the Unicode case-fold tables alone).
- Sits UNDER the `print-object` route (one `expandPrintObjectHook` seam rewrites both).
- Pure-builtin fold stands down for `princ-to-string`/`prin1-to-string`/`%princ-piece`/
  `%prin1-piece`: `nil`/`t` are SYMBOLS (`.kb/pure-builtin-fold.md`).
- `:capitalize` != `string-capitalize` (CLHS 22.1.3.3 converts only UPPERCASE): first character AS
  IT STANDS, rest downcased — `foo-BAR` -> `foo-Bar`, `*FOO*` -> `*Foo*`, `A1B2-C3` -> `A1b2-C3`.
  `:downcase` IS `string-downcase`.
- Heavy leaves gate on a variable being NAMED: `%pc-fold`/`%pc-radixed` lower to
  `%print-case-fold`/`%print-radixed` only then (`Ctx.printControlVariables` =
  `mentionsPrintControlVariable`, decided BEFORE `expandTopLevelDefinitions` injects the defvars).
- `*print-length*` n: first n then ` ...`, only when the unprinted rest is a CONS — `(1 2 . 3)`
  under 2 keeps its dotted tail; 0 -> `(...)`/`#(...)`. `*print-level*` n: list/vector AT depth n
  prints `#` (top level 0; atoms never truncate). `'x`/`#'x` is TRANSPARENT to the level but still
  opens a cycle-guard frame, so the walk carries TWO counters, `%pc-depth` (256-frame cap) and
  `%pc-lvl`. `*print-gensym*` nil strips `#:` under prin1. `*print-base*`: integers and ratios in
  upper-case digits (floats keep their text). `*print-radix*` t: `#b`/`#o`/`#x`, trailing `.` for a
  base-10 INTEGER, `#<base>r` otherwise.
- Both walks carry length+level in lockstep — `%pc-walk` and generated `%pos-walk`
  (`LispMacroExpander.PRINT_OBJECT_CONS_ARM`/`PRINT_OBJECT_VECTOR_ARM`); gensym and base/radix are
  leaf facts in `%pc-walk` alone. `%pos-walk` reads both variables UNCONDITIONALLY — one cons arm
  to keep in step, not two.

**Known gap (all seven):** a symbol inside a STRUCTURE, CLOS instance, hash table, array of rank
!= 1 or packed float vector is neither cased nor truncated — the walk covers symbols, conses and
general rank-1 vectors. `%print-object-str`'s walk (`.kb/clos.md`) has the SAME guard and gap and
is never live in the same program — read them together. Also inert: `*print-array*` nil,
`*print-circle*` t, `~@W`.

## Quote/function abbreviation and `|...|` symbol escaping
**Invariant:** a two-element list headed by `quote`/`function` prints `'x`/`#'x` (CLHS 22.1.3.7,
unconditional); three or more elements, or an improper tail, print in full. A symbol whose name is
empty, not upcase-invariant, holds a character the reader rejects in a bare token, or would not read
as a symbol (below) prints `|...|`-framed under `prin1` with embedded `|`/`\` doubled; `princ` never
escapes. Abbreviation applies under BOTH
`prin1` and `princ` (ECL behavior); the two gate independently.

- Five copies of the shape check: `LispCons.render` (inline inside the same `RenderCycleGuard`
  section a normal list opens), `_consToString` (`JvmRuntimeBuilder.buildConsToStringBody`, shared
  with `_consToDisplayString`), `emitPrintConsList` (`WasmRuntimeBuilder`, shared by
  `FUNC_PRINT_VAL`/`FUNC_PRINC_VAL`), plus `%pc-walk`/`%pos-walk` by hand.
- The escape lives in `LispSymbol.print()` (`needsEscape`/`escape`): a keyword's `:`, an uninterned
  symbol's `#:` and a `pkg:`/`pkg::` qualifier stay verbatim — only the trailing MEMBER text is
  tested. Compiled twins `_symEsc` (`JvmRuntimeBuilder.buildSymEscBody`) and `_sym_esc_gc`
  (`WasmStringRuntimeBuilder.buildSymEscGcBody`, `FUNC_SYM_ESC_GC`), called only from the
  bare-symbol arm, never the princ arms.
- The lowercase check is ASCII `a`-`z` only on the three compiled renderers, not the interpreter's
  `Character.toUpperCase(char)` fold (the general fold would need `WasmCaseFoldRuntimeBuilder`'s
  range table reachable from EVERY symbol-printing program). **Narrow gap:** a symbol whose only
  non-constituent characters are non-ASCII lowercase letters prints unescaped on all four backends
  and mis-reads — matched everywhere, not a divergence.
- **A name a bare token would not read as a symbol** (`LispSymbol.readsAsNoSymbol`, inline in the two
  compiled twins): dots alone, or CLHS 2.3.1.1's potential number -- digits, signs, `/`, `.`, `^`, `_`
  and letters no two of which are adjacent, a digit, beginning with neither a letter nor `/`, not ending
  in a sign. `|10|`, `|1E5|`, `|1/2/3|`, `|...|`; `1+`, `E1`, `1AB`, `%1` stay bare. 45 names agree with
  SBCL 2.2.9 on all four (ci-spec `prin1-escapes-a-name-that-reads-as-no-symbol`), and the rule costs
  the bytes of those two functions in every program printing a symbol. Limit: the digits are decimal,
  where SBCL also escapes `FF` under `*print-base*` 16 (the twins read no variable). An emitted reader
  has no `|...|` syntax, so `(read-from-string (prin1-to-string (intern "10")))` is a symbol named with
  its bars there, where it was the number 10 before.
- The empty name: until 2026-11-05 the JVM twin answered it verbatim (`#:`, an empty line) and the wasm
  `(intern "")` built an empty byte array that `_print_val`'s unguarded byte 0 test trapped on;
  `buildInternSym` now keeps one byte behind the zero length. On the JVM a bare `""` remains a symbol
  no first-character test survives (about 30 `charAt(0)` sites): `(symbolp (intern ""))` throws
  `StringIndexOutOfBoundsException`, a `simple-error`, measured 2026-11-05 (`.todo/i49`). So ci-spec spells only
  `(make-symbol "")` and `(intern "" :keyword)`.
- `%print-case-fold` converts only what stands ahead of the first `|`: the escaped member is never
  recased (`|Foo|` under `:downcase`, `pkg::|1E5|`). Until 2026-11-05 it folded the whole text.
- Non-constituent set (space, tab, newline, CR, form feed, `(`, `)`, `'`, `"`, `;`, `,`, `` ` ``,
  `|`, `\`) is enumerated identically in `LispSymbol.isBareConstituent`, `buildSymEscBody` and
  `SYM_ESC_FORBIDDEN` — change one, change all three.
- **Trap:** `type-of`/`%symbol-home` read a `%class-`/`%struct-` tag off
  `(prin1-to-string designator)` by substring match; an unqualified tag round-trips as
  `"|%struct-PT|"` and breaks it. Fixed by prelude `%unescaped-symbol-text`
  (`LispNames.UNESCAPED_SYMBOL_TEXT_INTERNAL`), peeling exactly one leading/trailing `|`;
  `print-unreadable-object :type t` (`LispMacroExpander.typeNameOf`) INLINES the identical peel.
  **Re-evaluate the trio together** if the escaping rule changes shape.
- `LispVal.print()` is reused by ~100 tests as an AST-dump convenience.

## The package qualifier follows `*package*` accessibility
**Invariant:** `prin1` (and `print`, `prin1-to-string`, `write-to-string`, `~S`, escaping `write`)
spells a qualifier only when the symbol is NOT accessible in the current `*package*` (CLHS
22.1.3.3.1); `princ`/`~A` never spell one.

- **Interpreter trap (2026-11-01)**: the print-object renderer is ONE generated defun pair
  (`ensurePrintObjectRuntimeLoaded`), so a print in progress -- expanded for the `%print-cased`
  leaf because the package is not the pristine `cl-user` -- used to run the RAW leaf a print
  nested in its argument under a `*package*` rebound to `cl-user` had regenerated, and spelled
  every symbol qualified (contextl's `map-symbol` does exactly that with
  `with-standard-io-syntax`). Once generated for a control, the pair now keeps the
  `%print-cased` leaf, which takes the raw conversion itself under the defaults. ci-spec
  `print-object-nested`.
- Rides `%print-cased` as the seventh control: the `prin1` symbol leaf goes through
  `%pc-unqualified`, which parses the qualifier off the canonical `PKG:NAME` and asks
  `%symbol-print-bare-p`. Interpreter: LIVE registry (`PackageResolver.printsBare`,
  `resolveUnqualified` in quoted-data mode). Compile paths:
  `LispMacroExpander.expandSymbolPrintBareP` lowers onto a `SymbolPrintTable` baked from the
  resolver's FINAL registry (`PackageResolver.symbolPrintTable`, in both `Ctx`s beside
  `packageTable`).
- Table = rule + corrections. Rule: home is `(%princ-to-string *package*)`, or the current package
  uses the home and the colon is single. Divergences for symbols OCCURRING in the program bake per
  package into `extra` (`:import-from`, re-export, late `export`) and `excluded` (`:shadow`, an
  earlier used package exporting the same name, `unexport`); a run-time intern follows the rule
  alone. A bare spelling has its own lists, below.
- Gate: `LispMacroExpander.printsUnderAPackage` — a top-level `in-package` leaving `cl-user`, or
  any other mention of `*package*`. The prelude pulls the renderer for such a program only when a
  `prin1`-style conversion is in reach from the surface (`reachesPrin1FromTheSurface`). **Keying
  the route on the renderer being DEFINED matters:** a program the splice passed over must keep raw
  spellings, not call an absent renderer. Interpreter: `printControlsInEffect` is true outside
  pristine `cl-user` (`PackageResolver.currentPackageIsPristineClUser`); `princ` always takes the
  raw conversion. A program never leaving `cl-user` is byte-identical.
- Two bugs found alongside: a computed `(string x)` went through the routed `%princ-piece` on both
  compile paths, so `(string 'foo)` under `:downcase` answered `"foo"` —
  `strictStringDesignatorForm` now takes raw `%princ-to-string`; and a `write-to-string` keyword
  that was a program's ONLY binding of a printer variable failed the JVM compile because
  `SpecialVarCollector` never saw the Pass-2 `let`, which it now walks.
- **An instance's type name is a symbol under `prin1`** (2026-11-04, `%pc-instance-text`, the walk's
  instance leaf): `#<K>`, `#S(K :A 1)` in `K`'s package, `#<PKG::K>` outside it, as SBCL prints them
  (no `{address}`; a raw `#<NAME :SLOT value>` otherwise). The name comes off the raw text, so it
  reaches `%symbol-print-bare-p` as a STRING: the compile paths' correction lists hold only symbols and
  answer no, leaving the rule, so a class imported into the current package keeps its qualifier there
  while the interpreter's live answer drops it. prove's own suite matches `#<MY-CONDITION` against this.
  `princ` stays raw, and slot values inside stay the known gap above. ci-spec
  `instance-type-name-follows-the-current-package`.
- A qualifier it keeps is the current package's nickname for the home when it has one (`%pc-qualifier`,
  `.kb/packages.md`, "Package-local nicknames").
- **A bare spelling gets a qualifier where the package reads that name as another symbol** (i30,
  2026-11-04, SBCL 2.2.9 checked): `COMMON-LISP:CAR` under a package using no `cl` or shadowing `car`,
  `COMMON-LISP-USER::FOO` outside `cl-user`. Two different symbols no longer print alike. The names are
  the standard's, not this implementation's `cl`/`cl-user`, and `package-name` and `package-nicknames`
  answer them too (`.kb/packages.md`, "Nicknames"). `%pc-unqualified` asks `%symbol-print-prefix`;
  the prefix goes through `%pc-qualifier`, so a local nickname of `cl` prints `C:CAR`.
  Interpreter: `PackageResolver.bareSymbolPrefix`, `printsBare`'s bare arm, the same resolution question.
  `nil`, `t` and the 93 names `cl` exports without implementing are no `isClSymbol` names: the bare arm
  answers them for a package using `cl`, so `cl:find-method` prints bare there although a read of
  `find-method` is the package's own (`.kb/packages.md`, "The `cl` external list").
- The compile path's half of the table (`noClPackages`, `clUserSymbols`, `bareExtra`, `bareExcluded`,
  lowered by `LispMacroExpander.expandSymbolPrintPrefix`) is rule + corrections like the qualified half.
  Rule: a name in `clUserSymbols` is qualified outside `cl-user`; any other prints bare, except a
  standard name under a package of `noClPackages`, the eight lambda-list keywords and `quote` excepted
  (`PackageRegistry.readsClInEveryPackage`). Corrections are asked of `printsBare` for the names that
  can differ: what the program can print, a standard name the package imports or shadows, and every
  standard name for a package using no `cl` with a non-empty use list.
  - `clUserSymbols` holds what the printer can meet: bare names in QUOTED data and the names of
    `defstruct`/`defclass`/`define-condition` (an instance's type name reaches it as a string, so these
    lists are strings compared by `string=`). A walk of every position was not taken: a spliced prelude's
    local variables are bare `cl-user` symbols too, and they would all travel.
  - `noClPackages` is by majority: a package re-exporting most standard names (`closer-common-lisp`,
    `clim-lisp`) reads as `cl`, and what it misses is `bareExcluded`.
  - `%standard-name-p` tells a standard name from a run-time read's bare `FOO` under such a package:
    `injectPrintStandardNames` splices its 7.4 K table only into a program that defines a package
    in `noClPackages` or enters a built-in one at top level. Without it the arm answers bare, as before.
- **What a compile path still prints bare:** a `cl-user` symbol interned under a computed name that no
  form spells, under any package. Its name is all there is, and no intern table says it was `cl-user`'s.
  The interpreter's answer is exact. `'nil` read under a package using no `cl` is `cl`'s `nil` on every
  backend (the reader folds it ahead of the resolver), so it prints `COMMON-LISP:NIL` where SBCL prints
  the package's own `NIL`.
- **`print-unreadable-object :type t` does not follow the package** (measured 2026-11-05, all four
  backends): `typeNameOf` peels the tag, so a `cl-user` class prints `#<HF-C>` under another package and a
  class of the current package prints `#<HF-P::D>`, where SBCL prints `#<COMMON-LISP-USER::HF-C>` and
  `#<D>`. The tag itself never takes a prefix (`bareSymbolPrefix`); with one the peel found nothing, and
  the interpreter printed `#<COMMON-LISP-USER::|%class-GEOM:SOLID|>` under a `cl-user` that uses a package.
- **A printed qualifier reads back:** the compiled run-time readers drop `CL`, `COMMON-LISP`, `CL-USER`
  and `COMMON-LISP-USER` qualifiers at the token (`JvmReadRuntimeBuilder.buildClassify`,
  `WasmReadRuntimeBuilder.emitDropStandardQualifier`), one colon or two. Until then `(read-from-string
  "cl:car")` was a symbol `CL:CAR` that was not `eq` to `car`.
- `type-of` answers the type's own symbol under any package: the interpreter's one-argument `intern` homed
  a bare `%struct-`/`%class-` name in the current package, a symbol `eq` to nothing the program spelled.
  `%home-spelling` qualifies it for that `intern` by `cl-user`, and is the text itself on the compile
  paths. It wraps the `intern` argument and does not replace the `intern`: the wasm `_intern` runtime
  gate, `RuntimeNameProducers` and `PACKAGE_ERROR_SITES` all key on that name, and a replacement that
  answered the first two still left nine programs with `no layout was baked for %class-PACKAGE-ERROR`.
- Pinned by ci-spec `a-bare-symbol-prints-qualified-where-the-package-reads-another`,
  `PackageResolverTest#{aBareSymbolPrintsTheQualifierOfItsHomeWhereThePackageReadsAnother,symbolPrintTableCarriesTheCorrectionsForTheSymbolsTheProgramSpells}`.

## A cyclic value prints finitely instead of overflowing the stack
**Invariant: no default renderer runs without bound on a cycle.** One mechanism
(`RenderCycleGuard`), two disciplines:

- Render path + depth cap: an instance, cons chain or array (general OR packed) opens ONE frame. A
  value already on the current path prints `#` in both escape modes, as does a frame opening past
  **256** (`RenderCycleGuard.MAX_RENDER_DEPTH`). The check is IDENTITY along the current path — not
  equality, not "rendered before" — so every finite rendering under 256 frames is byte-identical to
  before.
- Floyd over the cdr chain: a cdr cycle is an OutOfMemoryError, not a StackOverflowError, so the
  path guard cannot see it. The chain is walked ITERATIVELY with the cycle detected up front; the
  SECOND arrival at the cycle-start cell prints as the improper tail `" . #"` — `(1 . #)` for
  `(setf (cdr x) x)`.

Four implementations: interpreter `RenderCycleGuard` (ThreadLocal path array) from
`LispInstance.render`, `LispCons.render` (+ `cycleStart`), `LispArray.render`,
`LispFloatArray`/`LispIntVector.print`; JVM `_renderPath`/`_renderDepth` statics in EVERY class
(`JvmRuntimeBuilder.emitRenderGuardEnter`/`ExitAndReturn` + the Floyd prewalk in
`buildConsToStringBody`); both WASM backends two module globals after the hash/equalp counters,
unconditional; and the two Lisp walks, which thread path and depth as arguments and pre-scan with
Floyd (`%pos-chain-stop`/`%pc-chain-stop`), same text byte for byte, on a path SEPARATE from the
raw renderers' — so only a mixed nest past 256 frames renders differently routed vs unrouted.

The guard is unconditional; every artifact pays a small fixed size. It sits UNDER the
`print-object` route. `*print-circle*` proper (`#1=`/`#1#`) is unimplemented — the finite `#`
cutoff is deliberately label-free.

## What a stream with no column cannot do
**Every conditional line break is a no-op** — `pprint-newline` `:linear`/`:fill`/`:miser`,
`~_`/`~:_`/`~@_`, `pprint-indent`, `pprint-tab`, `~i`. Each needs the stream's column; a rontolisp
stream is an opaque integer handle with none (`.kb/standard-output-redirect.md`), and `format`'s
`~&`/`~t` only approximate it by scanning the string built SO FAR. So `*print-right-margin*`,
`*print-miser-width*` and `*print-lines*` are accepted and ignored, and a justification never pads
to `:mincol`. **Re-evaluate when a stream gains a column**: one field plus a write-through update
in every writing primitive, and `pprint-newline` and the `~_` family become one shared
"does the rest fit" test.

**The ordinary printing operators do NOT consult `*print-pprint-dispatch*`.** `princ`/`prin1`/
`print`/`~A`/`~S` are a per-backend primitive on the hottest path; the one hook above them,
`%print-object-str` (`.kb/clos.md`), is gated on the program defining a method. A dispatch entry
fires only where the program calls the entry function itself, as esrap does. Re-evaluate with the
column: both want the same seam.

**`char-name` answers nil for a graphic character**, which is CL; SBCL additionally returns the
Unicode NAME — the only difference between esrap's parse-error report here and on SBCL.
`name-char` (a prelude defun, 2026-10-31) inverts exactly what `char-name` answers, plus
`Linefeed`/`Nul`, so no Unicode name either: before it existed, cl-unicode's
`character-named` compiled `name-char` as an undefined `CL-UNICODE::NAME-CHAR`. ci-spec
`lite-builtins-residue`.

## The printer-control variables
`LispMacroExpander.PRINTER_MODE_VARS` is the single table: name -> global value. The interpreter
seeds it in `Environment.createGlobal` plus `LispEvaluator`'s special-variable set (they are BOUND,
not merely read). Compile paths get a top-level `(defvar name value)` from `injectMvSpillGlobal`
per variable the program MENTIONS — run after `expandTopLevelDefinitions`; "mentions" includes a
`write-to-string` keyword binding it (`LispMacroExpander.mentionsPrinterVariable`).

| variable | value | honored? |
| --- | --- | --- |
| `*print-escape*` | `t` | yes — picks prin1 vs princ; the `print-object` route binds it |
| `*print-readably*` | `nil` | yes — forces escaping, overrides length/level/gensym; never signals (below) |
| `*print-pretty*` | `t` | yes — gates the MANDATORY line break |
| `*print-circle*` | `nil` | no labels — the guard prints a cycle as `#`/`" . #"` |
| `*print-right-margin*` / `*print-miser-width*` / `*print-lines*` | `nil` | no (no column) |
| `*print-length*` / `*print-level*` | `nil` | yes |
| `*print-base*` | `10` | yes — integers and ratios re-spelled |
| `*print-radix*` | `nil` | yes — `#x`/`#b`/`#o`/`#Nr`/trailing `.` |
| `*print-case*` | `:upcase` | yes |
| `*print-gensym*` | `t` | yes — nil drops `#:` under prin1 |
| `*print-array*` | `t` | the value IS the behavior |
| `*print-pprint-dispatch*` | a fresh empty table | entries and lookup, but see above |

Every default is what the printer ACTUALLY does, so a program that only READS one sees the truth;
binding an inert one to a non-default value is what has no effect. The four standard STREAM
variables (`*trace-output*`, `*debug-io*`, `*query-io*`, `*terminal-io*`) ride the same table with
the `t` designator `*standard-output*` holds — esrap's rule tracing formats to `*trace-output*`
from inside a closure, and a captured free variable must be a declared global.
`*read-eval*`, `*read-suppress*`, `*read-base*` and `*read-default-float-format*` ride it too, each
special on all four since 2026-11-05 (the float format was a `setq`-seeded global, so a `let` of it
was lexical, and `*read-base*` did not exist).

## `with-standard-io-syntax`
**Invariant: the body sees CLHS 23.1.2's table on all four backends** (2026-11-05, SBCL 2.2.9 agrees on
ci-spec `with-standard-io-syntax-binds-the-standard-table`; quri's `*print-base*` pins made
`quri-test` `All 6 files passed`).

- One table, `LispMacroExpander.STANDARD_IO_VALUES`: only `*print-pretty*` nil, `*print-readably*` t,
  `*read-default-float-format*` `single-float` and a fresh `*readtable*` differ from the globals.
- The interpreter's `let` binds all of it. **A compile arm binds only the program's specials**
  (`expandWithStandardIoSyntax(cons, ctx.specialVars)`): a name nothing mentions has no defvar and
  nothing reads it, the printer included, so a program naming no control variable keeps its
  `(let ((*package* :cl-user)) ...)`, its bytes and no case-fold tables. The renderer naming
  `*print-readably*` is a mention, so a routed program binds it.
- `*print-readably*` on: `prin1` ignores `*print-length*`/`*print-level*` and keeps `#:`
  (`%print-cased`, `%print-object-str` as a binding at the entry, not a test in each walk); `princ`
  binds it nil around a `print-object` method (`printObjectCall`, for a program naming the variable);
  `write`, a `write-to-string` keyword and a one-argument `write-to-string`
  (`expandWriteToStringConversion`; `(let ((*print-escape* nil)) (write-to-string "a"))` printed
  the quotes until then) take `prin1` when it or `*print-escape*` is on.
- **No printer signals `print-not-readable`** (measured 2026-11-05: `(with-standard-io-syntax
  (prin1-to-string (lambda (x) x)))` answers `#<...>` on all four, SBCL signals). The class exists
  (`ClosRegistry`, reader `print-not-readable-object`) because serapeum and coalton signal and handle
  it themselves; `(error 'print-not-readable)` used to find no class, escaping `(error ...)` clauses.
  The signal has traps of its own (`.todo/i47`).
- Inert under it: `*read-default-float-format*` (`1.5d0` prints `1.5`, SBCL `1.5d0`).

## Tests
`LispEvaluatorTest.evalWriteAndPprintDispatch`/`evalPprintLogicalBlock`/`evalPrintCase`/
`evalPrintLengthLevelGensymBaseAndRadix`/`evalWriteAndWriteToStringKeywords`/
`evalPrintDropsTheQualifierOfAnAccessibleSymbol`/`evalPrintOfACyclicConsIsFinite`;
`PackageResolverTest`; `JvmLispCompilerTest.compileAndRunPrintCase`/`compileAndRunPrintControls`/
`compileAndRunStringOfASymbolIsNotFoldedByPrintCase`/
`compileAndRunWriteToStringKeywordAloneBindsThePrinterVariable`/
`compileAndRunPrintOfACyclicConsIsFinite`; `WasmLispCompilerIntegrationTest.printCase`/
`printControls`/`printDropsTheQualifierOfAnAccessibleSymbol`/`printOfACyclicConsIsFinite` and
their component twins; ci-spec `print-case`, `print-length-level-gensym-base-radix`,
`symbol-print-accessibility`, `backquote-quoted-splice`, `print-cyclic-instance-graph`,
`print-cyclic-cons`, `esrap-enablement-language-group`; `EsrapE2eTest`, `RoveE2eTest`.

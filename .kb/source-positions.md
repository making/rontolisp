# Source positions: `file:line:column` in reader AND frontend errors, and the two literals a program can read

An error the frontend raises must say WHERE. With ASDF/Quicklisp splicing a
multi-file library into one flattened program (`LoadInliner`), a bare message
about a 5000-line spliced program is the single biggest cost of bringing up a
new community library. Two mechanisms, one answer:

- **Reader errors** (phase 1 of `.todo/151`): the lexer/parser carry offsets and
  resolve them on the way out. Works everywhere, including the interpreter.
- **Everything raised AFTER the read** (phase 2): a side table records where each
  cons came from, and the frontend passes attach the nearest enclosing recorded
  position to a failure as it unwinds. **Compile path only** -- see the
  divergence section.

Positions never reach an emitter: compiled output is byte-identical with and
without any of this.

## The value type

`am.ik.rontolisp.SourceLocation` (`file`, 1-based `line`, 1-based `column`) with
`at(file, offset, input)` (counts `\n`; a lone `\r` stays on its line, which is
right for CRLF) and `prefix()`. It lives in the AST's own package, NOT in
`reader`: both the reader and the frontend passes need it, and `compiler` /
`codegen.*` may not import `reader` (CLAUDE.md package rules).

**No file means no prefix.** `prefix()` is `""` when `file` is null -- a runtime
`read`/`read-from-string` of a string, a REPL buffer -- so runtime error text
stays byte-identical.

## Phase 1 -- reader errors

`LispLexer` tokenizes to `List<LocatedToken>` (token + start offset);
`LispReader` unpacks the offsets into a parallel `int[]` so token access is
untouched and no `Integer` is boxed per token. Line/column is computed lazily by
`SourceLocation.at` only when an error is raised, so nothing is stored on the
AST. `LispReadException` carries the location and puts the prefix in its message,
so even a caller that only surfaces `getMessage()` reads self-descriptively.

**Every reader gets its origin file.** `LoadInliner.spliceFile` (including ASDF /
`ql:quickload` component files), `AsdfSystems`, `RontoLispCli.compileToFile` /
`interpret` -- and `LispEvaluator.loadFile`, which does NOT go through the
inliner: leave that one out and the same stray token names its file when compiled
and nothing when interpreted, the exact divergence this feature exists to remove.

**Which position an error reports.** A construct that only fails once the input
runs out -- an unterminated string, `#|` block comment, `|...|` symbol escape,
raw skipped list, or a list the parser never sees closed -- reports the offset
where it OPENED, not the scan position. Reporting end-of-input is what a naive
`pos`-at-throw does, and in a big spliced library it names the last line of the
file, which is exactly the useless answer this replaces. `LispLexer.errAt` /
`LispReader.errAtToken` are the seams; every other error keeps the scan position,
where the malformed token actually is.

Tests: `LispReaderTest#unterminatedConstructsPointAtTheirOpeningDelimiter`,
`#anUnclosedListPointsAtItsOpeningParen`,
`LispEvaluatorTest#readerErrorInARuntimeLoadNamesTheLoadedFile`,
`LoadInlinerTest#readerErrorIn*`.

## Phase 2 -- per-form provenance for the frontend passes

`am.ik.rontolisp.SourceProvenance` is an `IdentityHashMap<LispCons, (unit,
offset)>` plus the unit's text, filled by `LispReader.readExpr` for every datum it
reads. Keyed by cons IDENTITY, never a field on `LispVal`: the type is sealed and
its leaf values are shared, so a field would be wrong on the leaves and cost
memory on every program. Only conses are recorded; an error about an atom is
reported against the form containing it.

**How a location reaches an error, without touching the exception.** A pass may
catch its own exception types to fall back, so wrapping is not an option. Each
recursive pass instead does

```java
catch (RuntimeException ex) { throw SourceProvenance.noteFailure(cons, ex); }
```

which returns the SAME exception. The first frame to note a location for a given
exception wins -- the innermost, since notes happen while unwinding -- and a frame
whose cons is macro-generated (absent from the table) leaves the slot for an
enclosing one: that is the "nearest enclosing located cons" rule. A pass that
merely walks top-level forms calls `enterTopLevelForm(form)` once per form
instead (no `try`/`finally`, since it is only ever read while a failure unwinds);
that is the fallback when no frame had a hook. Hooked today:
`UserMacroExpander.expandAll` + its top-level loop, `Jvm/WasmExprCompiler`'s cons
dispatch, `FreeVarAnalyzer.collectCapturedVars` AND `collectFreeVars`.

The free-var walk needs its own hook because it EXPANDS the macros whose raw shape it
would otherwise misread (`check-type`, `assert`, `do`, `loop`, ...) -- and it is the
first pass to touch a TOP-LEVEL one, before `Jvm/WasmExprCompiler`'s dispatch has seen
it. A malformed top-level `(check-type 1)` therefore used to report with no position at
all while the same form one line inside a `defun` reported exactly.

`RontoLispCli.compileToFile` opens the recording scope, and on the way out
re-reports the failure as `cli.LispCompileException` with the prefix prepended and
the original as the cause. A `LispReadException` is left alone (it prefixes
itself), and so is a failure with no known position -- prefixing nothing is noise.
For a WARNING, which is printed rather than thrown, `SourceProvenance.prefix(form)`
gives the same string (`Jvm/WasmFunctionCallCompiler`'s undefined-function
warning).

**A warning goes through `compiler.CompileWarnings.warn`, never `System.err`
directly.** `JvmLispCompiler` may compile the same program TWICE -- its runtime-helper
gate is a prediction checked against the emitted bytecode, and a mispredicted gate
re-runs the whole compile -- and the discarded attempt had already printed, so one
undefined-function call site said it twice. An attempt buffers its warnings
(`startAttempt`) and only the one that SHIPS prints them (`flushAttempt`); a retried
attempt drops its own (`discardAttempt`). With no attempt open -- the WASM backends,
which never re-run -- `warn` prints straight through, so their output is unchanged.
Pinned by `RontoLispCliTest#anUndefinedFunctionWarnsExactlyOncePerCallSite`.

### The cons-identity rule every AST pass must honour

**A frontend pass that changes nothing must return the object it was given.** The
whole mechanism rests on it: rebuilding an unchanged cons erases its position, and
because a rebuilt parent forces rebuilt children, ONE gratuitous copy in the
middle of the pipeline drops the position of the entire program below the top
level. It is also free performance -- the passes below rebuilt a copy of every
program's whole AST for nothing.

Go through the shared helpers rather than writing the check by hand:
`LispCons.rebuilt(original, car, cdr)` for a car/cdr walk,
`LispCons.rebuiltList(original, elements)` for a `toList()`-style one. Each returns
`original` when nothing actually changed; `rebuiltList` also refuses to hand back a
DOTTED original, whose proper-list rebuild would have dropped the tail
(`LispConsTest`).

Made identity-preserving for this (each has a comment saying so at the rebuild
site): `CompileTimePathnameFolder.recurseCons`/`foldDefParam`/`foldWithOpenFile`,
`PackageResolver.resolveCons` (generic walk + the `quote` branch + `resolveQuotedDatum`,
which used to copy every quoted list of every program) and its `resolveSymbol` (a name
that resolves to itself hands back the symbol as read -- symbols carry no identity here,
there is no intern table, so a fresh copy is indistinguishable in behavior but forces the
enclosing cons to be rebuilt),
`TlsPemInliner.rewrite`, `LambdaLists.desugar`, `CrossLambdaExitLowering`
(`structural` plus `transformLambda`/`transformDefun`/`transformFunction`),
`JsonLibrary`'s call-site walk, `GrayStreamsLibrary.rewriteBindingForm`,
`UserMacroExpander.rebuild`/`expandBindings` and its `case`/non-symbol-head branches,
`ShadowedBuiltins`, `WasmSocketsRewrite`, `WasmArityBundler.rewriteElements`.
`StructLiteralFolder` already did.

The two worst were not the obvious ones. `UserMacroExpander` rebuilt the whole AST of
any program containing a single `defmacro` -- and hid behind a `print()`-equality check
that restored the ORIGINAL top-level form, so top-level positions looked right while
everything below them was gone. And `WasmArityBundler` / `ShadowedBuiltins` /
`WasmSocketsRewrite` run INSIDE `Jvm/WasmLispCompiler.compile`, so a probe of the CLI's
own pipeline shows nothing wrong.

**When you add or touch an AST pass, add the unchanged check.** The way to tell whether
a pass still needs it: compile a program that triggers it with a malformed form deep
inside a `defun` (`aMalformedFormKeepsItsLineWhenTheProgramAlsoTriggersALibrarySplice`
is exactly that, one case per pass) and check that the reported line is the malformed
form's own -- not the top-level one, and not absent. What legitimately stays coarse is
a form the frontend genuinely REWROTE: a macro expansion's products were never read, so
a failure inside one falls back to the nearest enclosing form that was.

### The other half: a pass that legitimately REWRITES

The identity rule only covers the pass that changes nothing. A pass that genuinely
rewrites a form -- a constant fold, an inliner -- rebuilds every cons from the
top-level form down to the rewrite, and each of those is a fresh key in an
identity-keyed table. For a rewrite that fires rarely that is the "genuinely REWROTE"
case above and coarsening is right; for one that fires all over an ordinary program
it would blank out most of it.

`SourceProvenance.inherit(original, rewritten)` closes that: the rewritten cons stands
for the same source text, so it takes the original's position (a no-op when the pass
handed back the original, when the result is not a cons, or when nothing is
recording). `PureBuiltinFolder` -- whose folds land in essentially every real program
-- routes every rebuild through it, and it is the general answer for the next
rewriting pass. Pinned by
`PureBuiltinFolderTest.aFoldedFormKeepsTheSourcePositionItReplaced`.

**A pass can owe BOTH halves, and `PackageResolver` is the one that owes them most.**
It satisfied the identity half and not the inheriting one, which made it the widest
position hole there was: a form in which a single symbol resolves to a different name
is genuinely rewritten, so it -- and every ancestor up to the top-level form -- became
a cons the table had never seen, and the innermost-located-ancestor fallback found
nothing to report against. That is not a rare shape; it is every form of every file
that says `(in-package :foo)` and then names anything qualified (or just reads
`*package*`), i.e. the whole of every quickloaded library. Every rebuild site of
`resolveCons` -- the generic walk, the `quote`/`defmacro`/`macrolet`/`case` branches,
the `find-package` fold, the wasm/wit directives and the introspection normalizer --
now inherits. What legitimately still coarsens is a CONSUMED top-level directive
(`in-package`, `defpackage`, `export`), which is replaced by a constant no later pass
can fail inside. Pinned by
`RontoLispCliTest#aMalformedFormKeepsItsLineInsideAPackageQualifiedFile` (without it
the failure has no position at all, so the compile boundary does not even wrap it --
a raw `ClassCastException` reaches the user).

## Phase 3 -- the source position literals a PROGRAM can read

`rontolisp:current-file` and `rontolisp:current-line` (`LispNames.CURRENT_FILE` /
`CURRENT_LINE`) are substituted by `LispReader.sourceLiteral`, next to `pi`,
`most-positive-fixnum` and `array-dimension-limit`: the first becomes a `LispString` of the origin
file (or `nil` when the read has none -- a REPL line, a `read-from-string`), the second a
`LispInteger` of the 1-based line the SYMBOL itself stands on.

**In the reader, not at expansion time.** It is the only place that knows where each
occurrence stands and is shared by the interpreter and all four backends, so they agree
by construction and no backend sees anything but a string and an integer -- the emitters
are untouched, like the rest of this file. The alternative (resolving during macro
expansion, off `SourceProvenance`) would need the interpreter to record provenance too,
which is exactly the divergence below, and would still answer approximately for
macro-generated forms.

The consequence is the `__FILE__` / `__LINE__` one: inside a `defmacro` template these
name the macro's own definition site, so a logging macro takes them as ARGUMENTS at its
call site. Two more consequences worth knowing before changing anything here:

- Only the qualified spellings are recognized (`rontolisp:`, `rontolisp::`, the `rl:`
  nickname), because the reader runs before any `in-package` directive is interpreted. A
  bare `current-file` must stay an ordinary symbol -- a user may have defined one.
- Substitution is unconditional, quoted data included, like `#+`/`#-` and `#.`.

A `load`ed / ASDF-spliced file names ITSELF: phase 1 already hands every reader its own
origin file, and that is what makes these useful in a multi-file program at all.

Tests: `LispReaderTest#currentFileAndCurrentLineReadAsTheirOwnPosition` and its three
neighbours, `RontoLispCliTest#theSourcePositionLiteralsNameTheLoadedFileNotTheEntryFile`,
and `ci-spec.yaml`'s `source-position-literals` case (all four backends; it asserts the
TYPES and a one-line delta, since the concrete values are the driver's own temp file and
offsets).

### The compile-path-only divergence (with its re-evaluation trigger)

Recording is opt-in per thread and only `RontoLispCli.compileToFile` opts in. Two
reasons:

- The interpreter reaches the same expander at EVALUATION time. A prefix there
  would land on ordinary runtime error text, which `ci-spec.yaml` and the doc
  examples pin byte for byte. The compile path's frontend is over before the
  program runs, so its diagnostics are free to say where.
- A served request may `load` at run time; a process-wide table would grow without
  bound and race across request threads. The state is a `ThreadLocal`, so a thread
  that never opens a scope pays one null check and a request thread that does
  takes its table with it when it ends.

**Trigger:** if the interpreter ever grows a separate frontend phase -- one that
expands a whole program before evaluating any of it -- the first reason stops
holding and the divergence should be retired. Phase 1's positions already cover
the interpreter, so only post-read errors are affected.

Tests: `SourceProvenanceTest` (recording, innermost-wins, the macro-generated
gap, the top-level fallback, scope teardown), `LispConsTest` (the two rebuild
helpers), `RontoLispCliTest`
(`aMacroThatSignalsWhileExpandingNamesItsCallSiteInTheLoadedFile`,
`aMalformedFormDeepInsideADefunNamesItsOwnLineOnBothCompileBackends`,
`aMalformedFormKeepsItsLineWhenTheProgramAlsoTriggersALibrarySplice`,
`aMalformedFormKeepsItsLineInsideAPackageQualifiedFile`,
`aMalformedTopLevelCheckTypeNamesItsOwnLine`,
`aCallToAnUndefinedFunctionWarnsAtItsCallSite`,
`anUndefinedFunctionWarnsExactlyOncePerCallSite`,
`theRecordingScopeIsClosedEvenWhenTheCompileFails`,
`theInterpreterKeepsItsBareErrorText`).

# Source positions: `file:line:column` in reader AND frontend errors

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
dispatch, `FreeVarAnalyzer.collectCapturedVars`.

`RontoLispCli.compileToFile` opens the recording scope, and on the way out
re-reports the failure as `cli.LispCompileException` with the prefix prepended and
the original as the cause. A `LispReadException` is left alone (it prefixes
itself), and so is a failure with no known position -- prefixing nothing is noise.
For a WARNING, which is printed rather than thrown, `SourceProvenance.prefix(form)`
gives the same string (`Jvm/WasmFunctionCallCompiler`'s undefined-function
warning).

### The cons-identity rule every AST pass must honour

**A frontend pass that changes nothing must return the object it was given.** The
whole mechanism rests on it: rebuilding an unchanged cons erases its position, and
because a rebuilt parent forces rebuilt children, ONE gratuitous copy in the
middle of the pipeline drops the position of the entire program below the top
level. It is also free performance -- the passes below rebuilt a copy of every
program's whole AST for nothing.

Made identity-preserving for this (each has a comment saying so at the rebuild
site): `CompileTimePathnameFolder.recurseCons`/`foldDefParam`/`foldWithOpenFile`,
`PackageResolver.resolveCons` (generic walk + the `quote` branch) and
`resolveSymbol` (a name that resolves to itself hands back the symbol as read --
symbols carry no identity here, there is no intern table, so a fresh copy is
indistinguishable in behavior but forces the enclosing cons to be rebuilt),
`TlsPemInliner.rewrite`, `LambdaLists.desugar`,
`CrossLambdaExitLowering.structural`. `StructLiteralFolder` already did.

Not yet identity-preserving, and the reason positions can still be coarse: any
pass a given program actually triggers (a library splice, the defun pruner, the
`--component` rewrites). Those degrade gracefully -- the failure falls back to the
enclosing recorded form, or to no prefix at all -- so this is a quality ladder,
not a correctness cliff. **When you add or touch an AST pass, add the unchanged
check.**

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
gap, the top-level fallback, scope teardown), `RontoLispCliTest`
(`aMacroThatSignalsWhileExpandingNamesItsCallSiteInTheLoadedFile`,
`aMalformedFormDeepInsideADefunNamesItsOwnLineOnBothCompileBackends`,
`aCallToAnUndefinedFunctionWarnsAtItsCallSite`,
`theRecordingScopeIsClosedEvenWhenTheCompileFails`,
`theInterpreterKeepsItsBareErrorText`).

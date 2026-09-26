# Compile-time warnings and `--warnings-as-errors`

**Invariant: a construct the interpreter only signals when it runs never fails a compile by
default** -- it compiles to the same run-time condition and prints `warning:` at its position
([error-handling.md](error-handling.md), the wrong-count sections). **`--warnings-as-errors`
(2026-09-26) fails a compile that emitted a COUNTED warning: every warning printed first, then
`error: N warning(s) about the program's source, treated as errors (--warnings-as-errors)`, exit
1, nothing written.** Every `-o` output, `JvmSourceCompiler.warningsAsErrors`, the Maven plugin's
`warningsAsErrors` (`rontolisp.warningsAsErrors`). Without `-o` refused (the interpreter emits
none). User doc: `doc/*/compiling/warnings.md`. Pinned by `CompileWarningsTest`,
`RontoLispCliStreamsTest.warningsAsErrors*` (.class/.jar/.wasm, macro-built code),
`RontoLispCliTest.warningsAsErrors*`, `JvmSourceCompilerTest`, the plugin's `LispSourceSetTest`.

## The emitter: `compiler/CompileWarnings`
- **`warn(subject, text)`** -- a warning about a form: printed `prefix + "warning: " + text`,
  placed and judged by `SourceProvenance.warningLocation`. **`note(line)`** -- a line about the
  build or the host, printed verbatim, never counted: the `:async t` / `--host-fetch` host
  obligations and `warning: no JDK found` (environment, nothing in the source to change).
  Everything else is `warn`: undefined function, static program-error (`warnStaticProgramError`),
  CL redefinition, `--warn-java-reflection` sites, the `--no-wasi` load-path lines
  (`NoWasiLoadPathRefusals.warn`), a war-ignored `http-handler` port.
- **Attempt buffering is where discarded warnings are dropped**: an entry is `line -> counts`; only
  `flushAttempt` (and the unbuffered print) increments the count, `discardAttempt` drops both.
  A backend re-running a compile (JVM gates/outlining, wasm-GC outlining) therefore never counts
  a warning twice or counts one the shipped output does not have.
- **Counting is always on inside `CompileDiagnostics.recording(dists, ...)`** (CLI and
  `JvmSourceCompiler`); only `CompileDiagnostics.failOnWarnings(flag)` -- after the backend,
  before any write -- depends on the option. It throws `cli/WarningsAsErrorsException`, which
  `CompileDiagnostics.locate` leaves undecorated (it is about the compile, not a form).

## What counts: the program's own source
- **Program source = a unit the seam read.** `SourceLanguage.read` runs inside
  `SourceProvenance.readingProgramSource`; the readers get their unit from
  `SourceProvenance.unit(file, text)`, which marks it (`State.programUnits`, identity) while
  that is open. Entry file, `-e` (no file, still counted), `load`ed files, ASDF systems and dist
  systems all come through the seam; a shipped library a splice reads with
  `LispReader.readAllFromString` does not, so its warnings print and never count (the
  `TOKENIZER:... is undefined` precedent). A library read once into a static cache is not even
  recorded on a later compile -- unlocated, uncounted: the rule is "positively in the program",
  never "not in a library".
- **A dist-installed system is a dependency**: `DistClient.installedSource(file)` (under an
  installed dist's `software/`) caps it, as cargo caps a registry dependency's lints. Measured
  2026-09-26 over `examples/` (231 files x .class/.wasm): the only counted-looking warnings from
  outside a program were postmodern's `CONNECTION-META` and cl-unicode's `NAME-CHAR`
  (undefined), both in `~/.rontolisp/quicklisp/software`.
- **Code a macro built is judged at the nearest located form.** A backend's `compileCons`
  (JVM, wasm-GC) and `NoGcWasmCompiler.compileExpr` bracket every cons with
  `SourceProvenance.enterForm` / `leaveForm`; `warningLocation` falls back to that innermost
  located form -- the rule `noteFailure` applies to errors. Top-level `progn`s are flattened
  before the backend sees them, so `LispMacroExpander.flattenTopLevel` gives each spliced part the
  wrapper's position when it has none (`inheritWhenCompiling`, compile path only): a user macro
  expanding to `(progn (defun ...) ...)` is placed at its call. Measured before this: such
  warnings printed with NO position at all.
- **Only compiled code warns**: a system's `defun` the tree-shaker drops is never compiled, so
  a wrong call inside it says nothing (`.kb/library-defun-pruning.md`).
- **Not counted**: the macro-time evaluator's own `warning: skipping macro-time evaluation` line
  (a `defvar` init form that failed at compile time -- no program defect).

## A `(warn ...)` while a macro expands (2026-09-26)
- **SBCL's rule**: a WARNING signalled during `compile-file`, macroexpansion included, sets
  `failure-p`; a STYLE-WARNING does not. Here: an unmuffled macro-time warning is
  `CompileWarnings.warn(null, text)` -- placed and judged at the innermost located form, so it
  counts exactly when the macro CALL is in the program's source (a library macro called inside
  library code prints, uncounted). A `style-warning` is `CompileWarnings.styleWarning`
  (`file:l:c: style-warning: text`, never counted). A handler that muffles it runs before the
  report, so nothing is printed or counted.
- **Mechanics**: `UserMacroExpander.expandCompiling` (the two top-level `expandAll` calls of
  `expand`, i.e. compile path only) runs the walk under `LispEvaluator.reportingWarningsTo(sink)`;
  the evaluator's `%warn` hands the report (minus `WARNING: `) to the sink instead of
  `*error-output*`. `expandAll` brackets every cons with `SourceProvenance.enterForm`, which is
  where "innermost located form" comes from. The interpreter's own `expandAll` uses (macrolet
  pre-expansion) install no sink, so a run prints `WARNING:` as before.
- **Style-warning test**: the interpreter expands `warn` through
  `LispMacroExpander.expandWarnWithDesignator`, whose `%warn` terminals carry a second argument
  naming what was signalled (quoted class, the runtime datum `__signal_cond`, or nil for a
  literal control); `designatesStyleWarning` answers by `subtypep`/`typep`. The compiled
  backends expand `warn` themselves and never see the two-argument shape.
- **Scope**: macro expansion only. `eval-when (:compile-toplevel)`, `#.` and library replay
  still print `WARNING:` uncounted (SBCL would count a compile-toplevel one).
- Pinned by `MacroTimeWarningsTest` and `RontoLispCliStreamsTest.warningsAsErrorsCountsAWarningAMacroSignalsWhileItExpands`.

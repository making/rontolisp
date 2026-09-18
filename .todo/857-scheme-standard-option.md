# `--scheme-standard r7rs|rontolisp`: choose how strict the Scheme front end is

Difficulty: Medium

Like Gauche's `gosh -r7`, one option picks the standard every Scheme file of the
program is read against: the entry file, a file `(load ...)`ed at run time or inlined
on the compile path, and the REPL session.

| value | meaning |
|---|---|
| `rontolisp` (default) | today's behavior: R7RS plus the SICP/MIT-compatibility names (`sicp` tag) and the `r5rs` names, all visible to a file with no `import` and to the REPL. This implementation's own dialect, like gosh's default mode; no compatibility promise with any other |
| `r7rs` | R7RS-small, strictly, within the subset the front end implements |

Keep the value set open: `r5rs` (case-folding reader, no `import`, R5RS names) is a
plausible later value. Do not add it here.

## Conformance fixes that come first (both modes)

Checked against Gauche 0.9.15's `(module-exports (find-module 'scheme.<lib>))`
(2026-09-18). `SchemeBuiltins` tags these wrongly:

- `exact->inexact`, `inexact->exact` are tagged `base`. They are `(scheme r5rs)`
  names: retag `r5rs`. Today `(import (scheme base))` wrongly exposes them.
- `read-char`, `peek-char`, `read-line`, `char-ready?`, `eof-object`, `eof-object?` are
  tagged `read`. R7RS puts them in `(scheme base)`, and `(scheme read)` exports only
  `read`. Retag them `base`.

Every other entry matches its R7RS library. Visible change: a file that imports
explicitly. A file with no `import` merges every tag, so the SICP corpus cannot move.
Confirm this with `SicpCorpusE2eTest`.

## What `r7rs` changes

1. **A file that does not begin with `import` is refused** when it is lowered, as a
   positioned `LispReadException`: "an R7RS program begins with an import declaration"
   (R7RS 5.1). Gauche instead starts with an empty environment and fails later on the
   first unbound name.
2. **`sicp` and `r5rs` names are never visible.** They are already unreachable through
   `import`; in `r7rs` mode `SchemeLowering.imports()` also does not merge them in its
   no-import branch (the REPL's case).
3. **Redefining an imported binding is refused** when it is lowered. That covers a
   top-level `define`, `define-values` or `define-record-type` name, and a `set!` of
   one. R7RS 5.6.1 says "it is an error"; Gauche -r7 allows the `define` silently and
   only warns on `set!` of an inlinable binding. Strict mode is for finding
   non-portable code, so it reports the error.
   `rontolisp` mode keeps "a user `define` wins" and the read-before-redefine
   variable shape.
4. **`(eval x)` with no environment is refused** (the argument is required in R7RS).
5. **The REPL starts with the nine R7RS libraries** (`IMPORTABLE_LIBRARIES`) and no
   `sicp`/`r5rs` names. Gauche -r7's `r7rs.user` REPL also starts populated. An
   `import` at the prompt still only adds names.

Unchanged in both modes: a name the file neither imports nor defines is still a direct
call or variable reference. That is how Scheme and Common Lisp files call each other,
and a whole-program unbound-name check would cross files.

Decide during the work, and record the decision in the `.kb`: whether `eval`'s run-time
table (`Scheme.runtimeForms`, generated from the tags) omits `sicp`/`r5rs` entries in
`r7rs` mode. The compiled table is cut to spelled names, but the interpreter's copy
holds every entry and is loaded lazily, keyed on function resolution. Today every
environment specifier is the one global environment, so `(eval '(1+ 1) (environment
'(scheme base)))` would still reach `1+`.

## Plumbing

The mode is a property of reading Scheme source, so it travels with the
`SourceLanguage` seam (`.kb/source-language.md`), not beside it:

- `RontoLispCli`: parse `--scheme-standard NAME` next to `--source-language`, add the
  help text, and refuse an unknown value by name.
- `CompileFrontend.run`/`expand`, `LoadInliner` (every inlined `.scm`), the
  interpreter's `load`, `SourceSession`/`SchemeSession` (REPL), and
  `JvmSourceCompiler` (embedders).
- `rontolisp-maven-plugin`: a `schemeStandard` parameter, if the plugin compiles `.scm`.
  Check that first.
- The playground (`src/web/java`) keeps the default; `./mvnw -Pweb compile` if any
  signature it overrides changes.
- `Scheme`/`SchemeLowering` take the mode as a value (an enum in `scheme`). No new
  package edge.

## Tests and docs

- `SchemeLoweringTest`: each refusal (1-4) with its position, and the retagged names
  under an explicit import.
- `RontoLispCliTest`: the option on a file, on the REPL, with an unknown value, and on a
  loaded `.scm`.
- `scheme-spec.yaml`: a valid R7RS program prints the same in both modes on all four
  backends. Check whether `SchemeSpecE2eTest` can pass CLI flags; if not, add a
  per-case field instead of a second corpus.
- `.kb/scheme-frontend.md`: a section for the modes, and update the library-tag section.
- `doc/{en,ja}`: the option and both values, on the Scheme pages (`guides/scheme.md`, or the split pages if `.todo/859` has landed).
  Run `DocExamplesTest`.

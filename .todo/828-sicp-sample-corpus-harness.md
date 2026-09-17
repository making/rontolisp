# Run the SICP sample corpus: an opt-in E2E with a category manifest

Difficulty: Medium

Target for the experimental Scheme front end (`.kb/scheme-frontend.md`): every SCHEME
program in the SICP sample corpus (`https://sicp.sourceacademy.org/sicp.zip`, 1,592 `.scm`)
runs, on all four backends, with the same output. Proper tail calls and re-entrant
continuations stay out of scope. This item owns the harness and the manifest; the missing
pieces are `.todo/829` .. `.todo/837`.

## Baseline (2026-09-17, probes and per-file table in `.todo/artefacts/828-sicp-sample-corpus-harness/`)

| category | files | interpreter ok today | note |
|---|---|---|---|
| `scheme` -- every free name is defined in the file, provided, or planned | 1,182 | 1,103 | 77 more pass once the planned names exist (measured with a stand-in prelude); 2 behave as the book says: `chapter1/section1/subsection6/26.scm` never terminates under applicative order, `chapter3/section3/subsection5/05_set_value_example_2.scm` ends in `(error "Contradiction" ...)` |
| `concurrent` (`variant=concurrent`) | 19 | 14 | the other 5 call `parallel-execute` (`.todo/834`) |
| `fragment` -- references a name no form in the file defines | 291 | 99 | defective in any Scheme; passes only when the path is never taken |
| `embedded-query` (`chapter4/section4/subsection1`) | 52 | 25 | query-language text, not Scheme |
| `embedded-amb` / `embedded-lazy` (`variant=non-det` / `lazy`) | 27 / 4 | 8 / 2 | programs FOR the book's evaluators |
| `js-import` (`import { beside } from 'rune';`) | 13 | 0 | not Scheme; the reader refuses `{` |
| `unreadable` (unbalanced `)`) | 4 | 0 | `chapter2/section3/subsection2/25..28` |

- With the stand-in prelude, 1,327 of the 1,342 files that exit 0 on the interpreter give
  byte-identical stdout on the JVM and on wasm. The 15 others: 4 are the stand-in's own
  fault (the file redefines `*` or `apply`, which a helper written in Scheme then calls --
  so the real helpers must stay Common Lisp in `scheme.lisp`, immune to a user `define`),
  11 are `fragment`s: a reference to a global VARIABLE nothing defines is a compile error
  (`Cannot compile symbol reference`), where the interpreter only fails if the path runs.
  Same for a Common Lisp program; decide here whether the harness just classes them as
  fragments (recommended) or the compile path downgrades to a call-time error like an
  undefined FUNCTION already does.
- File mode and REPL mode agree on every file (no file fails in exactly one).
- The `; expected:` comments (450 files) come from a differently shaped edition of each
  program: 158 agree with the REPL echo, no disagreement is a wrong value (the annotation
  names another expression, or the file ends in a definition). Use them as a positive
  check only, never as a gate.

## To do

1. `SicpCorpusE2eTest`, opt-in like `ExamplesE2eTest` (`-Drontolisp.sicp=<unpacked dir>`);
   the corpus is never checked in. Reaches the front end through `CompileFrontendAccess` /
   `JvmSourceCompiler`, all four backends, stdout compared against the interpreter.
2. A checked-in manifest: category per file (the static rule in `baseline.py`, ported), and
   the expected outcome for the named exceptions. A `scheme`/`concurrent` file that fails
   is a test failure; a `fragment` that starts to fail differently is not.
3. Second stage, after `.todo/832`: feed `embedded-*` samples to the evaluator the corpus
   itself ships (its `driver-loop` reads them from stdin) instead of excluding them.
4. Record the numbers in `.kb/scheme-frontend.md`; add a row to `.kb/running-backends.md`.

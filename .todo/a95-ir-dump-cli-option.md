# a95: CLI option to dump the IR as s-expressions

Difficulty: Low

## What

Add a CLI debug option that prints, for each top-level form of the input
program, the post-expansion IR (the core-form `LispVal` trees produced by
`CompileFrontend.expand`) as s-expressions, then exits without compiling or
running.

## Why

The IR (core forms) is the contract between the front end and the four
backends, but today it can only be inspected indirectly. A textual dump makes
it possible to see exactly what a macro expansion or a language lowering
(scheme, future frontends) produced, and to diff it, without reading backend
disassembly or adding temporary prints in `CompileFrontend`.

## Shape

- A opt-in flag on the CLI entry (naming follows the existing option style,
  e.g. `--dump-ir`); per-top-level-form output, one form per dump, in source
  order.
- Dump AFTER `CompileFrontend.expand` (macro expansion, library splice,
  load inlining) so the output is the final form the backends consume. A
  pre-expansion dump may be added later only if a need appears.
- Printing uses the existing printer for `LispVal` so the output is readable
  back by `LispReader`.
- Must behave identically regardless of which backend would have been chosen
  (the dump happens before backend selection; no backend code involved).
- The web playground does not get this; CLI only.

## Tests

- A CLI-level test: small program using macros (`cond`/`setf` etc.), assert
  the dump shows the expanded core forms, not the source surface syntax.
- A scheme `.scm` input dumps the lowered core forms (proves the lowering is
  visible through the same flag).
- Exit code and stdout purity: the dump is the only stdout content when the
  flag is given.

## Notes

- `.kb/architecture.md` package graph: the flag handling lives in `cli`; the
  dump must not add a new dependency edge.
- Nothing in `CompileFrontend.expand` changes; this is a new caller of it.

# f19. Clojure: `format` flags (`%04x`)

Difficulty: Medium

instaparse 1.5.0 (verbatim, interpreter, 2026-10-10) stops at `print.clj:38:8: format flag %0
is not supported yet` -- `(format "%%x%04x" lo)` in `char-range->str`. `format` renders the
Java directives it knows with widths (`.kb/clojure-frontend.md`, the lowering table's
`format` row) but refuses every flag.

## What decides the design

- `java.util.Formatter`'s flags (`-`, `0`, `+`, space, `,`, `(`, `#`) and which conversions
  take each; the refusals in its words (`MissingFormatWidthException`,
  `FormatFlagsConversionMismatchException`, `IllegalFormatFlagsException` ...).
- The format string is a literal today; a computed one is a separate question.

## Plan

1. Measure on clj 1.12.6 each flag over `%d`/`%x`/`%o`/`%s`/`%f`/`%e`, with and without a
   width, and the refusals.
2. Render them on all four backends; re-probe instaparse.

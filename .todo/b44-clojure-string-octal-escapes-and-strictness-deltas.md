# Clojure: string octal escapes and strictness deltas vs the oracle

Follow-up from b41 (which made unknown string escapes signal like the oracle).
Measured on the host oracle `clj` 1.12.6.1673 during b41:

- The oracle ACCEPTS octal `\0`-`\7` (up to 3 digits, value capped at `\377`):
  `"\0"` is NUL, `"\77"` is `?`, `"\777"`/`"\400"` refuse with
  `Octal escape sequence must be in range [0, 377]`, and a digit `8`/`9` in the
  escape refuses with `Invalid digit: 8`. `ClojureReader.readString` has no octal
  arm, so `"\0"` now signals `Unsupported escape character` -- a refusal where the
  oracle reads. Add the arm (first digit `0`-`7`, up to two more `0`-`7`, range
  check, `Invalid digit` for `8`/`9`, mirroring the oracle messages).
- Strictness deltas the other way (we accept, the oracle refuses): `\'` reads as
  `'` here but signals `Unsupported escape character: \'` there. Decide whether
  to refuse it (like the oracle) or document it as a lenient superset (the `ns`
  unquoted-vector-spec precedent); either way pin it in `ClojureReaderTest`.
- Out of scope but adjacent: `\u` with non-hex digits leaks `NumberFormatException`
  instead of a positioned read error (the oracle says `Invalid unicode escape`),
  and a short `\u12` says `truncated \u escape` here vs the oracle's
  `Invalid character length: 2, should be: 4`.

## Acceptance

- `ClojureReaderTest` pins `"\0"`, `"\77"`, the `\400` range refusal, the
  `\8` digit refusal, and the `\'` decision.
- `.kb/clojure-frontend.md` strings row updated; docs `en`+`ja` if the `\'`
  decision is user-facing.

## Depends on

b41 (whose `readString` default arm this extends).

# Clojure: unknown string escapes duplicate the next character

Difficulty: Small (one `default` arm in the reader).

## Gap (found during b21, oracle `clj` 1.12.6.1673)

`ClojureReader.readString`'s escape `default` arm appends `peek()` -- the
character AFTER the consumed escape letter -- so `"a\qb"` reads as `"abb"`
instead of signalling. The oracle refuses unknown string escapes at read time
(`Illegal/unsupported escape sequence`). The b21 regex reader
(`readRegexSource`) already passes unknown escapes through verbatim for the
pattern parser, so only plain strings (and chars/chars-vectors built from
them) still corrupt.

## Acceptance

- `ClojureReaderTest`: `"a\q"` (and one case with a following char, pinning no
  duplication) signals; the standard escapes keep working.
- Audit `clojure-spec.yaml`/docs for any case relying on the lenient shape
  (none known); `.kb` row if a refusal is added.

## Depends on

b21 (whose `readRegexSource` documents the split).

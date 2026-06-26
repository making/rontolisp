# Deferred example: idiomatic text-analysis / word-frequency tool

**Status:** deferred until the prerequisite builtins below exist. Do NOT work
around the gaps with non-idiomatic code (per-character `subseq`+`string=`
scanning, eql-keyed alists for string keys, etc.) -- add the proper functions
first, then write the example idiomatically.

## What we wanted to add to `examples/`

A practical text/file-processing tool in the spirit of `wc` + a word-frequency
report: read a text file, tokenize each line into words, fold the words into a
frequency table, sort by count, and print the top entries. In Common Lisp this
is a few lines, but rontolisp is currently missing the pieces that make it
idiomatic.

What we DID ship instead (idiomatic with today's primitives, all three backends):
`examples/line-numbers.lisp` -- a `cat -n` style line/character counter that
needs only `with-open-file` / `read-line` / `length` / `format nil`.

## Blockers (each tracked as its own function-implementation todo)

- **Tokenization needs a character type / string indexing.** No `char`,
  `schar`, `char-code`, `code-char`, `#\Space` literals, nor a standard split.
  Today you can only pull one-character substrings via `subseq` and compare with
  `string=`, which is not how CL code is written. See
  [04-character-type-and-string-parsing](04-character-type-and-string-parsing.md).
- **Frequency counting needs hash tables (or string-keyed alists).** `assoc`
  ignores `:test`, so a string key never matches (`eql` on distinct strings),
  and there are no hash tables at all. See
  [05-hash-tables](05-hash-tables.md) and
  [06-sequence-test-key-keywords](06-sequence-test-key-keywords.md).
- **Numeric columns (CSV-style aggregation) need string->number parsing.** No
  `parse-integer` / `read-from-string`, and `read` takes no stream argument, so
  values read from a file cannot be turned into numbers. See
  [04-character-type-and-string-parsing](04-character-type-and-string-parsing.md).

## Definition of done

When the above land, add (and verify on interpreter / JVM / WASM, then add a
case to `ci-spec.yaml` if appropriate):

- `examples/word-count.lisp` -- line/word/char counts plus a sorted word
  frequency table, written the way a Common Lisp programmer would.

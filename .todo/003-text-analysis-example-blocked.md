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

- **Tokenization needs a character type / string indexing.** RESOLVED: `char`,
  `schar`, `char-code`, `code-char`, `#\Space` literals and the character
  predicates landed (the character/parsing work, formerly tracked here as
  `04`, is done; remaining parity polish is in
  [08-character-parsing-parity-followups](08-character-parsing-parity-followups.md)).
  A standard `split` is still absent, but tokenizing by indexing with `char` +
  the character predicates is now idiomatic.
- **Frequency counting needs hash tables (or string-keyed alists).** RESOLVED:
  hash tables landed on all three backends (`make-hash-table`, `gethash`,
  `(setf (gethash ...))`, `remhash`, `clrhash`, `hash-table-count`,
  `hash-table-p`, `maphash`), with structural (`equal`) keys -- so `(incf
  (gethash word counts 0))` works. The word-frequency example is now unblocked;
  string-keyed `assoc :test` (see
  [06-sequence-test-key-keywords](06-sequence-test-key-keywords.md)) remains a
  separate, lighter-weight alternative.
- **Numeric columns (CSV-style aggregation) need string->number parsing.**
  RESOLVED: `parse-integer`, `read-from-string` and `(read stream)` landed;
  `examples/parse-numbers.lisp` demonstrates numeric-column aggregation.

## Definition of done

When the above land, add (and verify on interpreter / JVM / WASM, then add a
case to `ci-spec.yaml` if appropriate):

- `examples/word-count.lisp` -- line/word/char counts plus a sorted word
  frequency table, written the way a Common Lisp programmer would.

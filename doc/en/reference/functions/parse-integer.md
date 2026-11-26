# parse-integer

`(parse-integer string &key start end radix junk-allowed)`

Parses an integer from a string, skipping surrounding whitespace. `:start`/`:end` bound the parsed region, `:radix` selects the base (default 10) and `:junk-allowed`, when non-nil, stops at the first non-digit and returns the integer parsed so far (or `nil` if none). Without `:junk-allowed`, a region that is no integer -- a non-whitespace character after the digits, no digit at all, an empty region -- signals a `parse-error`. A nil `:end` means the length. A `:radix` that is not an integer from 2 to 36 signals a `type-error` of `(INTEGER 2 36)` before anything is read, [`digit-char-p`](digit-char-p.md)'s, even for an empty string or with `:junk-allowed`. A bound that is negative, not an integer (a nil `:start` included) or past the string's length, or a start past its end, signals the `type-error` [`subseq`](subseq.md) signals for the same range. The second return value is the position where parsing stopped, ready to feed back into `:start`. The full keyword set and both values work identically on every backend; usable as a first-class value (`#'parse-integer`).

```lisp
(parse-integer "ff" :radix 16) ; => 255
```

```lisp
(multiple-value-bind (n pos) (parse-integer "42x" :junk-allowed t)
  (list n pos)) ; => (42 2)
```

`(parse-integer "42")` returns `42`, and `(parse-integer "x9x" :start 1 :end 2)` returns `9` by parsing only the bounded region.

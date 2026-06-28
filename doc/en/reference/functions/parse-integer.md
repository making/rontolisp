# parse-integer

`(parse-integer string &key radix junk-allowed)`

Parses an integer from a string, skipping surrounding whitespace. `:radix` selects the base (default 10) and `:junk-allowed`, when non-nil, stops at the first non-digit and returns the integer parsed so far (or `nil` if none). Without `:junk-allowed`, any trailing non-whitespace character is an error. Works on all three backends (the `:start`/`:end` keywords are interpreter-only, and on the compiled backends the keyword names must be literal); usable as a first-class value (`#'parse-integer`).

```lisp
(parse-integer "ff" :radix 16) ; => 255
```

`(parse-integer "42")` returns `42`, and `(parse-integer "12x" :junk-allowed t)` returns `12` by stopping at the `x`.

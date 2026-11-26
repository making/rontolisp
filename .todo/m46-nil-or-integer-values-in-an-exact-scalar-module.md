# m46. Nil-or-integer values in an exact scalar module

Difficulty: High

A Java reactor compiles `--no-gc` (`.kb/java-frontend.md`, "A reactor on `--no-gc`"). Its
module is exact (`.kb/no-gc-scalar-wasm.md`, "Exact mode"): the scalar backend's nil is the
number 0, so a test of a number is refused rather than read as zero-is-false. That refuses the
run time's Lisp written over a nil that is no 0, each by name today:

- `%java-substring`'s optional `end` (`(if end end (length s))`): `substring(int, int)`.
- `%java-string-compare`'s `found` (nil, then a difference): `compareTo`.
- `position` in `%java-double-string`: a `double` in a `+` (and `rational` in
  `%java-float-string`: a `float`'s text).
- `digit-char-p` in `%java-digit` (and the quoted list of `%java-unicode-digit`):
  `Integer.parseInt`.
- `indexOf`: the prelude's `search`, whose `&key` the backend refuses.

Decide how a value that is an integer or nil is held where 0 is nil: a nil-able integer slot
of its own representation (a sentinel the one integer it shadows traps on, or a flag beside
the value), found by the type inference (a slot nil and an integer flow into) -- measure what
it costs a module that has none (every older one must stay byte-identical) and what the five
above cost once made. Rewriting `java.lisp`'s idioms is the other road, but it changes the bytes
of every wasm-GC program that uses them.

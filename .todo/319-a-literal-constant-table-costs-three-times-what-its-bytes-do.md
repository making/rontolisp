# A literal constant table costs three times what its bytes do

Difficulty: Medium

`(coerce '(<all literal integers>) '(simple-array (unsigned-byte 32) (*)))` -- the way
every CL library spells a lookup table -- is built at run time, cons cell by cons cell,
at **~11.8 bytes of wasm per element**. Baked into a data segment as the packed array it
becomes, the same table is 4 bytes per element.

Measured at `--optimize=size`: a 3-element table is 11,943 B, the same program with 256
elements is 14,918 B, i.e. 2,975 B for 253 extra elements.

chipz carries roughly 700 such elements across `constants.lisp` (`+crc32-table+` at 256,
`*fixed-block-code-lengths*` at 288, the distance/length code tables, ...), which is most
of the zlib artifact's 13,760-byte top-level chunk -- worth about **-5 KB** on the zlib
size-report rows, and more on any library with bigger tables.

## Shape

The machinery to land on already exists at both ends:

- Packed rank-1 arrays with the `#N@(...)` literal already bake into data segments and
  have per-backend raw accessors (`.kb/packed-integer-vectors.md`), including the
  `deftype`-alias `:element-type` resolution a library's own `octet` alias needs.
- The fold is a form-level SOURCE rewrite of the kind `.kb/uax15-derived-tables.md`
  describes: recognise `(coerce '(literals...) '<packed array type>)` -- and the
  `make-array ... :initial-contents '(literals...)` spelling beside it -- and rewrite it
  to the literal. `PureBuiltinFolder` is the natural home only if the result can be a
  literal the emitters already bake; check `.kb/pure-builtin-fold.md`'s exclusion
  reasoning before widening the curated table (a fold that allocates is not in the same
  family as the arithmetic ones).

Constraints to respect: the fold must be element-type-exact (a value that does not fit
the declared width is the program's bug, not the folder's to mask), it must decline for
a non-literal element, and it must not fire where the array is MUTATED -- a baked data
segment is per-module, so two evaluations of one form must not share storage unless the
form is a `defconstant`/`defparameter` initialiser evaluated once. The safest first cut
is exactly the constant-table shape: a top-level `define-constant`/`defparameter` whose
initialiser is the literal coerce.

## Deliverable

Measured reductions in `size-report/results/wasm-flags.md` (the `zlib` rows) with the
row's check still gunzipping byte for byte, `./mvnw test` + native `CiSpecE2eTest` green,
and byte-identical output for programs with no literal table. Note in the `.kb` file
whether the fold changed the interpreter's behaviour at all -- it must not; a folded
table and a built one have to be `equalp` and identically mutable.

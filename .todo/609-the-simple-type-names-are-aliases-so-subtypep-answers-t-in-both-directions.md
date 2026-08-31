# The simple- type names are aliases, so subtypep answers T in both directions

Difficulty: Medium

Found while closing `.todo/608` (the compound `subtypep` reduction), which
expected to have to ADD `SIMPLE-VECTOR`/`SIMPLE-ARRAY`/`SIMPLE-STRING` edges to
`LispMacroExpander.SUBTYPEP_PARENTS` and found them already answering -- through
`canonicalSubtypeName`, which collapses the three names ONTO
`VECTOR`/`ARRAY`/`STRING`. A collapse is symmetric, and that is the bug.
Measured 2026-08-31, all four backends agreeing with each other and disagreeing
with SBCL 2.2.9 in one direction:

```lisp
(subtypep 'simple-vector 'vector)   ; here T   SBCL (T T)     -- right
(subtypep 'vector 'simple-vector)   ; here T   SBCL (NIL T)   -- WRONG
(subtypep 'array 'simple-array)     ; here T   SBCL (NIL T)   -- WRONG
(subtypep 'string 'simple-string)   ; here T   SBCL (NIL T)   -- see below
```

Why this is a real answer and not a one-representation alias like the float
family. The float names collapse legitimately because rontolisp has exactly ONE
float format (`.kb/declarations-type-checks.md`, "The four float type names are
ONE type"). Simplicity is NOT that kind of fact: since `.todo/604` rontolisp
DOES distinguish a simple array from a non-simple one -- `type-of` answers
`(SIMPLE-VECTOR 4)` for `(make-array 4)` and `(VECTOR T 4)` for
`(make-array 4 :fill-pointer 0)`, and `makeArrayTypeTest` reads the difference.
A lattice that says `vector <= simple-vector` therefore contradicts the very
specifier `type-of` builds.

`STRING` is the one that needs a decision rather than a fix: every string value
here is immutable, so `simple-string` and `string` may genuinely denote one type
(the float argument), and `(subtypep 'string 'simple-string)` answering `T` is
then correct. Decide it explicitly and write the reason down; do not carry the
current answer forward by accident.

Shape of the fix:

- Drop `SIMPLE-VECTOR` and `SIMPLE-ARRAY` from `canonicalSubtypeName` and give
  them `SUBTYPEP_PARENTS` entries instead (`SIMPLE-VECTOR -> SIMPLE-ARRAY,
  VECTOR`; `SIMPLE-ARRAY -> ARRAY`). The universe already lists both names, so
  the emitted `%subtypep-ancestor-table%` follows for free -- but its ROWS
  change, so re-run the byte-identity-sensitive rows.
- Settle `SIMPLE-STRING`/`SIMPLE-BASE-STRING` the same way or state why they
  stay collapsed.
- Check the reverse direction is not load-bearing anywhere: `subtypep` is a
  library-visible probe (sxql, postmodern's `eval-when` float probe, alexandria)
  and a `simple-` pair answering nil where it answered t can flip a branch.

Behavior must be identical on all four backends
(`.kb/declarations-type-checks.md` owns the lattice): rows in
`LispEvaluatorTest` + `JvmLispCompilerTest` + `WasmLispCompilerIntegrationTest`
and a ci-spec case, with SBCL 2.2.9's answers on the same program.

Sequencing: this must land AFTER `.todo/605` (shipped 2026-08-31), which made
`vectorp` and the atomic `vector`/`simple-vector` specifier rank-aware -- the
two changes are the same "simple/rank facts the lattice must not lie about"
mechanism seen from the `typep` and the `subtypep` side.

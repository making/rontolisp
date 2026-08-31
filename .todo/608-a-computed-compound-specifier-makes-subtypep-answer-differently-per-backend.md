# A computed compound specifier makes subtypep answer differently per backend

Difficulty: Medium

Fell out of `.todo/606`, which taught the runtime `typep` dispatch to read a
COMPOUND specifier out of the specifier value. `subtypep` takes the same
computed specifiers and did not move -- and unlike the `typep` gap, this one is
already a cross-backend DIVERGENCE, not a uniform nil. Measured 2026-08-31:

```lisp
(defun st (a b) (subtypep a b))
(st '(or fixnum ratio) 'number)   ; interpreter: T   JVM/WASM: NIL   SBCL: T
(st '(integer 0 10) 'integer)     ; every backend:  NIL             SBCL: T
(subtypep '(integer 0 10) 'integer) ; literal, folded: NIL          SBCL: T
```

Two halves, and they have to move together or the divergence just changes
shape:

- **The static half**, `LispMacroExpander.subtypep(LispVal, LispVal,
  ClosRegistry)` -- the interpreter's `subtypep` builtin calls it directly and
  the compilers fold a literal pair through it. It handles `(or ...)` on either
  side and NOTHING else: every other compound specifier answers false, so even
  the folded literal `(subtypep '(integer 0 10) 'integer)` is nil.
- **The runtime half**, `expandRuntimeSubtypep` / `%subtypep-runtime`, which
  scans the `%subtypep-ancestor-table%` by type NAME. A cons matches no entry,
  which is why the interpreter's `or` handling has no compiled twin.

Shape of the fix. A RESTRICTING compound specifier is a subtype of its own
head -- `(integer 0 10)` <= `integer`, `(simple-array t (2 2))` <= `array`,
`(string 2)` <= `string` -- so a compound SUB whose head is not a logical
connective can reduce to its head and re-test; that is sound and closes the
common probe. `(and ...)` as the sub is a subtype when ANY conjunct is; `(and
...)` as the SUPER when EVERY conjunct is. `(not ...)`/`(member ...)`/`(eql
...)`/`(satisfies ...)` stay unknown (nil), which the lite single-value
`subtypep` is allowed to answer. Do the reduction ONCE and route both halves
through it -- the runtime defun needs the same head-reduction over a runtime
value, the way `%typep-compound-runtime` reads a specifier value.

Watch the two lattice edges the reduction exposes but does not add:
`SUBTYPEP_PARENTS` has no `SIMPLE-VECTOR`/`SIMPLE-ARRAY`/`SIMPLE-STRING`
entries, so `(simple-vector 4)` reduces to `simple-vector` and still answers
nil against `vector`. Add them in the same pass or the array half of the
reduction buys nothing.

Behavior must be identical on all four backends
(`.kb/declarations-type-checks.md` owns the lattice and names the pinning
tests): rows in `LispEvaluatorTest` + `JvmLispCompilerTest` +
`WasmLispCompilerIntegrationTest` and a ci-spec case. `doc/*/reference/
functions/subtypep.md` currently DOCUMENTS the divergence -- that paragraph
comes out when this lands.

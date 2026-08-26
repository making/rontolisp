# An `equalp` hash table is only `equalp` on the interpreter

Difficulty: High

`(make-hash-table :test 'equalp)` places its keys by an `equalp` FOLD on the
interpreter since the cl-unicode work (`LispEquality.equalpKey`, applied by
`LispHashTable` before the key reaches the structural map), so `"CS"` and `"Cs"`
are one key. The JVM and both WASM backends still place every key by the plain
`equal` hash, so there they are two. That is a cross-backend divergence in a
topic whose `.kb` file (`.kb/hash-tables.md`) says all four must agree, and it
is here only because nothing that needs the fold could reach them: a
cl-unicode-sized program does not fit a `.class` at all (`.todo/545`).

**WASM, measured 2026-08-26** (wasmtime 47): `(ql:quickload "str")` compiles to a
6.7 MB Preview 1 module and then dies inside cl-unicode's load with
`Unhandled condition: Unknown property name "Cs".` -- `derived.lisp` looks up
`"Cs"` in `*property-map*`, an `equalp` table holding `"CS"` (the bidi class
registers after the general category in `UnicodeData.txt` order, and
`*canonical-names*` is last-wins per symbol; upstream lands there too). So this
item is what stands between the WASM backends and cl-unicode, and it is the
first of the two things to fix there.

This is the WIDENING half of the `:test` story; `.todo/012` is the NARROWING
half (an `eql` table still matching structurally). They want the same
machinery -- a table that knows its test -- and should probably land together.

## Why a fold rather than a second hash/compare pair

`equalp` on two values is `equal` on their folds, so one structural table
carries both tests and every backend's existing `_hash`/`_equal` pair is
untouched. A second pair would have to be written, kept in step with the first,
and tree-shaken separately on WASM.

The fold that exists (interpreter): a string and a character fold to upper case,
a number to its exact rational value (so `1`, `1.0` and `2/2` are one key), a
cons folds element-wise. An ARRAY key deliberately does not fold: `equal` on a
vector is identity on every backend, so a folded copy would never find itself.
That is a real deviation from ANSI `equalp` tables and belongs in the `.kb`
entry when this lands.

## What to implement

Each backend needs (a) the table to carry its test and (b) the fold applied on
`gethash`/`puthash`/`remhash` before the key is hashed.

- **JVM** (`JvmHashRuntimeBuilder`, `runtime/RontoHashTable`): the fold itself
  is plain Java over the JVM value model (a Lisp string is a Java `String` with
  framing quotes, a character an `int[]{cp}`, a cons an `Object[]`), so it
  belongs in `runtime/RontoHashTable` -- which TRAVELS with compiled output, so
  the class list in `.kb/jvm-export.md` needs nothing new but the method does
  need to import nothing. The flag can hang off a second reserved map key
  beside `#order`. `_hashGet`/`_hashPut`/`_hashRem` branch on it.
- **WASM** (`WasmHashTableCompiler`): the table is a `TYPE_CELL` holding a
  header `cons (count . buckets)`; the flag has to live in that header without
  breaking `hash-table-p`, which discriminates a table from a general array by
  the header car being an i31. Folding the key needs `_string_upcase` /
  `_char_upcase` (`WasmCaseFoldRuntimeBuilder`) FORCED into a module that uses
  an equalp table, since both are tree-shaken.
- **Printing / `hash-table-test`**: `.kb/hash-tables.md` pins both to the
  constant `EQUAL` and says explicitly to print the real test only once the
  table learns it and lookup honors it. This is that moment: the printer's
  three sites, the `hash-table-print-syntax` ci-spec case and
  `LispMacroExpander.expandHashTableTest` all change with it.

## Definition of done

```lisp
(let ((h (make-hash-table :test 'equalp)))
  (setf (gethash "CS" h) 1)
  (list (gethash "Cs" h) (hash-table-count h)))   ; => (1 1)
```
byte-identical on all four backends, plus a `src/test/resources/ci-spec.yaml`
case and the `.kb/hash-tables.md` rewrite above.

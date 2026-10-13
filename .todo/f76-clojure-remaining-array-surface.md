# f76. Clojure: remaining array surface (`to-array-2d`, `aset-*`, `booleans`/`ints`/… casts)

Difficulty: Easy

Found while closing f57 (2026-10-13): the typed-array work covered
`object-array`/`to-array`/`into-array` and the eight `x-array` constructors,
but these neighboring core names are still `unknown name` on every backend:

| program | clj 1.12.6 | here |
|---|---|---|
| `(to-array-2d [[1 2] [3]])` | `Object[]` of arrays | `unknown name` |
| `(aset-int a i v)`, `aset-long`, `aset-double`, `aset-float`, `aset-short`, `aset-byte` (done), `aset-char`, `aset-boolean` | the oracle's checked stores | `unknown name` (except `aset-byte`) |
| `(ints xs)`, `longs`, `doubles`, `floats`, `shorts`, `bytes` (done via `bytes`), `chars`, `booleans` | casts to the primitive array | `unknown name` (except `bytes`) |

## What decides the design

- The f57 decision stands: typed arrays are vectors with the oracle's member
  coercions (`.kb/clojure-frontend.md` "Typed arrays"). `to-array-2d` is a
  vector of vectors; the `aset-*` verbs are the checked stores over
  `%clojure-aset`'s vector arm (the oracle refuses a mistyped value with `No
  matching method aset-<t> found taking 3 args`); the `*-casts` narrow like
  the `x-array` member casts.
- `into-array`'s `X/TYPE` converter matrix (f57's oracle probes) already pins
  most of the per-type semantics to reuse.

## Plan

1. clojure-spec rows, oracle-identical, four backends.
2. User docs en + ja.

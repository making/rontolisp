# b67. `(scheme complex)`: complex numbers in Scheme

Difficulty: Medium

The last missing R7RS-small library besides `(scheme load)` (b68). The core pipeline
already has complex numbers end to end -- the `RontoComplex` holder, the gated `_c*`
emitter group on the JVM, the tagged `TYPE_COMPLEX` struct and runtime group on wasm
(`.kb/jvm-complex.md`, `.kb/wasm-complex.md`, `.kb/transcendentals.md`) -- but the
Scheme front end refuses every result Common Lisp would answer as a complex through
`%scheme-no-complex` (`.kb/scheme-frontend.md`, "(scheme inexact)"). The deviation
list says "There are no complex numbers".

Scope:

- Reader/printer: `#C(r i)` external representation both ways; a complex literal where
  the imaginary part is 0 is the real (R7RS 6.2.4).
- Numeric tower: `exact`/`inexact` over complex, `=`,`+`,`-`,`*`,`/`, `magnitude`,
  `angle`, `real-part`/`imag-part`, `make-rectangular`/`make-polar`, `number?` true of
  a complex.
- The `(scheme inexact)` transcendentals stop refusing: `sqrt` of a negative real,
 `log` of a non-positive, `exp`/`sin`/`cos`/`tan`/`asin`/`acos`/`atan` of a complex,
 answering the principal branch (CL's, which is R7RS's).
- Remove the `%scheme-no-complex` refusals and the "There are no complex numbers"
 deviation from `doc/*/scheme/deviations.md`; add `(scheme complex)` to
 `IMPORTABLE_LIBRARIES` and the libraries doc table.
- Watch the never-float-an-exact-value trap and the wasm type-test fold: a program
 that makes no complex must stay byte-identical or lose only what the fold drops
 (`.kb/scheme-frontend.md` measurements section, `.kb/wasm-ref-type-fold.md`).

## Oracle

Gauche 0.9.15, as everywhere in this front end:

```console
$ gosh -r7 -e '(display (sqrt -4))(newline)(display #C(1 2))(newline)'
#C(0 2)... (gosh prints 0+2i; pin OUR spelling, note the difference in deviations.md)
```

Pin: read-print round trip, arithmetic, sqrt/log of negatives, `equal?`/`eqv?` on
complexes, the byte-identity of a complex-free program.

## Acceptance

All four backends via a `scheme-spec.yaml` case (`complex-numbers-...`), plus
`SchemeBuiltinsTest` entries and the byte-identity check on `hello.scm` and one
numeric program.

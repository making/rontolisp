# `concatenate` to a `(vector (unsigned-byte 8))` returns a GENERAL vector

Difficulty: Medium

This is what fails 7 of 13 `ClPostgresE2eTest` legs (the test is opt-in behind
`RONTOLISP_POSTGRES_E2E=1`, so `./mvnw test` skips it and stays green). It is
pre-existing on `develop` -- verified by stashing local work and re-running the
baseline -- and it is NOT environmental, which is what the failure first looked
like.

## Repro (no server needed)

```lisp
(let* ((a (make-array 2 :element-type '(unsigned-byte 8) :initial-contents '(1 2)))
       (b (make-array 2 :element-type '(unsigned-byte 8) :initial-contents '(3 4)))
       (c (concatenate '(vector (unsigned-byte 8)) a b))
       (d (concatenate '(vector (unsigned-byte 8) *) a b)))
  (print (list :a-elt (array-element-type a) :a-typep (typep a '(simple-array (unsigned-byte 8) (*)))))
  (print (list :c-elt (array-element-type c) :c-typep (typep c '(simple-array (unsigned-byte 8) (*)))))
  (print (list :d-elt (array-element-type d) :d-typep (typep d '(simple-array (unsigned-byte 8) (*))))))
```

```
(:A-ELT (UNSIGNED-BYTE 8) :A-TYPEP T)     ; make-array is right
(:C-ELT T :C-TYPEP NIL)                   ; concatenate is not
(:D-ELT T :D-TYPEP NIL)
```

**Identical on all four backends** (interpreter, JVM, wasm-GC Preview 1,
`--component`), so the defect is in the shared result-type normalizer
(`compiler/ConcatenateForms`, `.kb/concatenate-result-families.md`) plus the
interpreter's own `concatenate`, not in one backend. The `*` in
`(vector (unsigned-byte 8) *)` is a red herring: both spellings lose it.

ANSI requires the result to be of the specified type, so `array-element-type`
must answer `(unsigned-byte 8)` and the value must satisfy
`(simple-array (unsigned-byte 8) (*))` -- the packed representation
`.kb/packed-integer-vectors.md` already gives `make-array`.

## How it reaches cl-postgres

`cl-postgres/messages.lisp:78 md5-password` builds the md5 auth response:

```lisp
(pass2 (md5-and-hex (concatenate '(vector (unsigned-byte 8) *)
                                 (enc-string-bytes pass1) salt)))
```

36 bytes (32 hex chars + the server's 4-byte salt) go into `md5:md5sum-sequence`,
whose `md5.lisp:554` dispatch is

```lisp
(etypecase sequence
  ((simple-array (unsigned-byte 8) (*)) ...)
  (simple-string ...))
```

Neither arm matches a general vector, so the probe dies with

```
ETYPECASE: no clause matches #(55 48 52 97 ... 203 16 178 242)
```

The `trust` and `password` rungs pass (they never hash), which is why the failure
looks like an auth-configuration problem rather than a sequence-type one.

## Failing legs

`authLadderOnTheInterpreter`, `authLadderOnJvm`, `authLadderOnWasmComponent`,
`scramAuthOnWasmComponent`, `crudOnWasmComponent`, `unicodeTextOnWasmComponent`,
`failsToCompileOnWasmPreview1`. The three wasm-component ones report
`unknown handle index 0` under wasmtime 47.0.2 rather than the etypecase error --
**confirm whether that is this bug reaching the socket layer after a desynced
connection, or a second, independent defect**, before assuming one fix closes all
seven. The interpreter and JVM legs are unambiguous.

## Acceptance

- The repro above answers `(UNSIGNED-BYTE 8)` / `T` on all four backends.
- A ci-spec case pins it (this is exactly the cross-backend shape ci-spec is for).
- `RONTOLISP_POSTGRES_E2E=1 ./mvnw -Dtest=ClPostgresE2eTest test` is green, or the
  remaining wasm-component failures are split into their own item with their own
  diagnosis.
- `.kb/concatenate-result-families.md` gains the packed-integer result family and
  names the pinning test.

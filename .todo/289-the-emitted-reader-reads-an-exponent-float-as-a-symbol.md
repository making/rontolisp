# The emitted reader reads an exponent-notation float as a SYMBOL

Difficulty: Low

Found 2026-08-08 while probing float printing. The compiled-in reader -- the one
behind runtime `read` / `read-from-string` / `load` on BOTH compile paths -- does
not recognise the exponent marker in a float token. It falls through to "not a
number" and interns the token as a symbol, so the value is a symbol whose name
happens to look like a float.

```console
$ cat rd.lisp
(dolist (s (list "1e-8" "1.5" "1.0e10" "1E5" "2e3" "1d-8"))
  (let ((v (read-from-string s)))
    (princ s) (princ " -> ") (princ v)
    (princ " floatp=") (princ (floatp v))
    (princ " symbolp=") (princ (symbolp v))
    (terpri)))

$ rontolisp rd.lisp                     # interpreter
1e-8 -> 1.0E-8 floatp=T symbolp=NIL
1.5 -> 1.5 floatp=T symbolp=NIL
1.0e10 -> 1.0E10 floatp=T symbolp=NIL
1E5 -> 100000.0 floatp=T symbolp=NIL
2e3 -> 2000.0 floatp=T symbolp=NIL
1d-8 -> 1.0E-8 floatp=T symbolp=NIL

$ rontolisp rd.lisp -o rd.wasm --optimize && wasmtime run -W gc rd.wasm
1e-8 -> 1E-8 floatp=NIL symbolp=T          <-- a SYMBOL
1.5 -> 1.5 floatp=T symbolp=NIL            <-- the no-exponent form is fine
1.0e10 -> 1.0E10 floatp=NIL symbolp=T
1E5 -> 1E5 floatp=NIL symbolp=T
2e3 -> 2E3 floatp=NIL symbolp=T
1d-8 -> 1D-8 floatp=NIL symbolp=T
```

The JVM backend answers exactly the same thing, so this is the emitted reader,
not a WASM-specific gap: the frontend `LispReader` (which reads the SOURCE) has
no such problem, and a `1e-8` written literally in a program compiles fine.

## Why it matters beyond `read-from-string`

- Arithmetic on the value TRAPS rather than erring usefully:
  `(let ((v (read-from-string "1e-8"))) (* v 1.0))` is a `cast failure` trap on
  wasm-GC (the f64 coercion `ref.cast`s a symbol), and a class cast on the JVM.
- `load` of a data file and `read` from a stream see the same tokens, so any
  program that reads scientific-notation numbers at run time (a CSV of
  measurements, a JSON-ish config read with `read`) silently gets symbols on the
  compile paths and numbers on the interpreter. `rontolisp:json-parse` has its
  own number scanner and is unaffected.

## Where to fix

`.kb/emitted-reader-narrow-subset.md` records that the compiled-in reader reads
the frontend's full dispatch set; this is a token-level gap under that. The
number scanner lives in `WasmReadRuntimeBuilder` / `JvmEvalRuntimeBuilder`'s
reader half -- it accepts digits, a sign and one `.`, and stops. It needs the
`e`/`E`/`d`/`D`/`s`/`S`/`f`/`F`/`l`/`L` exponent markers with an optional signed
integer exponent, and must keep rejecting a token that is NOT a valid float
(`1e`, `1e+`, `e5`, `1.2.3`) so those stay symbols.

## Done when

- The probe above prints `floatp=T symbolp=NIL` on all four backends for every
  exponent spelling the frontend reader accepts, and still interns `1e`, `e5`
  and `1.2.3` as symbols.
- A ci-spec case covers it (the value AND `floatp`, not just the printed text --
  the printed text of a symbol and of a float can coincide).
- The printed SHAPE divergence stays out of scope: `(princ 1e-8)` prints `0.0`
  on wasm even for a properly parsed float, which is `.todo/046`.

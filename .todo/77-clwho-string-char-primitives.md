# 77: cl-who unit 1 -- string/char/sequence primitives batch

Parent: `.todo/76`. First unit (leaf builtins, no dependencies on the other
units). One session: implement on all four backends + tests + docs + native
E2E.

## Scope -- the primitives cl-who needs that are missing today

Verified missing by probe on the native jar (2026-07-06):

1. **`make-string`** -- `(make-string n &key initial-element element-type)`.
   `initial-element` defaults to an implementation-defined char; cl-who passes
   `#\Newline`, `#\Space`, and a bare `(make-string total-size)`. `element-type`
   (`'base-char`/`'character`/`'lw:simple-char`) is parsed and ignored (single
   string representation). Used by specials.lisp (`+newline+`, `+spaces+`) and
   `string-list-to-string`.
2. **`replace`** -- `(replace seq1 seq2 &key start1 end1 start2 end2)`,
   destructive copy into seq1, returns seq1. Must be string-aware (cl-who uses
   `(replace result-string string :start1 curr-pos)`). List support is a bonus,
   not required by cl-who.
3. **`write-sequence`** -- `(write-sequence seq stream &key start end)`. Today
   it only handles arrays (`aref expects an array`); make it accept strings and
   honor `:start`/`:end`, writing to the stream like `write-string`. cl-who's
   `escape-string` relies on it.
4. **`lower-case-p`**, **`upper-case-p`** -- char predicates (`alpha-char-p`
   already exists; mirror it). Used by `same-case-p`.
5. **`constantp`** -- `(constantp form)`; true for self-evaluating objects
   (numbers, strings, characters, keywords, `t`/`nil`) and `(quote x)` forms.
   A lite version is fine (cl-who uses it to decide compile-time vs runtime
   attribute emission; false-negatives just push work to runtime). Its
   argument may be any AST form.
6. **`streamp`** + the **`stream` type-specifier** in `check-type`/`typecase`
   (`makeTypeTest`). `with-html-output` expands to `(check-type ,var stream)`;
   a string-stream / file-stream handle must satisfy `streamp`. Add `streamp`
   as a real builtin and wire `stream` into `makeTypeTest`
   (`.kb/declarations-type-checks.md`).

## Backends & wiring

Follow "Adding a New Built-in Function" in CLAUDE.md for each of `make-string`,
`replace`, `write-sequence`, `lower-case-p`, `upper-case-p`, `constantp`,
`streamp`:

- `LispNames` + `PackageRegistry.CL_SYMBOLS` (some names already present -- add
  the rest).
- `Environment.createGlobal()` (interpreter).
- `Jvm<Name>Compiler` + `JvmExprCompiler.compileCons()`;
  `Wasm<Name>Compiler` + `WasmExprCompiler.compileCons()`.
- `BuiltinFunctionWrappers` entry for first-class use.
- `streamp`/`stream`: also `LispMacroExpander.makeTypeTest`.

`write-sequence`/`replace`/`make-string` touch the string representation, which
differs per backend (UTF-16 interpreter/JVM, byte-indexed WASM) -- mirror the
existing string builtins (`subseq`/`char`/`concatenate`) per backend. `--no-gc`
scalar backend: only what is cheap; a clear compile error otherwise is fine
(cl-who never targets `--no-gc`).

## Acceptance

All green on interpreter, JVM, WASM Preview 1, WASM component:

```lisp
(make-string 3 :initial-element #\x)                      ; => "xxx"
(replace (make-string 5 :initial-element #\a) "XY" :start1 1) ; => "aXYaa"
(with-output-to-string (s) (write-sequence "abcd" s :start 1 :end 3)) ; => "bc"
(list (lower-case-p #\a) (upper-case-p #\A) (constantp 5) (constantp 'x)) ; => (T T T NIL)
(with-output-to-string (s) (check-type s stream) (write-string "ok" s)) ; => "ok"
```

Add a `ci-spec.yaml` case; run native `CiSpecE2eTest`. Add per-operator doc
pages + `_catalog.yaml` entries and run the `-Drontolisp.doc.fix=true` helper.

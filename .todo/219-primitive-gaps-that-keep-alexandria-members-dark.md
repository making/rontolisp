# The four primitives that keep part of alexandria's API dark

Found while making alexandria a first-class loadable system (2026-07-30,
`.kb/asdf.md`'s alexandria entry). Each item is a **missing CL primitive**, not
an alexandria problem: alexandria is just the first loadable library that calls
all four. Every one of them fails identically on all four backends, so none is a
backend divergence -- they are conformance gaps.

The four, with the alexandria member each one keeps dark:

| Primitive gap | What CL says | Dark today |
| --- | --- | --- |
| `coerce` to a **computed** result type | any type specifier | `copy-sequence`, `median` (and `coercef`) |
| `(last list n)` -- the count argument | `last list &optional n` | `rotate` |
| `every`/`some`/`notany`/`notevery` over **several** sequences | `every pred &rest sequences` | `alexandria-2:dim-in-bounds-p`, `row-major-index`, `rmajor-to-indices` |
| `read-sequence` into a **character** buffer | fills any sequence from a character stream | `read-stream-content-into-string`, `read-file-into-string` |

## Reproductions

```lisp
(coerce #(1 2) 'list)                  ; => (1 2)      -- literal type: fine
(defun cs (type seq) (coerce seq type))
(cs 'list #(1 2))
;; LispEvalException: coerce: a computed result type must be a float type, got LIST
;; (identical on the JVM and both WASM backends -- the runtime coerce is float-only)

(last '(1 2 3) 2)                      ; LAST expects 1 arguments, got 2
(every #'< '(1 2) '(3 4))              ; EVERY expects 2 arguments, got 3
(with-input-from-string (s "abc")      ; alexandria:read-stream-content-into-string
  (let ((buf (make-array 8 :element-type 'character)))
    (read-sequence buf s)))            ; READ-BYTE expects a binary input stream
```

`read-file-into-string` is the one with a caller waiting on it beyond
alexandria: postmodern's `execute-file` wants it (`.kb/asdf.md`, the uiop
subset note).

## Notes per item

- **`coerce` with a computed type** is the widest of the four and the one to
  think about first: `concatenate` already re-does its family dispatch at run
  time in `BuiltinFunctionWrappers.concatenateWrapper` (`member` over the
  designator's head, `.kb/concatenate-result-families.md`), so the shape of a
  runtime type dispatch is settled and the sequence families are the same set.
  The float-only restriction is what the error message announces, so widening it
  is additive.
- **`(last list n)`** is `expandLast` in `LispMacroExpander` plus the
  `Environment` built-in plus the wrapper (`unary(LAST)` today -- it becomes the
  `binaryOptionalThird` shape, i.e. an optional count).
- **multi-sequence `every`/`some`** needs the interpreter's built-ins widened
  first (they hard-check arity), then `expandEvery`'s loop generalized to walk N
  sequences in lockstep. `notany`/`notevery` ride along.
- **`read-sequence` on a character stream** is the I/O one: the buffer's element
  type decides, and `(make-array n :element-type 'character)` has to be
  distinguishable from a general vector for the dispatch to work. Check
  `.kb/read-load-streams.md` before starting.

## Deliberately not in this item

- `subtypep`'s missing secondary value (blocks `alexandria:type=`) -- that is
  `.todo/214`'s inventory, and `.todo/213` decides the channel first.
- `intern` with a runtime package designator and `symbol-function` with a
  runtime name (they make `format-symbol`/`ensure-symbol`/`ensure-function` on a
  symbol compile-backend errors). Those are loud, and they are the symbol-model
  redesign's territory (`.todo/156`).
- Multi-list `mapc`/`mapcan`/`maplist`/`mapcon` -- `.todo/218`.

## Verification

Per item: the interpreter -> JVM -> WASM order, a case in each backend's test,
a `ci-spec.yaml` line (native E2E re-run), then the alexandria row in
`doc/*/guides/asdf-systems.md` loses that member from its limitation list and the
demo (`examples/asdf/alexandria-demo.lisp`) plus `AlexandriaE2eTest` gain it --
they are one text, kept in sync.

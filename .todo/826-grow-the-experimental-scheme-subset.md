# Grow the experimental Scheme subset: the follow-ups `.todo/825` named

Difficulty: High

`.todo/825` landed the minimal front end (`.kb/scheme-frontend.md`): reader, lowering to
core forms, `(scheme base)` / `(scheme write)` subset, all four backends. It refused the
rest by name. Each entry below is independent; split one off into its own item when it
is picked up. Every one of them must keep the invariant: lower to core forms, change no
backend, add its cases to `scheme-spec.yaml`.

| feature | lowers onto | note |
|---|---|---|
| `define-syntax` / `syntax-rules` | an expander in `SchemeLowering`, before desugaring | needs a shadow-aware walk like `substituteSymbolMacros`; hygiene for the introduced core keywords already exists (`CORE_*` identity symbols) |
| `guard` / `raise` / `error-object?` / `error-object-message` | `handler-case` / `error` / a condition's report | compiles in EH mode (`.kb/error-handling.md`). Until then an `error` can only end the program |
| `parameterize` / `make-parameter` | the special-`let` restore (`.kb/dynamic-special-variables.md`) | a parameter object is a procedure: needs a first-class handle on a special |
| `case-lambda` | a `&rest` lambda dispatching on `(length args)` | |
| `delay` / `force` / `make-promise` | a record with a thunk | |
| bytevectors | the `(unsigned-byte 8)` pack (`.kb/packed-integer-vectors.md`) | reader `#u8(` is refused today |
| ports (`current-output-port`, string ports, `read-line`, `read-char`) | `%STREAM` instances (`.kb/read-load-streams.md`) | `display`/`write` take one argument today; the two-argument form is an arity error |
| `(scheme char)`, `(scheme cxr)`, `(scheme inexact)`, `(scheme lazy)` | table rows in `SchemeBuiltins` with their own `library` tag | `importSet` already keys visibility on the tag |
| `define-library` / `include` | per-file lowering gets a library scope | cross-file references are by convention today: an unknown name is a direct call / a variable |
| internal `define-record-type` | needs a non-top-level `defstruct`, which the compile path refuses (`.kb/defstruct.md`) | |
| `|...|` identifiers, `+inf.0` / `+nan.0`, `#!fold-case` | reader | check that every backend PRINTS infinities the same way first |

Not a language feature, same area:

- **The playground has no language pick.** A `.scm` reaches it only through `(load ...)` of
  an uploaded file; the REPL and the compile buttons read Common Lisp
  (`RontoPlayground.evalLine` / `frontend`). The doc site's Scheme fences are static for
  the same reason (`DocExamplesTest` verifies them; `RunnableBlockTransformer` runs `lisp`
  only). A whole-file lowering does not fit a form-at-a-time REPL: decide whether the
  REPL keeps the defun-or-variable pre-scan's state across forms or lowers every
  top-level procedure to a variable there.
- **The generic printer costs 58.8 KB of `.class`** (7.1 KB of wasm). Measured pieces on
  the JVM: `aref` 14 KB, a `do` loop 9 KB, `char` 7 KB, `write-char` 6 KB. That is the JVM
  backend's per-feature runtime, not the printer's shape -- look there, not in
  `scheme.lisp`.
- Proper tail calls beyond loops (mutual recursion overflows at 5,000 on the JVM). A
  trampoline would tax every call; measure before proposing one.
- `(-)` on the interpreter reports `Index 0 out of bounds for length 0`; the compile path
  says `- requires at least one argument` (`compiler/ArithmeticIdentities`).

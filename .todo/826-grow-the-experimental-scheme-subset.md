# Grow the experimental Scheme subset: the follow-ups `.todo/825` named

Difficulty: High

`.todo/825` landed the minimal front end (`.kb/scheme-frontend.md`): reader, lowering to
core forms, `(scheme base)` / `(scheme write)` subset, all four backends. It refused the
rest by name. Each entry below is independent; split one off into its own item when it
is picked up. Every one of them must keep the invariant: lower to core forms, change no
backend, add its cases to `scheme-spec.yaml`.

| feature | lowers onto | note |
|---|---|---|
| `define-syntax` / `syntax-rules` | an expander in `SchemeLowering`, before desugaring | needs a shadow-aware walk like `substituteSymbolMacros`; hygiene for the introduced core keywords already exists (`CORE_*` identity symbols). Done in `.todo/861` (limits: `.kb/scheme-frontend.md`, "Macros") |
| `guard` / `raise` / `error-object?` / `error-object-message` | `handler-case` / `error` / a condition's report | compiles in EH mode (`.kb/error-handling.md`). Done in `.todo/865` (deviations: `.kb/scheme-frontend.md`, "Exceptions") |
| `parameterize` / `make-parameter` | the special-`let` restore (`.kb/dynamic-special-variables.md`) | a parameter object is a procedure: needs a first-class handle on a special. Done in `.todo/867` (deviations: `.kb/scheme-frontend.md`, "Parameters") |
| `case-lambda` | a `&rest` lambda dispatching on `(length args)` | Done in `.todo/869` (deviations: `.kb/scheme-frontend.md`, "`case-lambda`"; static clause dispatch: `.todo/870`) |
| `delay` / `force` / `make-promise` | a record with a thunk | done in `.todo/831` (with `cons-stream` and the stream procedures) |
| bytevectors | the `(unsigned-byte 8)` pack (`.kb/packed-integer-vectors.md`) | Done in `.todo/871` (`.kb/scheme-frontend.md`, "Bytevectors"; binary ports stay with the ports row) |
| ports (`current-output-port`, string ports, bytevector ports and `read-u8`/`write-u8`, `read-line`, `read-char`) | `%STREAM` instances (`.kb/read-load-streams.md`) | `display`/`write` take one argument today; the two-argument form is an arity error. `read` from the current input port is split off: `.todo/832`. Done in `.todo/873` (`.kb/scheme-frontend.md`, "Ports"; file ports -- `(scheme file)` -- are `.todo/874`) |
| `(scheme char)`, `(scheme cxr)`, `(scheme inexact)`, `(scheme lazy)` | table rows in `SchemeBuiltins` with their own `library` tag | `importSet` already keys visibility on the tag. Split off: `(scheme cxr)` -> `.todo/829`, `(scheme inexact)` -> `.todo/830`, `(scheme lazy)` -> `.todo/831` (those three done), `(scheme char)` -> `.todo/879` (all four done; `(scheme char)`: `.kb/scheme-frontend.md`, "`(scheme char)`") |
| `define-library` / `include` | per-file lowering gets a library scope | Done in `.todo/882` (`.kb/scheme-frontend.md`, "Libraries and include"); exporting syntax from a library is `.todo/883` |
| `cond-expand` (top level and as a library declaration) | a pre-pass beside `include`'s, over a feature list (`r7rs`, `rontolisp`, `(library ...)`, `and`/`or`/`not`, `else`) | refused by name today, in a program and in a `define-library` |
| internal `define-record-type` | needs a non-top-level `defstruct`, which the compile path refuses (`.kb/defstruct.md`) | |
| `|...|` identifiers, `+inf.0` / `+nan.0` | reader | check that every backend PRINTS infinities the same way first |

Driven by a corpus rather than by the report: `.todo/828` (the SICP sample corpus) and its
items `.todo/829` .. `.todo/837`; `eval` is `.todo/833`.

Not a language feature, same area:

- **The playground has no language pick.** A `.scm` reaches it only through `(load ...)` of
  an uploaded file; the REPL and the compile buttons read Common Lisp
  (`RontoPlayground.evalLine` / `frontend`). The doc site's Scheme fences are static for
  the same reason (`DocExamplesTest` verifies them; `RunnableBlockTransformer` runs `lisp`
  only). The form-at-a-time lowering is solved: the CLI REPL reads through
  `eval/SourceSession` (`.kb/scheme-frontend.md`, "A session"). What is left here is the
  pick itself and `evalLine` taking that seam -- it echoes only the LAST form, so it
  needs `Step.echoes` of the last step, not `ReplBuffer`'s loop.
- **The generic printer costs 58.8 KB of `.class`** (7.1 KB of wasm). Measured pieces on
  the JVM: `aref` 14 KB, a `do` loop 9 KB, `char` 7 KB, `write-char` 6 KB. That is the JVM
  backend's per-feature runtime, not the printer's shape -- look there, not in
  `scheme.lisp`.
- Proper tail calls beyond loops (mutual recursion overflows at 5,000 on the JVM). A
  trampoline would tax every call; measure before proposing one.
- `(-)` on the interpreter reports `Index 0 out of bounds for length 0`; the compile path
  says `- requires at least one argument` (`compiler/ArithmeticIdentities`).

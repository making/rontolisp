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
| `cond-expand` (top level and as a library declaration) | a pre-pass beside `include`'s, over a feature list (`r7rs`, `rontolisp`, `(library ...)`, `and`/`or`/`not`, `else`) | Done in `.todo/892` (`.kb/scheme-frontend.md`, "`cond-expand`"; also `(features)`, and `eval` takes one) |
| internal `define-record-type` | needs a non-top-level `defstruct`, which the compile path refuses (`.kb/defstruct.md`) | Done in `.todo/884`: the `defstruct` is hoisted to the top level (`.kb/scheme-frontend.md`, "Internal record types"; one type per occurrence, not per evaluation) |
| `|...|` identifiers, `+inf.0` / `+nan.0` | reader | Done in `.todo/886` (`.kb/scheme-frontend.md`, "Vertical-line identifiers and the infinities"; `write` lines a symbol that would not read back; follow-ups `.todo/887`, `.todo/888`, `.todo/889`) |

Driven by a corpus rather than by the report: `.todo/828` (the SICP sample corpus) and its
items `.todo/829` .. `.todo/837`; `eval` is `.todo/833`.

Not a language feature, same area:

- **The playground has no language pick.** Split off: `.todo/893`. Done in `.todo/893` (the
  playground's language select, and the doc site's `scheme` fences are Run cells;
  `.kb/source-language.md`, "The browser"). A `.scm` reaches it only through `(load ...)` of
  an uploaded file; the REPL and the compile buttons read Common Lisp
  (`RontoPlayground.evalLine` / `frontend`). The doc site's Scheme fences are static for
  the same reason (`DocExamplesTest` verifies them; `RunnableBlockTransformer` runs `lisp`
  only). The form-at-a-time lowering is solved: the CLI REPL reads through
  `eval/SourceSession` (`.kb/scheme-frontend.md`, "A session"). What is left here is the
  pick itself and `evalLine` taking that seam -- it echoes only the LAST form, so it
  needs `Step.echoes` of the last step, not `ReplBuffer`'s loop.
- **The generic printer costs 58.8 KB of `.class`** (7.1 KB of wasm). Split off: `.todo/894`.
  Done in `.todo/894`: 31 KB of it was the JVM eval runtime, switched on by a stream resolver
  that dead library code rooted; now 43.1 KB / 8.4 KB (`.kb/scheme-frontend.md`, "Where the
  JVM bytes went"; the JVM apply tier, `.kb/eval-runtime.md`).
- Proper tail calls beyond loops (mutual recursion overflows at 5,000 on the JVM). Split
  off: `.todo/897`. Done in `.todo/897`: top-level procedures whose tail calls cycle are
  one loop on all four backends (`.kb/scheme-frontend.md`, "Tail-call groups"; a
  trampoline measured 15-20x per bounced call). Left: internal definitions (`.todo/898`),
  tail calls through a procedure value (`.todo/899`), the interpreter's thrown `go`
  (`.todo/901`).
- `(-)` on the interpreter reports `Index 0 out of bounds for length 0`; the compile path
  says `- requires at least one argument` (`compiler/ArithmeticIdentities`). Split off:
  `.todo/908`.

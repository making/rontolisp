# The composite stream constructors, and `open`'s `:if-exists`

Difficulty: Medium -- the constructors are the `make-broadcast-stream` pattern
one more time (Gray classes in prelude Lisp, no backend touched); `:if-exists`
is per-backend open-mode work on four backends. Recommend starting with an
Opus-class model.

Split out of `.todo/036` when that item closed (2026-08-15). It was never in
036's table -- 036 was the pathname / file-system / `write` umbrella -- but
`.todo/338` pointed at it for these names, so they get a home of their own
rather than a dangling reference.

## What is missing

| Operator | Kind | Purpose |
|----------|------|---------|
| `make-two-way-stream` | Function | one input + one output stream as one |
| `make-echo-stream` | Function | an input stream echoing what it reads to an output stream |
| `make-concatenated-stream` | Function | read the components in order |
| `two-way-stream-input-stream` / `-output-stream` | Function | the accessors |
| `echo-stream-input-stream` / `-output-stream` | Function | the accessors |
| `concatenated-stream-streams` | Function | the accessor |
| `open`'s `:if-exists` / `:if-does-not-exist` | Keyword | `:supersede` / `:append` / `:overwrite` / `:error` / `:create` |

`.todo/338` measures the consequence: the ANSI suite's `streams` chapter sits at
20.0%, and these are what it blames first.

## The shape the constructors should take

**`make-broadcast-stream` is the precedent and it should be copied exactly**
(`.kb/gray-streams.md`, `LispPreludeLibrary.MAKE_BROADCAST_STREAM_INTERNAL`): a
broadcast stream WITH components is a Gray output stream whose write generics
loop the components -- prelude Lisp, no runtime learns a new stream kind, and the
four backends therefore cannot drift. A two-way / echo / concatenated stream is
the same idea on the INPUT half of the protocol, which the Gray work of
`.todo/252` widened to carry:

- `make-two-way-stream` -- a Gray stream defining both `stream-read-char` (from
  the input component) and `stream-write-char` (to the output one).
- `make-echo-stream` -- a two-way stream whose `stream-read-char` also writes
  what it read.
- `make-concatenated-stream` -- `stream-read-char` walks the component list,
  dropping a component at its end of file.

Each is a `defclass` + a handful of `defmethod`s + a constructor defun in
`LispPreludeLibrary`, reached only by a program that spells the constructor (the
broadcast entry's splice rule), so nothing else changes size.

## `:if-exists` is the harder half

`open` resolves its `:direction` at COMPILE time on both compile paths
(`OpenModes.staticMode` in `am.ik.rontolisp.compiler`, `.kb/read-load-streams.md`),
which is why `open` has no `BuiltinFunctionWrappers` entry. `:if-exists` has to
join it there: a literal keyword decided by the shared front end, then one open
mode per backend (interpreter/JVM `StandardOpenOption`, WASM `path_open`'s
`oflags` -- `O_CREAT`/`O_TRUNC`/`O_EXCL` are already what the current
create-or-truncate mode sets). Today every output open is `:supersede`.

## Gate

`LispEvaluatorTest` + `Jvm/WasmLispCompilerTest` cases per constructor, a
`ci-spec.yaml` case running all three constructors and an `:append` reopen on all
four backends, per-operator doc pages in both language trees, and the
`.kb/gray-streams.md` / `.kb/read-load-streams.md` paragraphs. Then re-run the
ANSI report (`.github/workflows/ansi-report.yaml`) and check that `streams`
moved.

# A Scheme REPL: `rontolisp --source-language scheme` with no file

Difficulty: Medium

Today the flag is parsed and then ignored by the REPL: `ReplBuffer.eval` reads
`SourceLanguage.COMMON_LISP` unconditionally, so `(define (f x) ...)` answers
`The function DEFINE is undefined` (measured 2026-09-17). Wanted: with
`--source-language scheme` the REPL reads, evaluates and echoes Scheme. Still
EXPERIMENTAL, like the front end (`.kb/scheme-frontend.md`).

## The real problem: the lowering is per FILE, a REPL is per FORM

`SchemeLowering` decides defun-or-variable by a pre-scan of the whole file (defined once
by a `lambda`, never `set!` -> `defun`, called directly; anything else a variable called
through `funcall`). Fed one form at a time it forgets every earlier definition:
`(define (f x) ..)` becomes a `defun`, and a later `(f 1)` typed at the prompt still
works only by accident -- `f` is "a name this file does not define", which lowers to a
direct call. It breaks as soon as the classification would differ across forms:

- `(define (f x) ..)` then `(set! f car)`: the `set!` form sees no definition, emits
  `(setq f ..)` -- a VARIABLE -- while calls keep going to the `defun`.
- `(define f 1)` then `(define (f) ..)`, `(map f xs)` with `f` defined earlier (value
  position lowers to a variable reference, but `f` is a function), a redefined record
  procedure.

Two ways out; pick by measuring nothing, this is a semantics choice:

1. **REPL mode lowers every top-level procedure to a variable** (`setq` + `funcall`,
   `#'` never emitted for a user global). Uniform, stateless, always correct; loses the
   direct call, which nobody measures at a prompt. `Scheme.read` grows a mode (a
   parameter object, not a boolean soup). Recommended.
2. Keep a session: a `SchemeSession` holding the global scope across reads. Keeps
   `defun`s, but a later `set!` must retroactively turn a `defun` into a variable --
   i.e. re-emit it. More state, more ways to disagree with the file path.

Either way `(setq rontolisp::%scheme-false '|#f|)` must be emitted once per session, not
per form (harmless to repeat, but it is echoed).

## What else the REPL owns per language (add methods to the seam, `.kb/source-language.md`)

- **Continuation**: `ReplBuffer.isBalanced` counts parens with Common Lisp's string and
  comment rules. Scheme differs: `#;` datum comments, `#| |#` nesting, `#\(` and `#\)`
  characters, `|` is not an escape. Ask the language ("is this buffer a complete
  datum sequence?") -- `SchemeReader` already reports end-of-input through
  `LispReadException.endOfFile`.
- **Echo**: values print through the Scheme printer (`%scheme-write`: `#t`/`#f`/`()`,
  case-sensitive symbols), not `print()`. A `define` echoes nothing; multiple values
  echo one per line as today (`.kb/multiple-values.md`, "The REPL echo is a consumer").
- **Prompt**: `ReplBuffer.prompt` shows the current package (`CL-USER>`); Scheme has no
  package to show.
- **Errors**: a positioned `LispReadException` has no file at the prompt; keep the
  message, drop the prefix (already the case when `file` is null).
- `JLineRepl` (highlighting, completion) reads Common Lisp names; at least it must not
  upcase or mangle what is typed.
- `(import ...)` at the prompt: `imports()` only honours LEADING imports of a file.
  At a REPL every form is "leading"; decide whether an import narrows the session or is
  accepted and ignored (R7RS REPLs start with everything visible).

## Same seam, second consumer

The browser playground's `evalLine` is the same shape (`RontoPlayground`, reads
`COMMON_LISP`) and has no language pick either (`.todo/826`, "The playground has no
language pick"). Solve the per-form lowering here ONCE and let the playground take it.

## Done when

- `rontolisp --source-language scheme` defines, redefines, `set!`s and calls procedures
  across prompts with file-identical results; `RontoLispCliTest` / `JLineReplTest` pin a
  transcript including the two breaking sequences above.
- Multi-line input continues correctly over `#;`, `#| |#`, strings and `#\(`.
- The echo is Scheme's (`#t`, `()`, `Sym`), the help text says the REPL honours the flag,
  `doc/*/guides/scheme.md` gains a short REPL section, and `.kb/scheme-frontend.md`
  replaces "The REPL reads one form at a time and stays Common Lisp".

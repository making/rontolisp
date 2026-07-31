# The `prin1` family does not escape `"` and `\` inside strings

Found 2026-07-31 from `(print "{\"hello\":\"aaa\"}")` printing `"{"hello":"aaa"}"`.
Unlike [215](215-print-omits-cls-leading-newline-and-trailing-space.md) this is NOT a
deliberate divergence: it is a plain bug, and it breaks the defining contract of
`prin1` -- that its output can be read back.

CLHS: under `*print-escape*` (`print`/`prin1`/`~S`/`write-to-string`/`prin1-to-string`)
a string is printed with surrounding `"` and with every embedded `"` and `\` preceded
by `\`. We emit the surrounding quotes but no escapes.

```console
$ sbcl --script t.lisp                 $ rontolisp t.lisp
"{\"hello\":\"aaa\"}"                  "{"hello":"aaa"}"      ; (print ...)
{"hello":"aaa"}                        {"hello":"aaa"}        ; (princ ...) -- correct
"a\"b\\c"                              "a"b\c"                ; (prin1-to-string "a\"b\\c")
```

The reader already handles `\"` / `\\`, so the two halves disagree and the round trip
dies rather than returning a wrong value:

```lisp
(read-from-string (prin1-to-string "a\"b"))
;; SBCL      => "a\"b"
;; rontolisp => LispEvalException: Unterminated string literal
```

`princ` / `~A` / `princ-to-string` / `write-line` are correct and must stay untouched --
they are the no-escape half by definition.

## Reproduced on

Interpreter, JVM (`-o Prog.class`), WASM GC (`-o t.wasm`), and the component
(`--component`) all print the same unescaped text, so this is one missing behavior in
four places, not a backend divergence. `--no-gc` was not reachable with a top-level
`print` (it takes only `defun` / `wasm-export` at top level); check it through a
`defun` before assuming it is exempt.

## Root cause per backend

- Interpreter: `LispString.print()` (`src/main/java/am/ik/rontolisp/LispString.java:224`)
  is literally `"\"" + this.value() + "\""`. Everything above it (`Environment`'s
  `printString`, `prin1-to-string`, `write-to-string`, `format ~s`, the `_lispToString`
  analogue for conses/arrays/hash keys) inherits the gap.
- JVM: the quote-framing in `JvmRuntimeBuilder` `_lispToString` (see the
  "prin1: the quote-framed string" branch around `JvmRuntimeBuilder.java:1605`) plus the
  `quoteStr` constants threaded from `JvmLispCompiler.java:1445`.
- WASM GC / component: strings are stored quote-framed and `princ` prints `(1, len-1)`
  while `prin1` prints `(0, len)` (`.kb/wasm-gc-strings.md:132`). There is no per-character
  pass at all, so the escape has to be written as new runtime code
  (`WasmStringRuntimeBuilder` / `WasmRuntimeBuilder`, `WasmPrin1Compiler`,
  `WasmPrin1ToStringCompiler`). This is the expensive part of the item.
  `WasmReadRuntimeBuilder.java:1471` already un-escapes `\n \t \\ \"` on the read side --
  the writer is its missing mirror, and reusing that table keeps the two consistent.
- CLOS `print-object`: `%print-object-str` / `%prin1-to-string` (`.kb/clos.md:357-371`)
  bottom out in the same renderers, so string slots of instances/structs are affected too.

## Scope

- Fix the four backends together; add the escape ONLY on the `*print-escape*` = `t` path.
- Decide the exact escape set. CL escapes only the characters with a syntax type of
  single-escape (`\`) or the string terminator (`"`); a newline inside a string is printed
  literally, NOT as `\n`. Our reader accepts `\n` / `\t` on input, so "escape everything
  the reader understands" is tempting and WRONG -- match SBCL: `"` and `\` only.
- Tests: a round-trip pinning test (`(read-from-string (prin1-to-string s))` = `s`) for a
  string containing `"`, `\`, and a newline, in `LispEvaluatorTest`,
  `JvmLispCompilerTest`, `WasmLispCompilerIntegrationTest`, plus a `ci-spec.yaml` case
  covering `print` / `prin1-to-string` / `format ~s`.
- `doc/*/reference/functions/{print,prin1,prin1-to-string,write-to-string}.md` and
  `format`'s `~S` row currently show unescaped output in their examples; regenerate with
  `-Drontolisp.doc.fix=true` and check none of them was silently documenting the bug as
  the intended result.
- Sweep the repo for expectations that BAKED IN the bug -- anything asserting a
  quote-framed string with an embedded `"`. `json`'s tests are the likely place: a JSON
  string round-tripped through `prin1` currently looks right only because of this.

## Non-goals

- `princ` / `~A` / `princ-to-string` -- correct as they are.
- `print`'s leading newline / trailing space: that is [215](215-print-omits-cls-leading-newline-and-trailing-space.md),
  a separate and deliberate divergence.
- `*print-escape*` as a USER-settable variable and the rest of the printer-control
  variables ([041](041-readtable-and-printing-control.md)). This item is only about the
  escape-mode renderer being wrong; it does not require honoring a rebound
  `*print-escape*` outside the `print-object` path that already binds it.
- Escaping in symbol printing (`|foo bar|` bars). Same CLHS section, different renderer,
  and no round-trip failure has been observed for it -- worth a separate check, not a
  silent widening of this fix.

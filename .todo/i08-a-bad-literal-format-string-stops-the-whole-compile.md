# i08. A literal `format` string that would fail at run time stops the whole compile

Difficulty: Low

Measured 2026-11-04: `clingon-*/src/command.lisp:1297:21: format: ~* moves outside the argument list`
on JVM and WASM, for

```lisp
(format NIL " (alias~:*~[es~;~:;es~]: ~{~(~A~)~^, ~})" (length aliases) aliases)
```

The `~:*` before any argument is genuinely an error when it runs, and it is upstream's bug. But the
code is only reached with command aliases. SBCL 2.2.9 compiles `(format nil "~:*~a" 1)` without a
warning and signals `SB-FORMAT:FORMAT-ERROR` at the call; the interpreter also signals only at the
call (`.kb/format.md`, "the literal path signals the same at EXPANSION time"). So the program runs
on the interpreter and SBCL and refuses to build on the compilers.

Make the literal path compile a call-time signal with the same text, plus a `CompileWarnings`
warning at the form's position (so `--warnings-as-errors` still rejects it). This covers the
`not enough arguments for directive` case too. Add to the existing format ci-spec coverage.

After this, clingon on the compile paths meets `.todo/386`: `clingon:make-command` is re-exported
by `cl-reexport` at run time.

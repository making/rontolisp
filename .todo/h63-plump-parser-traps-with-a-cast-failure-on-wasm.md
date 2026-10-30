# h63. plump's parser traps with a wasm `cast failure` on both WASM backends

Difficulty: Medium

Found 2026-10-30 checking plump once its matcher DSL expanded (`.todo/h34`). On the
interpreter and the JVM plump matches SBCL (the JVM needs the home packages' names: `plump:`
re-exports through a run-time `do-external-symbols` loop, `.todo/386`). Preview 1 and the
component both trap -- no Lisp report, `--report-locations=line` adds nothing:

```lisp
(ql:quickload "plump" :silent t)
(let ((plump-parser:*root* (plump-dom:make-root)))
  (plump-lexer:with-lexer-environment ("abc<def")
    (print (plump-dom:make-text-node plump-parser:*root* "x"))   ; fine
    (print (plump-dom:text (plump-parser:read-text)))))          ; SBCL "abc"; wasm: cast failure
```

`read-text` is `(make-text-node *root* (decode-entities (consume-until (make-matcher (is
#\<)))))`, and every piece works on WASM when called at top level: `make-root`,
`make-text-node`, `decode-entities "a&amp;b"`, `consume-until` over a top-level
`make-matcher` inside `with-lexer-environment`. So the trap is in how the compiled
`plump-parser` function body is lowered (plump's lexer declaims and inline matchers are the
first suspects), not in a runtime primitive. A minimal special-binding shape (a `defvar`
without a value, let-bound by the caller, read by a struct accessor in the callee) runs
on all four backends.

Next step: cut `read-text`'s body down inside a defun of a package that uses the lexer
until the trap goes away; then the bug-fix protocol (a failing ci-spec case first).

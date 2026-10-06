# d70. The interpreter's runtime `load` of a malformed file escapes `handler-case`

Difficulty: Medium

A file holding a `)` that closes nothing, a form it ends inside of, or an unterminated `#|`
aborts the whole program on the interpreter: the error is not caught by a surrounding
`handler-case`, and none of the file's earlier forms run. SBCL evaluates the forms before the bad
one and then signals a catchable error; the JVM, P1 and the component now do the same (`_load`,
`.kb/read-load-streams.md`). Measured 2026-10-06, `bad1.lisp` = `(print 1) )`:

```lisp
(print (handler-case (load (copy-seq "bad1.lisp")) (error (c) (list :err (princ-to-string c)))))
(print :after)
```

| SBCL 2.2.9 | JVM / P1 / component | interpreter |
|---|---|---|
| `1`, `(:ERR ...)`, `:AFTER` | `1`, `(:ERR "Unexpected ')'")`, `:AFTER` | `error: bad1.lisp:2:1: Unexpected ')'`, exit 1 |

`(print 1) (print 2` and `(print 1) #| x` behave the same way (`Unexpected end of input, expected
')'` / `Unterminated block comment`). The interpreter's `load` parses the whole file before
evaluating; it wants to read form by form and signal the reader's typed condition (`end-of-file` /
`reader-error`) as a catchable one.

# d56. `read-from-string` of a stray `)` answers NIL on the compiled backends

Difficulty: Medium

`(read-from-string ")")` is a `reader-error` in SBCL and the interpreter. The JVM, P1 and the
component answer NIL without signalling. Measured 2026-10-06:

```lisp
(print (handler-case (read-from-string ")")
         (parse-error (c) (list :parse-error (type-of c)))
         (error (c) (list :error (type-of c)))))
```

| SBCL 2.2.9 | interpreter | JVM / P1 / component |
|---|---|---|
| `(:PARSE-ERROR SB-INT:SIMPLE-READER-ERROR)` | `(:PARSE-ERROR READER-ERROR)` | `NIL` |

Find where the compiled run-time reader treats an unmatched close paren (end of input, a
skipped token?) and refuse it as the interpreter's reader does, on all four backends together.

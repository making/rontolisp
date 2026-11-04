# i18. The JVM misreports an uncaught no-applicable-method

Difficulty: Low

Measured 2026-11-04.

```lisp
(defgeneric g8 (x) (:method ((x integer)) 1))
(print (g8 "s"))
; JVM: Unhandled condition: CLOSE: The value 0 is not of type STREAM
; wanted: No applicable method: G8 on STRING
```

A program containing `princ-to-string` (measured together with `handler-case`) reports it correctly.
Related: the JVM says `... on %struct-ST7` where the interpreter says `on ST`.

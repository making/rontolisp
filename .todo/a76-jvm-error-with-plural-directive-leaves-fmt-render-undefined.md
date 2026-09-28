# JVM: an `error` whose control string uses `~:p` compiles to a call of the undefined `%fmt-render`

Difficulty: Medium

On a JVM class, `error` with a `~:p` (plural of the previous argument) directive signals
`The function %FMT-RENDER is undefined` instead of its report whenever the program has no
`handler-case` that prints the condition; the compile warns
`warning: the function %FMT-RENDER is undefined; compiled as a call-time error`. The interpreter
reports the message. Found writing the new objc base, whose `objc.lisp` spells its arity message
without `~:p` because of it.

```lisp
(defun g (x) (if x (error "x ~a takes ~a argument~:p, got ~a" 1 2 3) 5))
(print (g nil))
(print (g t))
```

`java -jar rontolisp.jar p.lisp` prints `5`, then `Unhandled condition: x 1 takes 2 arguments, got 3`.
`-o P.class` + `java P` prints `5`, then `Unhandled condition: The function %FMT-RENDER is undefined`
(2026-09-28). The same program with `arguments` spelled out, or with the call wrapped in
`(handler-case ... (error (e) (princ-to-string e)))`, reports correctly: the gate that injects the
runtime format renderer does not count this `error` site's `~:p` as needing it.

## Done when

A test pins the uncaught report of an `error` with `~:p` on the JVM class output (and the
`--component`/Preview 1 outputs if they share the gate), and the warning is gone.

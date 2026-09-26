# A Lisp condition or non-local exit raised in a java: callback is wrapped at the Java call

Difficulty: High

Found 2026-09-26 while adding java:reify (a16). A `java:reify` / `java:proxy` function, or a
function passed where an interface is expected, runs inside a Java call. What it raises reaches
the `java:` site that made that Java call, which wraps every throwable as `error calling C.m:
<throwable>` -- a Lisp condition and a non-local exit included:

| program | interpreter | JVM |
|---|---|---|
| `(block b (java:call (java:static "java.util.List" "of" 1 2 3) "forEach" (lambda (m x) (when (= x 2) (return-from b x)))) :none)` | `error calling java.util.List.forEach: am.ik.rontolisp.eval.BlockReturnSignal` | `error calling java.util.List.forEach: java.lang.RuntimeException` |
| `(handler-case (java:call (java:static "java.util.List" "of" 1) "forEach" (lambda (m x) (error "boom ~a" x))) (error (e) (format nil "~a" e)))` | `"error calling java.util.List.forEach: am.ik.rontolisp.eval.LispEvalException: boom 1"` | `"error calling java.util.List.forEach: java.lang.RuntimeException: boom 1"` |

So a `return-from` / `throw` / `go` out of a callback never arrives, a `handler-case` on the
condition's own type never matches it, and the text differs by backend (the exception class of a
Lisp error). Expected: the exit and the condition pass through the Java frames unchanged, as a
Clojure exception thrown in a reify method does.

Where: `eval/JavaInterop.fail` (and the `InvocationTargetException` unwrap around every reflective
invoke), `JavaBridgeTemplate.fail` / `applyCallable`, the `memberFailed` handler of
`codegen/jvm/JvmJavaDirectSites` (catches `Throwable`). The compiled program needs a test for "a
Lisp signal" that a Java-thrown `RuntimeException` does not pass -- read how the compiled
`error` and the cross-lambda exits (`compiler/CrossLambdaExitLowering`, `.kb/do-return-block.md`)
represent themselves first. Pin both rows on both backends (`JavaInteropTest` /
`JvmJavaInteropCompilerTest`).

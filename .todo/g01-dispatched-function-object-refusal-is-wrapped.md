# g01. Dispatched function-where-Object refusal is wrapped, resolved is plain

Difficulty: Low

Found while closing f90 (2026-10-13): the function-where-`Object`-is-expected
refusal (`JavaImplementation.functionObjectRefusal`) is a plain error on the
interpreter on every path, and on the JVM only at a resolved direct site (f90
refuses it in `emitFunctionObjectRefusals`, before the conversion handler). At a
site left to run time the JVM's bridge throws it as a `RuntimeException` out of
`JavaBridgeTemplate.convert`, which the dispatch handler wraps into `error
calling C.m: java.lang.RuntimeException: java: a function ...`, where the
interpreter answers the bare refusal.

Measured (JVM): `(let ((l (java:new "java.util.ArrayList")) (f (lambda () 1))) (java:call l "add" f))`
with an unknown-typed `l` (e.g. through a function parameter) prints the wrapped
text; the interpreter prints the bare `java: a function ... got #<lambda>`.

## Plan

1. Decide the carrier: either the bridge throws past `_jfail` (a signalled
   condition, as resolved sites do by throwing before the handler), or the
   interpreter wraps (against f90's pin).
2. Pin both backends together (`JavaImplementationPrograms.FUNCTION_OBJECT`-style
   row with an unknown receiver).

# A `deftype` alias held in a VARIABLE is not resolved on the compile backends

Difficulty: Low

Found while landing `.todo/612`. The interpreter's `make-array` resolves its
`:element-type` argument through the `deftype` registry AT RUN TIME
(`Environment.makeArrayBuiltin` -> `LispMacroExpander.resolveElementTypeAlias`
with the live `ClosRegistry`), so a designator that arrives as a VALUE and
happens to name a user alias still picks the alias's representation. The
compile backends resolve aliases at expansion time only: the
`%make-array-et` prelude helper todo-612 introduced compares the runtime
designator against the seven built-in spellings and nothing else, so an alias
falls to the `t` arm.

```lisp
(deftype octet () '(unsigned-byte 8))
(defun buf (et n) (make-array n :element-type et))
(print (array-element-type (buf 'octet 4)))
(print (aref (buf 'octet 4) 0))
; interpreter    (UNSIGNED-BYTE 8) / 0
; JVM + both wasm  T               / NIL
; SBCL 2.2.9     (UNSIGNED-BYTE 8) / 0
```

A LITERAL `:element-type 'octet` is unaffected and has been correct since
salza2 forced it -- every compile-time recognizer resolves the alias first
(`.kb/array-literals.md`, `.kb/packed-integer-vectors.md`). This is only the
value-carrying spelling, which is rarer still than the runtime designator
itself: nothing in the quicklisp cache writes it today.

The shape of the fix follows todo-612's: the alias space is not closed, but the
program's OWN `deftype` set is, and it is known at expansion time. Either

- extend `%make-array-et`'s cond with one arm per registered alias whose
  expansion upgrades to something other than `t` -- the helper body is built
  from a static string today, so this makes it program-dependent (it would have
  to move out of `LispPreludeLibrary.SOURCES` into a generated defun, the shape
  `BuiltinFunctionWrappers` already uses); or
- normalise the designator BEFORE the dispatch: `(%deftype-expand et)`, a
  generated function mapping each registered alias name to its expansion and
  everything else to itself, called once at the top of the helper. One extra
  arm's worth of code per alias, and it composes with anything else that wants
  a runtime type designator (`typep`, `coerce`, `make-sequence`) -- which is
  probably why it is the better of the two.

Check those other runtime type-designator consumers while here: `typep` and
`coerce` may have the same hole, and if they do, the second option closes all
three at once.

Behavior must be identical on all four backends: rows in `LispEvaluatorTest` +
`JvmLispCompilerTest` + `WasmLispCompilerIntegrationTest` and a ci-spec case
whose expected text is SBCL 2.2.9's, plus the paragraph in
`.kb/array-literals.md` ("The one thing the helpers cannot do is resolve a
`deftype` ALIAS") that currently records the gap.

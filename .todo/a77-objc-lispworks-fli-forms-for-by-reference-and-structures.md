# `objc`: the LispWorks FLI forms the Objective-C manual's examples use

Difficulty: High

The new `objc` base runs the LispWorks 8.1 Objective-C manual's call-side examples unchanged,
except the ones written with its Foreign Language Interface, which rontolisp has no counterpart
of:

- 1.3.5, a structure result filled through a foreign object:
  `(fli:with-dynamic-foreign-objects ((rect cocoa:ns-rect)) (objc:invoke-into rect box "frame") ...)`;
- 1.3.7, a value returned by reference:
  `(fli:with-dynamic-foreign-objects ((result-value :int)) (objc:invoke object "getValueInto:" result-value) (fli:dereference result-value))`;
- `objc:invoke`'s "otherwise it is assumed to be a foreign pointer to a cocoa:ns-rect and is
  copied" for every structure argument, and `cocoa:set-ns-rect*` and friends on such a pointer.

Today a structure is the Lisp value `invoke` passes and answers (a vector, a cons), the setters
fill those, and a by-reference argument takes a raw address nothing in the language can allocate
on every target (`ffi:alloc` exists on the interpreter and the JVM only, never on `--native`).

## Decide

- The subset of `fli` to provide (`with-dynamic-foreign-objects`, `allocate-foreign-object`,
  `free-foreign-object`, `dereference`, `foreign-slot-value`, `size-of`), under the `fli` package
  name, on all four objc targets -- which means foreign memory primitives on `--native` too (the
  runner's host memory, not the module's linear memory).
- What a foreign structure object is (an address + a type) and how `invoke` / `invoke-into` tell
  one from the vector forms.
- Whether `ffi:`'s pointer value can be the same value, so one representation serves both.

## Done when

The two manual forms above run unchanged on the interpreter, JVM class output, the native binary
and `--native`, and `fli:size-of` answers the recorded LispWorks sizes of the four Foundation
structures (`src/test/resources/objc-lispworks-answers.lisp`).

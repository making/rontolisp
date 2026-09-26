# Compiled java: values classified and described unlike the interpreter

Difficulty: Medium

Follow-up of a14 (direct calls). A resolved site now checks and reports exactly as the
interpreter (`JavaInterop.invokeResolved`; texts through the program's `_lispToString`),
but what a compiled program counts as a host object, and how the bridge (the sites left
to run time) shows a value in an error, are still the a13-era bridge's.

Measured 2026-09-26 with `(defun f (x) (java:call x "size"))` (an unresolved site),
interpreter vs `-o X.class`:

- `(f (list 1 2))`: `... got (1 2)` vs `... got #<java [Ljava.lang.Object;>`
- `(f 1.0e10)`: `got 1.0e10` vs `got 1.0E10`
- `(f (expt 2 100))`: `got 1267650600228229401496703205376` vs
  `got #<java java.math.BigInteger>`
- `(f (make-array 2 :initial-element 0))`: an error (`got #(0 0)`) vs the Lisp vector
  taken as a host `ArrayList` and `size()` answered (1).

The last one is behavior, not text, and the direct path shares it: `_jhost` mirrors the
bridge's `isJavaObject`, which accepts every `ArrayList`, so a Lisp vector passed where a
`(java:object "java.util.List")` was declared is called instead of refused.

The printer has the opposite confusion (found 2026-09-26, a16): in a compiled program whose
printing goes through `%print-object-str` with its vector arm -- it formats a condition with
`~a` and can hold a general array -- printing a host `ArrayList` of integers throws
`ClassCastException: Integer cannot be cast to [Ljava.lang.Object;` from `_arrayDims` under
`%pos-walk` (`vectorp` takes it for a Lisp vector). The interpreter prints
`#<java java.util.ArrayList>`. Reproduction:
`(defun msg (e) (format nil "~a" e)) (print (handler-case (error "boom") (error (e) (msg e))))
(print (vector 1 2)) (let ((l (java:new "java.util.ArrayList"))) (java:call l "add" 1) (print l))`.

Plan:
- One host-object test for compiled code, the bridge's `kindOf` rule (an `ArrayList`
  whose slot 0 is an `Object[]` header is a Lisp array): `isJavaObject` in
  `JavaBridgeTemplate` and `_jhost` in `JvmJavaDirectSites` both use it.
- The bridge's `describe` through the program's printer: bind `_lispToString` beside
  `_apply`/`_strv` in `bind`, pin it for the splitter (`REFLECTIVELY_FOUND_METHODS`)
  and the shaker (a root while the bridge travels), fall back to today's text when absent.
- A bignum stays a Lisp integer (never a host object) on both: decide whether
  `(java:new "java.math.BigInteger" ...)` should be refused or unmarshalled.
- Pin each row on both backends (`JavaInteropTest` / `JvmJavaInteropCompilerTest`).

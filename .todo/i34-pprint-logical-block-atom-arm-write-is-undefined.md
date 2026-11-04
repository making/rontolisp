# i34. Compiled `pprint-logical-block` of a non-list object: `WRITE` is undefined

Difficulty: Low

Measured 2026-11-04 on the JVM and both WASM backends. Interpreter and SBCL 2.2.9 print `1`, then `in`.

```lisp
(defun f (o s) (pprint-logical-block (s o) (write-string "in" s)))
(f 1 *standard-output*)   ; JVM: The function WRITE is undefined; WASM: trap
(terpri)
(f nil *standard-output*)
```

Compile warning: `the function WRITE is undefined; compiled as a call-time error`.
`expandPprintLogicalBlock`'s atom arm emits `(write object :stream stream)`, and `write` is a prelude
definition (`LispPreludeLibrary`) the program's own text does not name, which is the likely reason nothing
pulls it in: a program that also calls `write` compiles without the warning.

lisp-namespace's `print-object` (`package.lisp:55`, `:58`) gets the same warning, on a jar from before h93
too, and it shows in the `TriviaE2eTest` and `SxqlE2eTest` output. The same class may explain
`%CHECK-SEQUENCE-BOUNDS is undefined`, which log4cl's JVM compile warns about: not reduced yet.

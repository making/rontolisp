# i77. A dispatcher repeats its methods once per generation, and the protocol arm too

Difficulty: High

Found 2026-11-06 compiling coalton (`.todo/i73`); fset alone shows it:

```lisp
(ql:quickload "fset")
(print (fset:convert 'list (fset:union (fset:set 1 2 3) (fset:set 3 4 5))))
(print (length (compute-applicable-methods #'fset:compare (list 1 2))))
```

- JVM: `method FSET$colonCOMPARE: method code exceeds the JVM's 65535-byte limit: 171728`. Before the outliner
  the method measured 5,516,079 bytes (915,783 nodes, `-Drontolisp.debug.outline`); it cut 10,722 pieces and
  the calls of the pieces alone are past the limit. Without the `compute-applicable-methods` line it compiles.
- The text of the generated defun is 5.6 MB: 40 generations (`macro/MethodGenerations`, `.kb/clos.md`, "Method
  definitions take effect in program order") of up to 426 methods, each generation a whole `dispatchBody`, and
  under the generic-function protocol each of them with its own dynamic arm, one entry per method (9,972
  method references in all). fset's `define-cross-type-compare-methods` interleaves running forms with its
  methods, so nearly every `defmethod compare` starts a generation.
- WASM compiles it (82 s, 14.9 MB) and answers 0 for the last line, where the interpreter and SBCL 2.2.9
  answer 2. Unmeasured whether that is the generations or the protocol's table.
- coalton reaches both: its `derive` names `compute-applicable-methods`, and its compiler uses fset.

The direction: one dispatch body over every method, each branch guarded by whether its method exists yet (the
generation flags already say when), instead of one body per generation; the dynamic arm once, its entries
guarded the same way. Combination and `call-next-method` have to choose among the live methods only, which
`dispatchBody` per table gives for free today, so that is the part to design.

# JVM: a run-time float :element-type with :initial-contents builds a general array

Difficulty: Low

Measured 2026-09-28. On the JVM backend a `make-array` whose `:element-type` is a run-time
designator naming a float, in a program with no literal float `make-array`, allocates the general
array stamped `double-float` instead of the packed one:

```lisp
(defun et-of (x) x)
(print (make-array 2 :element-type (et-of 'double-float) :initial-contents '(1d0 2d0)))
;; interpreter, wasm P1, component: #d(1.0 2.0)
;; JVM:                             #(1.0 2.0)      (array-element-type still DOUBLE-FLOAT)
```

`JvmLispCompiler.programUsesFloatArray` / `makeArrayIsPackedFloat` read only a LITERAL designator,
so `ctx.usesFloatArray` stays false and `JvmArrayCompiler` skips `LispFloatArray.prototypeFor` in
the dispatch arm `LispMacroExpander.lowerRuntimeElementTypeMakeArray` spells. A site the
`%make-array-et` helper serves (`:initial-element` only) is unaffected: the helper's defun spells
the literal arms, which arm the gate. The inline shapes -- `:initial-contents`, or no helper
spliced -- are not.

Goal: the gate counts a run-time designator whose inline dispatch can reach a packed float arm
(no `:fill-pointer` / `:adjustable`, which degrade every arm), so the JVM prints and packs as the
other three backends do; measure the class-size cost for a program that never reaches the arm.

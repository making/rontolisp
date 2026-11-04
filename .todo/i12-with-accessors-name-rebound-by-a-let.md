# i12. A `let` inside `with-accessors` rebinding an accessor name breaks

Difficulty: Medium

Measured 2026-11-04.

```lisp
(defclass wa-c () ((v :accessor wa-v :initform 5)))
(defun wa-f (o)
  (with-accessors ((a wa-v)) o
    (let ((a 1)) (list a))))
(print (wa-f (make-instance 'wa-c)))   ; SBCL (1)
```

Interpreter: `the value is not of the expected type`. JVM: `while compiling defun WA-F: LispCons
cannot be cast to LispSymbol` from `FreeVarAnalyzer`, the binding head having become
`(WA-V obj)`. `with-slots`/`with-accessors` run the lite `LispMacroExpander.substituteSymbols`,
which replaces names in binding positions too (`.kb/symbol-macrolet.md`).

Consumer: 3bz `gzip.lisp:252` rebinds `crc32` inside `with-accessors ((crc32 gs-crc32))`, so 3bz
does not compile to a class. Renaming that variable in a copy gets past it to i13.

CLHS defines both as `symbol-macrolet`. Check what lowering them through the shared walk costs:
the interpreter's walk would no longer reach a macro call's arguments, which the lite one does.
A `with-slots`/`with-accessors` name would then also be readable through `&environment`.

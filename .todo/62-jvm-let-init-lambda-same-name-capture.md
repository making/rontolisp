# 62: JVM: let-init lambda capturing a same-named outer variable miscompiles

Found while implementing `.todo/56` (flet/labels), 2026-07-05. Interpreter and
WASM print `11`; the JVM-compiled class throws
`ClassCastException: Integer cannot be cast to [Ljava.lang.Object;` in
`_invoke_0`:

```lisp
(let ((g (lambda () 1)))
  (let ((g (lambda () (+ 10 (funcall g)))))
    (print (funcall g))))
```

The inner let's init lambda references `g` free; it must capture the OUTER `g`
(a let init form is evaluated in the outer scope), but the JVM backend appears
to wire the capture to the inner `g`'s (boxed) slot. Renaming the inner
variable (`g1`) makes it work, which is why the flet/labels expansion uses
counter-unique generated names and never hits this.

Repro: `scratchpad letrec.lisp` case 3 of the flet session; add as a JVM unit
test when fixing. Suspect: `JvmLetCompiler`'s ordering of "allocate/register the
new binding slot" vs "compile the init expression" when the init contains a
lambda capturing the same name (FreeVarAnalyzer's LET case handles it
correctly -- init walked with OUTER boundVars -- so the bug is likely in the
codegen's capture wiring, not the analysis).

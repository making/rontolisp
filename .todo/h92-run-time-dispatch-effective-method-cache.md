# h92. Run-time method dispatch has no effective-method cache (contextl's figure editor)

Difficulty: Medium

contextl's `test/figure-editor.lisp` (`run-test`: 1000 runs moving 100 lines, layered and not)
does not finish in minutes. Measured 2026-11-01 on the JVM: its untimed and timed non-layered
halves took 172 s each, and the layered half did not finish in the rest of a 500 s limit; at 3
runs it passes on all four backends, as `fe2` does (`.kb/clos.md`, "Layered classes").

Cause, from reading the code: every layered function's layer argument is the prototype of a class
made at run time, so each call takes the run-time dispatch arm (`withDynamicDispatchArm`), which
computes every method's entry -- `dynamicMethodEntry`, `%gf-runtime-entries`, `%class-key` over
`%instance-cpl`, whose memo `%dynamic-cpls%` is an alist cleared at every finalization -- and
sorts them (`%dynamic-dispatch`, `macro/dynamic-classes.lisp`) afresh on every call.

Plan: cache a generic's sorted applicable entries by the classes of its specialized arguments
(an eql-specialized argument by its value), dropped by `add-method`/`remove-method`, a
`defmethod` of the generic and a class (re)finalization (`%mop-class-finalized`); then time the
1000-run figure editor before and after on the interpreter and the JVM.

# uiop:if-let

`(uiop:if-let ((var form)...) then [else])`

Binds the variables in parallel like [`let`](../special-forms/let.md), then
evaluates `then` when **every** variable came out non-nil and `else` otherwise.
The bindings are established for both branches, so `else` can still see them.

A single un-nested binding is accepted as well — `(uiop:if-let (x form) ...)` —
which is how UIOP itself spells the one-variable case; a binding list whose first
element is a symbol *is* the one binding.

```lisp
(list (uiop:if-let ((a 1) (b 2)) (list a b) :none)
      (uiop:if-let ((a 1) (b nil)) (list a b) :none)
      (uiop:if-let (x (+ 1 2)) (* x 10) :none))   ; => ((1 2) :NONE 30)
```

`uiop` is ASDF's portability layer, not part of Common Lisp: the name is only
reachable with the `uiop:` qualifier. This is UIOP's own copy of alexandria's
macro of the same name, and the two behave identically.

## Backend support

Works on all four backends: it is a built-in macro expansion shared by the
interpreter and both compilers. Like the other built-in macros it has no
function value (`#'uiop:if-let` is an error).

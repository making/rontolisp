# A JVM fragment cannot reach a defn its host defines in a body

Difficulty: Medium

Found while working h14 (2026-10-30). A `defn` in a body is a nested defun, which a JVM
host holds in its function namespace `_fenv` (`NestedDefunNamespace.heldNames`: every
nested defun of a program that evaluates data, as a host does), beside a `_g$` global that
stays nil. `JvmLinkage.shared` does not list `_fenv`, and a fragment resolves the name
through `_lookup`, the global or `_foreignLookup`, none of which holds it:

```clojure
(let [k 10] (defn nf [] k))
(println (eval '(nf)))          ; clj and the interpreter: 10
                                ; JVM host: The function NIL is undefined
(defn call-nf [] (nf))
(eval '(defn nf [] :redefined))
(println (call-nf) (nf))        ; clj and the interpreter: :redefined :redefined
                                ; JVM host: The function c%nf is undefined
```

The second failure is the fragment's `#'c%nf` (the redefinition notifies with the replaced
root); `(rontolisp::%run-time-redefine 'c%nf ...)` of such a name compiles to the same
undefined-function signal (`JvmRunTimeLinkCompiler`), since no class before holds a cell
for it.

Shape to weigh: share the function namespace through the link table -- `_fenv` a shared
field, the host's held names carried so a fragment reads them as its own `fenvForwarders`
(the fragment then needs `_envLookup`, today part of the eval runtime) -- and make
`%run-time-redefine` of a held name the store into that namespace, which the host's own
calls already read.

Acceptance: both lines answer as on the interpreter on the JVM leg of clojure-spec (extend
the `host:` case `a-run-time-redefinition-reaches-every-caller`).

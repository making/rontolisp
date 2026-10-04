# c63. Clojure: the runtime's own refusals carry no exception class, so every catch takes them

Difficulty: High

Since 2026-10-03 a `catch` dispatches by class (`.kb/clojure-frontend.md`, "Catching"): an
exception by its class chain, a runtime error by the class the oracle throws for its Common
Lisp condition type (`type-error`, `arithmetic-error`, `program-error`, `file-error`). The
refusals the Clojure runtime signals itself -- the `(error "...")` sites of `clojure.lisp`
(about 180) and of the lowering -- are `simple-error`s that name no class, so every catch but
`ExceptionInfo`'s takes them, the first clause of any class winning:

```clojure
(try (first 5) (catch ArithmeticException e :wrong) (catch IllegalArgumentException e :right)) ; :wrong
(try (assert false) (catch Exception e :wrong) (catch Error e :right))                         ; :wrong
```

The oracle's classes there, measured against clj 1.12.6 (2026-10-03): `seq`/`first`/`cons`/
`reduce` of a number, `conj` of a non-entry onto a map, "Key must be integer" are
`IllegalArgumentException`; `assoc` past a vector's end and `rand-nth` of `[]`
`IndexOutOfBoundsException`; `name`/`deref`/`re-pattern`/`peek`/`char` of the wrong kind,
`compare` across kinds, a string or number called as a function `ClassCastException`; `.write`
of nil and calling nil `NullPointerException`; `alter` outside `dosync`, `(pop [])`, calling an
unbound fn `IllegalStateException`; `read-string` past the end `RuntimeException`;
`bigint`/`bigdec` of a non-number string `NumberFormatException`; `subs` past the end
`StringIndexOutOfBoundsException`; a keyword called with three arguments `ArityException`;
`assert` `AssertionError`.

The class must be decided where the refusal is detected (the error-handling rule: never by
the message at the catching end). What is missing is a carrier that costs a program nothing
until it catches: building an exception condition (`C%E-NEW`) needs the exception runtime,
whose report reaches the printer (~100 KB of wasm), in every program that can reach a
refusal, which is nearly every program; a body swapped in only when the program catches by
class would make the uncaught report depend on whether a `try` exists elsewhere. Candidates
to measure: a small family of seeded-style condition classes the catch maps (one per oracle
class the refusals need), signalled typed from the refusal sites, with the wasm-GC instance
gates in mind (`.kb/error-handling.md`, "The condition floor"). Pin the measured classes in
clojure-spec on all four backends; the `a-refusal-naming-no-class-...` case then flips to the
oracle's answers.

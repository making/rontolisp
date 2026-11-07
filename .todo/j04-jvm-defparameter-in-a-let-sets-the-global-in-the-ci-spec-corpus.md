# j04. JVM: `defparameter` inside a `let` of its name sets the global in the ci-spec corpus

Difficulty: Medium

The native E2E (`CiSpecE2eTest`, full run of 2026-11-07 on `deaba3e84`) fails the case
`defvar-below-the-top-level-binds-only-while-unbound` on the JVM leg (plain and `--simd`); the
interpreter, P1 and component legs pass. The last line differs:

    expected (:STORED 5 NIL 2 1)
    actual   (:STORED 5 NIL 5 2)

so in the concatenated corpus program `(let ((*dvn-p* 5)) (defparameter *dvn-p* 2) *dvn-p*)`
stores into the global instead of the dynamic binding. The same case alone, and the two-line
program below, print `(2 1)` on the JVM (also with an `eval` or a `symbol-value` of a computed
name added), so something only the whole corpus program has selects another binding path
(candidates: `_dbound` / per-thread binding, the eval mirror, a size-driven split of the main
method):

    (defparameter *dvn-p* 1)
    (print (list (let ((*dvn-p* 5)) (defparameter *dvn-p* 2) *dvn-p*) *dvn-p*))

The case came with the run-time "only if unbound" test for a `defvar` (`compiler/DefvarGuards`,
`.kb/dynamic-special-variables.md`, "A defvar's \"only if unbound\""), which also made
`defparameter` set the active binding. Find which corpus trait flips the JVM path, make the
`defparameter`/`defvar` store target the active binding there too, and pin it where `./mvnw test`
sees it if the trait is reachable from a unit test.

Done when: the case passes on every leg of the native E2E (run it with the corpus, not alone).

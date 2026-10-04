# c87. wasm `remf` past the first key answers NIL

Difficulty: Low

On both wasm backends `remf` that removes a key other than the first answers NIL although it
removed the pair: `(let ((p (list 'a 1 'b 2))) (print (remf p 'b)) (print p))` prints `NIL`
`(A 1)` (measured 2026-10-04, preview 1 and component); the interpreter, the JVM and SBCL
print `T` `(A 1)`. Removing the first key answers `T` everywhere. `ci-spec.yaml` `remf` only
pins the not-found answer, and `RemfIndicatorFixture` prints the list alone, so nothing
pins it. Expected: `T` on all four, pinned by a row that prints `remf`'s answer.

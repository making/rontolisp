# Clojure: `conj` onto an atom conses onto the wrapper

Difficulty: Small (one guard in the `conj` consp arm, mirroring b21's regex guard).

## Gap (found during b21's leak hunt, oracle `clj` 1.12.6.1673)

Atoms lower to the `(:C%ATOM ...)` cons wrapper, and `conjTwoForm`'s list arm
tests only `consp` minus the typed wrappers -- so `(conj (atom nil) 1)` silently
answers `(1 :C%ATOM ...)`-shaped garbage instead of signalling like the oracle
(`Don't know how to create ISeq from: clojure.lang.Atom`). b21 added the same
guard for patterns/matchers (`isRegexForm`); atoms were left untouched as out
of scope.

## Acceptance

- `clojure-spec.yaml` or `ClojureInteropTest`: `(conj (atom nil) 1)` signals,
  green where pinned (spec: all four backends).
- `.kb/clojure-frontend.md` atom row notes the signal.

## Depends on

b21 (the `isRegexForm` guard precedent in `conjTwoForm`).

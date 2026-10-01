# `seq`/`count`/`empty?`/`cons` onto an atom read the wrapper as a list

Difficulty: Small (one guard in `%clojure-strict-seq`, one shared guard in
`countForm`/`emptyForm`, mirroring b42's `isAtomForm` guard in `conjTwoForm`).

## Gap (found during b42, oracle `clj` 1.12.6.1673)

b42 fixed `conj` onto an atom (`(:C%ATOM ...)` cons wrapper) by adding a
`(not (isAtomForm ...))` guard to `conjTwoForm`'s list arm. The same wrapper
still passes three sibling checks, verified against the oracle (all signal
there):

- `%clojure-strict-seq` (`src/main/resources/am/ik/rontolisp/eval/clojure.lisp`):
  the `((consp coll) coll)` arm sits past the set/record/typed-opaque/regex
  checks with no atom check, so `(seq (atom nil))`, `(first (atom nil))`,
  `(rest ...)` and `cons` onto an atom read the wrapper as a plain list.
- `countForm` (`ClojureLowering.java`): an atom falls through to
  `(length coll)`, answering 2 (the wrapper's size) instead of signalling.
- `emptyForm`: an atom falls through to `(null coll)`, answering false
  instead of signalling.

Note `empty?` has no dedicated signal arm at all (its default is `null`);
the oracle signals for atoms, so the guard must come before the default.

`%clojure-atom-p` already exists in `clojure.lisp`; the lowering side can
reuse `isAtomForm`. Refs/agents/volatiles share the `:C%ATOM` cell, so one
guard covers all four, like b42.

## Acceptance

- `clojure-spec.yaml`: `(seq (atom nil))`, `(first (atom nil))`,
  `(count (atom nil))`, `(empty? (atom nil))`, `(cons 1 (atom nil))` all
  signal, green where pinned (spec: all four backends).
- `.kb/clojure-frontend.md` atom row notes the signals.

## Depends on

b42 (the `isAtomForm` guard precedent in `conjTwoForm`).

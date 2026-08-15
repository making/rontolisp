# A computed `export` never reaches the resolver, so the single-colon spelling it licenses stays a compile error

Difficulty: Medium

Split out of `.todo/038` (2026-08-15) when the rest of the symbol/package API
landed; the mechanics and the reason it was NOT widened then are recorded in
`.kb/packages.md` ("A COMPUTED `export` never reaches the resolver").

## The shape

```lisp
(in-package :pkg)
(defun x () 1)
(in-package :cl-user)
(export (intern "X" "PKG") "PKG")   ; computed: the resolver cannot see the symbol
(pkg:x)                             ; -> "The symbol X is not external in the PKG package"
```

Only a LITERAL `export` folds (`PackageResolver.tryConsumeExport`); a computed
one falls through to the runtime function, which the interpreter serves against
the SAME resolver -- so it works there for the forms read after it -- and which
the compile paths lower to "arguments for effect, then `t`"
(`LispMacroExpander.expandRuntimeExport`), the registry being frozen. The
RUNTIME designator route already works on every backend (the `_lookup` alias
rows serve `PKG:NAME` for every `PKG::NAME` defun), so the gap is purely the
compile-time single-colon SPELLING.

## Why it was not fixed with the rest

The only rule that could license the spelling is "a package that some computed
`export` targets exports everything it owns" -- and a `defun` made under
`(in-package p)` is not in the registry's owned set at all (only `defpackage`
`:export`/`:shadow` names are), so the rule would have to accept ANY `pkg:x`
under such a package and would swallow typos. This subset denies by default.

## What would decide it

A consumer. Found 2026-08-02 while patching uiop from Lisp, which is no longer
how uiop is served (`.kb/asdf.md`), so the item has none today. When one turns
up, weigh the two shapes: (a) admit the spelling only for packages named as a
literal TARGET of a computed export, (b) wait for symbol identity
(`.todo/156`), where "does this package own this name" is a real question and
the answer needs no heuristic.

## Non-goals

- Making the interpreter and the compile paths differ MORE: whatever lands must
  behave the same on all four backends.

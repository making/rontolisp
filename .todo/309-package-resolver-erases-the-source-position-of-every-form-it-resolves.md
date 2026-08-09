# `PackageResolver` erases the source position of every form it actually resolves

Difficulty: Medium

`.kb/source-positions.md` states the rule in two halves: a pass that changes
NOTHING must hand the cons back by identity (`LispCons.rebuilt`), and a pass
that genuinely REWRITES owes the rewritten cons the original's position
(`SourceProvenance.inherit`). `PackageResolver` implements the first half --
`resolveCons` and `resolveSymbol` both have the comment saying so -- and not the
second. So a form in which a single symbol resolves to a different name is
rebuilt from the top-level form down, and every position under it is gone.

That is not a rare shape: it is every form of every file that says
`(in-package :foo)` and then names anything qualified, i.e. the whole of every
quickloaded library.

## How it surfaced

Found while adding the `--no-wasi` build lines (`compiler/NoWasiLoadPathRefusals`).
Two forms of the same compile, both read from a quicklisp checkout:

| form | position reported |
| --- | --- |
| `(get-universal-time)` inside lack's `cookie-state` slot default | `.../cookie.lisp:25:12` |
| `(with-open-file ...)` inside clack's `%load-file` | none at all |

The difference is exactly the rule above: the first cons contains no symbol that
resolves differently, so it comes back by identity; the second contains
`*package*` / `*readtable*` / `*load-pathname*`, which resolve to canonical
names, so it -- and every ancestor up to the `defun` -- is a fresh cons the
provenance table has never seen. The warning pass falls back to the innermost
SURVIVING located ancestor and finds none, so it prints its line bare.

## The fix, and why it is not a one-liner in practice

Route `PackageResolver.resolveCons`'s two rebuild sites through
`SourceProvenance.inherit(original, rebuilt)`, the way `PureBuiltinFolder`
already does. The code change is small; the cost is the blast radius, because
positions are a PREFIX on error and warning text:

- every message that currently prints bare gains a `file:line:column: ` prefix,
- `src/test/resources/ci-spec.yaml` `expected` blocks contain error text, so the
  native `CiSpecE2eTest` run is the real gate (a JVM `./mvnw test` does not run
  it),
- `DocExamplesTest`'s `console` blocks contain error text too.

So this is "one edit plus a full expected-output sweep", not a drive-by.

## Done when

- A malformed form deep inside a `defun` of a package-qualified library file
  reports its OWN line, not the top-level one and not nothing -- the same probe
  `aMalformedFormKeepsItsLineWhenTheProgramAlsoTriggersALibrarySplice` uses,
  extended with an `in-package` + qualified-symbol case.
- `.kb/source-positions.md` moves `PackageResolver` from the identity-preserving
  list to the inheriting one, saying which half it now satisfies.
- The native `CiSpecE2eTest` run is green against the rebuilt binary.

## Related

`.kb/source-positions.md`, `PackageResolver.resolveCons`/`resolveSymbol`,
`SourceProvenance.inherit`, `PureBuiltinFolder` (the precedent),
`compiler/NoWasiLoadPathRefusals` (where the gap showed up).

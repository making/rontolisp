# `defpackage` clause validation, and clause errors a handler can catch

Difficulty: Medium

Split out of `.todo/917` (2026-09-20): the largest family the member table left
on the ANSI `packages` board (`defpackage.13`-`.26`, 14 tests) plus the two
`LispPackageException`-is-not-a-condition rows `.todo/904` had already noted.

## What it is

CLHS `defpackage` requires:

- a `program-error` for a repeated `:size` or `:documentation` clause
  (`defpackage.13`, `.14`);
- a `program-error` when the names in `:shadow`, `:shadowing-import-from`,
  `:import-from` and `:intern` are not disjoint (`defpackage.17`-`.23`, every
  pair of clauses);
- a `package-error` when a `:nicknames` entry names an existing package or
  nickname (`defpackage.15`, `.16`);
- a `package-error` with a usable restart when `:use`/`:import-from` names a
  package that does not exist (`defpackage.24`-`.26` want `handle-non-abort-restart`
  to find one).

`PackageResolver.resolveDefpackage` checks none of the first two (it accepts the
repeats and the overlaps) and throws `LispPackageException` for the third and
fourth -- a raw Java exception the evaluation seam does not classify into a
`package-error`, so a `handler-case` around the `defpackage` (the tests wrap it in
`signals-error`) sees nothing catchable and the driver bills the form.

## Plan

1. The validation itself, in `resolveDefpackage`: the repeat and the disjointness
   checks, each a distinct message.
2. The seam: a `defpackage` that is NOT top-level registers at run time through
   `LispEvaluator`'s `DEFPACKAGE` case (`rareOperatorExpansion` -> `resolve`),
   which is where a `LispPackageException` can be turned into the typed condition
   `signalPackageError` already builds for the runtime tier (`package-error`
   with the designator in its slot; `program-error` for the clause-shape rows).
   A TOP-LEVEL `defpackage`'s hard error stays a hard error: that is the compile
   path's read-time failure, and CL signals at load time too.
3. Restarts are out of scope unless the condition machinery already offers one
   (`.todo/433` owns the store-value / continue restarts).

Measure as a DIFF of failing test names in the `packages` chapter before and
after; the baseline row is in `.kb/packages.md`, "The member table".

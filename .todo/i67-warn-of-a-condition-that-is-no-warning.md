# i67. `warn` of a condition that is no warning

Difficulty: Medium

Measured 2026-11-05: all four signal the datum and then write, where SBCL 2.2.9 signals a `type-error` (CLHS `warn`, datum and
expected type `warning`).

```lisp
(print (handler-case (warn 'simple-error :format-control "e") (type-error () 'te) (error () 'err)))
```

SBCL answers `TE`, all four `ERR`; with no handler SBCL dies of the `type-error`, all four print `WARNING: e` and go on.

## Plan

A typed datum is known at expansion, a condition datum needs a run-time test in `expandObjectSignal`'s condition arm. The
`type-error` is built by a lowering after the scans, so `conditionNarrowing` and `usedLayoutTags` have to learn the site
(`.kb/error-handling.md`, "The condition floor is narrowed"). Once only warnings pass, `formWarnsAnyDatum`'s arm of
`needsSignalClauseMatch` can go.

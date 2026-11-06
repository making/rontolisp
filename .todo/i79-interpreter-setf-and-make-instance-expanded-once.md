# i79. The interpreter's setf and make-instance: expanded once per site, not per evaluation

Difficulty: Medium

Measured 2026-11-06 (JFR of coalton's load up to `library/math/real.lisp`, `.kb/asdf.md`, the coalton entry): a
`setf` expands again on every evaluation, 4.5% of the samples (`expandSetfMaybeUserExpander`), and so does a
`make-instance`, 2.9%, two thirds of it a walk of every generic that is gone since
(`ClosRegistry.initializationGeneric`). Each expansion is fresh conses, so
the call-site notes (`.kb/interpreter-expansion-memo.md`, "The call-site note") miss every form in it and note
them again. Of the `setf` share about 2% is three walks of every class or layout per slot name
(`classSlotAccessByTag`, `uniqueLayoutSlotIndex`, `resolveSlotBaseName` in `LispMacroExpander`).

They expand per evaluation because what they expand to reads registries that move: the user macros (the
`callSiteEpoch` already covers those), the setf expanders and setf functions, the struct accessors, the class
layouts and slot allocations, global symbol macros, the generic-function protocol's switches, and the prelude
setf places that `ensurePreludeSetfPlacesLoaded` loads on first sight.

The work: keep each site's expansion (an `ExpansionNote`, or the `builtinMacroExpansions` memo) under a stamp of
every input it reads, each registry bumping its part of the stamp on every write; or, where a slot walk alone is
the cost, answer it from a memo stamped the same way. List the inputs from the code, not from this note. Pin with
a program that redefines each input between two evaluations of one site. Done when the samples under the two
expansions are gone from the same JFR window and the window's time drops by about their share.

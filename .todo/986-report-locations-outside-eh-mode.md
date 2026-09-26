# `--report-locations` prints nothing for a wasm-GC program without a catching form

Difficulty: Medium

`--report-locations=line|function` adds location lines under a wasm-GC module's
`Unhandled condition:` report (`.kb/error-handling.md`, "Location lines on wasm-GC"). The report
itself exists only in EH mode -- a program containing `handler-case`, `ignore-errors` or
`unwind-protect` -- so for any other program the option is silently a no-op: the module still
traps with a bare `unreachable`, which is exactly the case where a location would help most.

Goal: with `--report-locations`, a program outside EH mode gets the report and its location lines
too, on Preview 1, `--component` and `--native`. A build WITHOUT the option stays byte-identical
to today's, on every program -- that is the constraint that decides the shape, not a
nice-to-have.

What is known (2026-09-26): turning EH mode on for every program was measured at
121,572 -> 175,486 B on the two-line toy (`.kb/error-handling.md`, "Outside EH mode nothing
changes"), mostly the report renderer and the routing it drags in. Paying that only under the
option is acceptable; paying less is the work:

- Measure first what the option costs when it simply forces EH mode (toy, zlib, the size-report
  programs, `--optimize=size` and default), and record it beside the existing table.
- Then look for a narrower mode for a program with no catching form: nothing can catch, so no
  handler routing is needed -- only the throw path, the per-frame note and the entry landing pad.
  The note in the `.kb` file ("Re-evaluate if the report renderer becomes narrowable to the
  classes that can actually ESCAPE") is the lead.
- Document the option's behaviour change in `doc/{en,ja}/compiling/wasm.md`.

Read first: `.kb/error-handling.md` (EH mode gate, uncaught report, location lines),
`.kb/standard-output-redirect.md` (the stderr narrowing the report ORs into).

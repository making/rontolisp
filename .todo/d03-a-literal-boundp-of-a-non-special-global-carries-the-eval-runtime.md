# d03. A literal `boundp` of a non-special global carries the eval runtime

Difficulty: Medium

```lisp
(defun s () (setq *z* 1))
(s)
(print (boundp '*z*))
```

`CompileTimeBoundp` cannot answer this probe (the only assignment is in a deferred body), so it
stays a run-time `%boundp-raw` over the eval mirror, and `boundp` keeps the whole eval runtime and
every mirror write alive. Measured 2026-10-04 against the same program with `(if *z* t nil)` in
place of the probe: JVM 47,142 vs 6,228 B, wasm P1 2,364 vs 1,807 B. The same holds for any
non-special global the fold leaves open, e.g. a probe inside a defun of a name a later top-level
`setq` assigns.

d01 removed this cost for a tracked special (no definer value, probed by a literal `boundp`): an
unbound marker in its own store answers the probe (`.kb/dynamic-special-variables.md`,
"Bound-ness of a special without a value"). Plan: extend the tracked set to non-special globals
with no definer value that a literal `boundp` names, so the probe reads the store and the
`boundp` arm of the eval gate drops when every site is such a probe. Watch the read side: an
unbound marker must read as nil wherever the global is read (the tracked-special path already
does this). Measure size-report, bench-report, examples and the ci-spec program for byte
identity.

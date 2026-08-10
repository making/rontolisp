# The zlib rows, part 2: the dead bzip2 tree and the ~84 KB condition floor

Difficulty: High

Split out of `.todo/315` (see `git log` for its closing commit), which delivered the
measurement tooling and the runtime-floor narrowings. This item carries the two
remaining, larger works, with the measurements that rank them. Baseline after 315:
`zlib --optimize=size` = 411,948 bytes (was 425,815); the check stream still gunzips
byte-identically on all four backends.

## 1. The dead bzip2 tree, ~90-120 KB (the biggest single prize)

`-Drontolisp.wasm.debug-func-sizes` (built by 315) says the shipped artifact carries:
64 bzip2-named functions totalling 53,229 B -- the largest single function in the whole
module is `CHIPZ::%MAKE-BZIP2-STATE` at 44,814 B (the ~60-slot BOA constructor) -- plus
one of the two big `labels` state-machine lambdas (`_lambda_654` 29,430 B /
`_lambda_655` 12,419 B; one is `%bzip2-state-machine`'s, map it via the dump before
starting) and the bzip2 data-section strings.

Why the pruner keeps it (verified against chipz 0.8 sources, which have NO eql
specializers -- `package.lisp`'s "Symbols for EQL specializers" comment is vestigial):

- `make-dstate` (`dstate.lisp:43-48`) is a `case` on the format whose `(:bzip2 bzip2)`
  arm names `make-bzip2-state` -> keeps the constructor and the struct.
- `decompress-fun-for-state` (`decompress.lisp:41-44`) is a `typecase` on the STATE
  whose `(bzip2-state ...)` arm names `%bzip2-decompress` -> keeps the state machine.
  This is the load-bearing keeper; killing only the `make-dstate` arm changes nothing.

The sound shape, two stages, BOTH needed:

- **Stage A -- constant propagation of the format argument**: `'chipz:gzip` flows
  user call -> `decompress`'s default defgeneric method -> `%decompress` -> `make-dstate`,
  three frames, then folds the `case`. This is the "real new pass" of 315's item 4 and
  is worth stating generally: a library whose entry point dispatches on a
  caller-constant keyword is a common shape.
- **Stage B -- instantiator-gated `typecase`/`case` arms in the pruner**: an arm
  testing a candidate struct/class with NO live instantiator contributes no references
  (the same soundness argument as the pruner's defmethod specializer gate,
  `.kb/library-defun-pruning.md`; the fixpoint stays monotone -- when an instantiator
  goes live, the arm's references join). Stage B only fires after Stage A kills
  `make-bzip2-state`.

Note `DeadTypeBranchPruner` (compiler-level, `--optimize` only) already prunes typecase
clauses by call-site argument types -- check whether Stage B belongs there or in
`LibraryDefunPruner` (the latter also shrinks unoptimized artifacts and JVM classes).

## 2. The condition-system floor, ~84 KB of the 89,138 B handler-case probe

Unchanged by 315 (the restart-runtime false positive it fixed was ~7 KB on zlib and is
not part of this floor). The explored decomposition, each with its gate site
(file:line refs as of 2026-08-10):

- **The `format` runtime renderer is the largest piece** (~33 KB of Lisp source,
  `format-render.lisp`): pulled in transitively because `%format-condition`
  (`LispMacroExpander.formatConditionDefun`) unconditionally contains the
  `%fmt-render` arm. A bare handler-case program's only conditions carry
  fully-rendered controls with nil `format-arguments`; emit a
  `(if (null args) control (%fmt-render control args))` fast path and decline the
  renderer splice when no site can pass non-nil arguments.
- **The seeded class tree in the report partition**: `conditionReportGroups` iterates
  every registered condition class (23 seeded); a program whose only constructible tag
  is `%CLASS-SIMPLE-ERROR` still gets the `end-of-file`/`unbound-slot`/
  `simple-type-error` arms (their report strings are in the zlib artifact). Intersect
  the groups with the tags the module can construct or test.
- **`%print-object-str` on `routesConditionReports()` alone**, even with empty
  `printObjectTags` and no printing operator reachable from a condition value.
- **Instance layouts for unreachable classes**: `WasmInstanceLayouts.emit` serializes
  EVERY registry layout once `usesInstances` is on -- `DIVISION-BY-ZERO`,
  `STYLE-WARNING` etc. are in a gzip decompressor's data section. Pass the
  constructible/tested tag set and skip the rest.
- `mayCreateConditions` treats any `handler-case` head as condition-constructing; a
  body that provably cannot signal could keep the whole fast path. (Narrowing here
  must respect the handlers-fall-back contract.)

## 3. Data-section items (measure first; from 315's list)

Double interning (`CHIPZ::FOO` and `CHIPZ:FOO` rows), one
`"No applicable method: <NAME> on "` literal per CLOS accessor. Small next to 1 and 2.

## Deliverable

Same as 315's: a measured reduction in the `zlib` rows of
`size-report/results/wasm-flags.md`, wins recorded per step, no change in what the row
checks, `./mvnw test` + native `CiSpecE2eTest` green, byte-identical output for
programs that do not use the mechanism being gated.

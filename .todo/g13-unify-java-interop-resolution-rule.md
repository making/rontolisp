# Unify the java-interop resolution rule into one implementation

Difficulty: High

`compiler/JavaOverloads` is the shared resolution rule (kinds, `kindCost`,
`select`/`selectRanked`), but the compiled JVM bridge `codegen.jvm.JavaBridgeTemplate`
keeps a hand-written standalone copy of the run-time half (`isJavaObject`, `kindOf`,
`convert`, `unmarshal`, cost tables), pinned to the shared rule only by
`JavaBridgeTemplateParityTest`. The interpreter (`eval/JavaInterop`) and the direct
sites (`JvmJavaDirectSites`) are further copies of pieces of the same semantics
(receiver conversion, unmarshal), kept in sync by convention and tests.

The copies exist because the bridge template must not import any rontolisp class
(it is emitted into user programs), and the direct sites must inline into generated
bytecode. A naive "share the code" refactor breaks those constraints.

Goal: one source of truth for the rule, with the per-backend copies either generated
from it or reduced to thin renderers, so a new marshal/convert rule lands once.
Candidates considered before: generating `JavaBridgeTemplate` text from
`JavaOverloads`/`JavaKind` metadata at build time; or specifying the rule as data
(a table) that all three read/emit from.

Constraints to preserve:

- The emitted bridge has no nested classes/records and no rontolisp imports.
- `JavaBridgeTemplateParityTest` (and the direct-site parity pins) must keep passing,
  or be subsumed by a stronger single-source check.
- Program size neutrality: the generated bridge/sites must not grow measurably; verify
  with the size harness (`.kb/size-measurement.md`) before and after.

Relevant docs: `.kb/java-interop.md` (all sections; "THE rule lives ONCE" note and the
KEEP-IN-SYNC warnings mark each copy).

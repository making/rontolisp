# 572. The quicklisp `.asd` search path is in host filesystem order

Difficulty: Low (one `.sorted()`, plus deciding whether the resulting order change
moves any pinned expectation)

`.kb/emitted-output-determinism.md` says "Filesystem order (`Files.list`,
`File.listFiles`, `Files.walk`) is not an order. Sort it." `cli/FormatCommand`
obeys it. `eval/DistClient.collectAsdDirs` does not.

Found 2026-08-29 by the re-sweep that closed `.todo/570`.

## The cause

`eval/DistClient.java` walks a downloaded release with `Files.walk(root)` and
appends the result VERBATIM into `searchDirs` -- the quicklisp system search
path `AsdfSystems.locate` consults. `Files.walk` promises no order; on ext4 it
is directory-hash order, which differs between machines and can differ between
two checkouts on one machine.

This is not the per-JVM-run salt of `.todo/570` -- one host compiles the same
program the same way every time -- so no single machine can see it by compiling
twice. It bites when a release contains more than one `.asd` defining the same
system name (a top-level `foo.asd` beside a `test/foo.asd`, which quicklisp
releases do ship): which one `locate` finds first, and therefore which source
is spliced into the emitted program, is decided by the host's directory order.
Two developers can compile one program from one lockfile and get two different
classes.

## The fix

Sort the walk (by `Path::toString`, the way `FormatCommand` does) before it
reaches `searchDirs`.

The interesting part is not the sort, it is what it MOVES: on any machine whose
filesystem order already differed from sorted order, some program's emitted
bytes change. Compile the quicklisp-backed examples before and after and record
the diff -- if a system's chosen `.asd` changes, that is the bug being fixed and
the new choice needs a look, not a rubber stamp.

## Acceptance

- `collectAsdDirs` returns a sorted path list, and a test pins that.
- The quicklisp E2E suites still pass, and any emitted-bytes change is explained
  rather than absorbed.

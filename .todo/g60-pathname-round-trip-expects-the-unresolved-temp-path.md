# g60: a pathname round-trip test expects the unresolved macOS temp path

Difficulty: Low

`LispEvaluatorTest.pathnameFileOperatorsRoundTripOnTheFilesystem(Path)` (line ~11627)
fails on macOS: expected `#P"/var/folders/.../junit-.../a.txt"`, but was
`#P"/private/var/folders/.../a.txt"` (`/var` is a symlink to `/private/var`). g57 fixed
the same mismatch in the UIOP truename tests by checking SBCL; this one was missed.

## Goal

Decide from the oracle (SBCL) whether the operator should return the physical path (then
fix the expectation, as g57 did) or the given one (then fix the runtime on every backend),
and leave the test green on macOS and Linux. Sweep `src/test/java` for other `@TempDir`
expectations that compare a resolved path with an unresolved one.

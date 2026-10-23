# g61: probe-file resolves symbolic links like SBCL

Difficulty: High

SBCL's `probe-file` answers the truename: it resolves symbolic links, so on macOS
`(probe-file "/var/folders/.../a.txt")` is `#P"/private/var/folders/.../a.txt"`. Ours
answers the argument's spelling on every backend; only `truename` resolves
(`.kb/pathnames.md`, "probe-file answers the argument spelling; truename the physical one").

Trying `%real-path` inside `probe-file` (2026-10-23) turned four existing `probeFile*`
tests to `/private/var` -- expected, they pin the old spelling -- and raised the open
question that blocked it: on WASM a WASI preopen names the directory as given, and the
physical path need not lie under any preopen, so a resolved answer may not be openable
there.

## Goal

- `probe-file` returns the physical path (links resolved) on the interpreter, JVM and both
  WASM backends, matching SBCL, for files and directories; a missing file still answers NIL.
- On WASM, decide how a resolved path stays usable: e.g. map it back through the preopen
  table, or preopen the physical spelling as g57 did for the test `--dir`s. Document the
  choice.
- Callers that rely on the argument spelling (`uiop:file-exists-p`, `directory-exists-p`,
  the prelude's `probe-file` uses, ASDF/UIOP lowerings) are checked against SBCL/UIOP and
  either follow or are pinned as deliberate.
- Update the four `probeFile*` expectations, the `.kb/pathnames.md` note, and the
  `doc/en` + `doc/ja` pages that describe `probe-file`.

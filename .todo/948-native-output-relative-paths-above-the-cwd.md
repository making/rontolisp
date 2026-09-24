# `--native`: a relative path cannot climb above the current directory

Difficulty: Medium

Measured 2026-09-24 with the runner stub (`rontolisp-native/`, `.kb/native-output.md`): run
from `sub/`, `(probe-file "../up.txt")` answers NIL and `(open "../up.txt")` signals
"cannot open file", although the file exists. The interpreter and the JVM backend open it.

Cause: the wasm-GC backend resolves every relative path against fd 3
(`.kb/read-load-streams.md`, "a path resolves against the PREOPEN TABLE"), and the stub
preopens `.` there -- a cap-std sandbox that refuses `..` escapes. The `/` preopen covers
absolute paths only. Under `wasmtime run --dir .` this is the host's sandbox by design; a
native executable is expected to behave like any native program.

## Options (decide by measurement)

- Stub side: tell the module its absolute cwd (a preopen NAMED after the absolute cwd, or
  an env var), and have `_path_dirfd` join a relative path containing `..` onto it before
  the preopen-table match, so it resolves through `/`. Keep `.` as fd 3 for everything
  else (cost: nothing on the common path).
- Backend side for every WASI host: normalize `..` lexically against a known cwd -- only
  valid when the host exposes one; `wasmtime run` does not.

## Done when

A `--native` output run from a subdirectory reads `../file` like the JVM output does, and
`wasmtime run` output is unchanged. Pin in `rontolisp-native/precomp/tests/stub.rs` (a
fixture run from a subdirectory) or the 943 E2E.

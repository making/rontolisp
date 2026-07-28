# `probe-file` (and the `uiop:file-exists-p` that rides on it)

Split out of `.todo/201` (§3), which registered `uiop:file-exists-p` as a
package member so postmodern's `execute-file.lisp` RESOLVES and the system
loads, but left it undefined-at-call-time on every backend. This item makes it
real.

## The gap

rontolisp has no way to ask whether a file exists. `open` (and therefore
`with-open-file`) is the only file primitive, and on a missing path it throws on
the JVM and **traps** on WASM (`_open` emits `unreachable` on a non-zero
`path_open` errno), so the `(handler-case (open ...) (error () nil))` idiom
cannot stand in for a probe: a wasm trap is not catchable. That is why this
cannot be done in Lisp on top of what exists -- it needs a primitive.

`probe-file` is the CL-standard spelling and the right primitive;
`uiop:file-exists-p` is then a `LispMacroExpander` lowering onto it (its
contract is the same: the truename on success, `nil` otherwise -- rontolisp
represents a pathname as its namestring, so it returns the path string).

## Scope (the standard new-built-in cycle, all four backends)

- `LispNames` + `PackageRegistry.CL_SYMBOLS`/`CL_FUNCTIONS`.
- Interpreter: `Environment`/`LispEvaluator`, mediated by `SourceLoader` (never
  `Files` directly) so the browser playground's in-memory loader answers too --
  the "attempt to read" pattern `AsdfSystems.locate` already uses.
- JVM: a `_probeFile(Object path)` helper in `JvmIoRuntimeBuilder` +
  `JvmProbeFileCompiler` (`new java.io.File(p).exists()`, path quotes stripped
  the way `_open` does).
- WASM: a `_probe_file` runtime function -- `WasmIoRuntimeBuilder.buildOpenBody`
  with the errno branch returning `nil` instead of trapping, and `fd_close` on
  success -- plus `WasmProbeFileCompiler`. Both Preview 1 and component.
- `BuiltinFunctionWrappers` entry (first-class `#'probe-file`).
- Docs: `doc/{en,ja}/reference/functions/probe-file.md`, `_catalog.yaml`, the
  curated `reference/functions.md` row, then the `-Drontolisp.doc.fix=true`
  helper.
- A `ci-spec.yaml` case so the native-image job covers it (the probe needs a
  file that exists in the E2E working directory -- the driver's own source is
  the obvious candidate).

## Then

- Lower `(uiop:file-exists-p X)` to `(probe-file X)` in
  `LispMacroExpander.expandUiopStubCall`, next to the `get-pathname-defaults`
  case that already sits there, and drop the "stub" wording from
  `LispNames.FILE_EXISTS_P` and the uiop paragraph of `.kb/asdf.md`.
- postmodern's `execute-file` needs `.todo/196` (restart-case) and
  `alexandria:read-file-into-string` before it runs end to end, so this is not
  on `.todo/202`'s critical path -- but it is the last thing between the
  milestone build and a working `pomo:execute-file`.

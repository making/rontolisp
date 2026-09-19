# `uiop:parse-windows-shortcut` / `parse-file-location-info` to upstream's body

Difficulty: Medium

A `.lnk` is parsed by seeking (`(file-position s (+ start offset))`), which is exactly why
`uiop-os.lisp` signals `not-implemented-error` naming `file-position` for both. The
interpreter binary file stream can now seek (`.todo/390`), so the restore is a copy-in of
upstream's body plus a `.lnk` fixture.

## What it takes

- Restore `uiop/os:parse-windows-shortcut` and `uiop/os:parse-file-location-info` to
  upstream's body. `read-null-terminated-string` / `read-little-endian` are already real.
- The two parsers must keep SIGNALLING on the backends that still cannot seek (WASI Preview 1
  and `--component`, per `.todo/876` / `.todo/877`), so the restore is gated -- the `:unix`
  / host-identity family uses `(featurep :rontolisp-wasm)` to distinguish the WASI backends
  (`uiop/os:architecture` already answers `:wasm32` there). The gate is `(if (featurep
  :rontolisp-wasm) (not-implemented-error ...) (upstream body))`, or the parser meaningfully
  signals when `file-position` answers nil rather than silently misreading.
- A `.lnk` fixture in `src/test/resources` for the parser to consume, and both parse arms
  removed from the `not-implemented` assertions:
  `LispEvaluatorTest#evalUiopOsWorkingDirectoryAndTheWindowsShortcutFamily`,
  `JvmLispCompilerTest`'s `uiop:parse-windows-shortcut` pin, ci-spec `uiop-os` (the
  `:not-implemented` row).
- Update `.kb/uiop.md`'s `not-implemented-error` row and the `.lnk` paragraph of
  `doc/en/reference/uiop/os.md` / `doc/ja/reference/uiop/os.md`, removing the
  `file-position` re-evaluation trigger.

## Gate

`uiop:parse-windows-shortcut` over the `.lnk` fixture answers the same structure as upstream,
on every backend that claims file-position support. The `.lnk` reader is pure binary stream
seeking + the two already-real octet primitives.

## Prerequisite

Both host backends seek before this can drop its not-implemented arm on the JVM: the JVM
backend is `.todo/875`, the interpreter one is landed.

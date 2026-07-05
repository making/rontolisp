# 59: Phase 3 unit 5 -- string streams (with-output-to-string / with-input-from-string)

Part of the ASDF Phase 3 split (see `.todo/54-asdf-support.md`, "Phase 3"
section). Wishlist source: `.todo/36-io-extensions.md` (string I/O section;
file-system and pathname functions stay in 36).

## Scope

- `with-output-to-string`: `(with-output-to-string (s) body...)` -> a
  string-builder stream bound to `s`; `princ`/`prin1`/`print`/`terpri`
  /`write-line`/`format` with `s` as the destination append; the form returns
  the accumulated string.
- `with-input-from-string`: `(with-input-from-string (s "text") body...)` ->
  `read`/`read-line`/`read-char`-style consumption from the string.
- `write-string` (function) and `write-to-string` (thin `prin1-to-string`
  alias) while in the area.

## Design sketch

Streams are already opaque backend-local integer handles in the file-stream
handle space on all three backends (`.kb/read-load-streams.md`). The natural
route: add a string-backed stream kind to each backend's stream runtime
(interpreter `Environment` stream table; JVM stream runtime builder; WASM
stream runtime builder) rather than macro-level tricks, so every existing
print/read primitive that already takes a stream handle works unchanged. The
two `with-*` macros then expand like `with-open-file` (open-kind, bind,
unwind via the same shape with-open-file uses, plus a final
`%string-stream-contents` call for output streams).

Check first how `format` and `princ` route their optional stream argument on
each backend -- the macro expansion of `format` (`expandFormat`) may special
case t/nil destinations.

## Wiring checklist

New functions need the full "Adding a New Built-in Function" workflow
(LispNames/PackageRegistry/Environment/Jvm+Wasm compilers/
BuiltinFunctionWrappers); the two macros the usual macro wiring. list-* +
ci-spec introspection updates, docs (en+ja) + catalogs, extend
`.kb/read-load-streams.md`, native E2E (all four backends -- component path
included).

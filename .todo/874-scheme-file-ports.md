# Scheme file ports: `(scheme file)`

Difficulty: Medium

Follow-up of `.todo/873` (ports, `.kb/scheme-frontend.md`, "Ports"), split from
`.todo/826`'s ports row. Keep the invariant: lower to core forms, change no backend,
`scheme-spec.yaml` cases on all four backends, Gauche 0.9.15 `-r7` as the oracle.

## Scope

- `open-input-file`, `open-output-file`, `open-binary-input-file`,
  `open-binary-output-file`, `call-with-input-file`, `call-with-output-file`,
  `with-input-from-file`, `with-output-to-file`, `file-exists?`, `delete-file`, as the
  `(scheme file)` library tag (`SchemeLowering.IMPORTABLE_LIBRARIES`, `(environment ...)`).
- A textual file port is a `%scheme-port` over a CL `open` stream. `open` needs a LITERAL
  `:direction` (`.kb/read-load-streams.md`, "Computed open options"), so each helper
  spells its own literal `open`; `close-port` must then really `close` the stream (today
  it only clears the `open` slot -- string and bytevector ports hold nothing to release).
- Binary file ports: `read-u8` & co over `read-byte`/`write-byte` on a binary `open`
  stream, next to today's bytevector arm (the port's `stream` slot then holds a CL
  stream, not a bytevector -- a kind slot or a separate record decides which).
- `file-error?` answers `#t` for a failed open (CL `file-error`), on every backend; wasm
  paths resolve against the preopens (`--dir`), which the spec driver passes.

## Test plan

- Spec cases writing then reading a file under `/tmp` (built at run time -- a literal
  path is folded at compile time, `.kb/read-load-streams.md`, "Trap: a LITERAL absolute
  path"), `with-output-to-file` restoring the current port on an escape, `file-error?`
  on a missing file.
- Size: a program using no file procedure unchanged (the ports feature gate already
  isolates the port section).

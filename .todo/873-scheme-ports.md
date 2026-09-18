# Scheme ports: first-class ports, string ports, bytevector ports

Difficulty: High

Split off from `.todo/826` (its `ports` row). Keep 826's invariant: lower to core forms,
change no backend, add the cases to `scheme-spec.yaml` (all four backends). Oracle: Gauche
0.9.15 (`gosh -r7`).

## Scope

- Port objects: `current-output-port`, `current-input-port`, `current-error-port` as
  parameter objects, so `(parameterize ((current-output-port p)) ...)` redirects
  `display` & co.
- The optional port argument of `display`, `write`, `write-shared`, `write-simple`,
  `newline`, `write-char`, `write-string`, `read-char`, `peek-char`, `read-line`, `read`,
  `char-ready?`; `write-string`'s `start`/`end`.
- String ports: `open-input-string`, `open-output-string`, `get-output-string`
  (non-destructive, unlike CL's `get-output-stream-string`).
- `port?`, `input-port?`, `output-port?`, `textual-port?`, `binary-port?`,
  `input-port-open?`, `output-port-open?`, `close-port`, `close-input-port`,
  `close-output-port`, `flush-output-port`, `read-string`.
- `call-with-port`; `with-output-to-string` / `call-with-output-string` only if a SICP
  sample or Gauche's R7RS set needs them (they are not R7RS-small).
- Bytevector ports: `open-input-bytevector`, `open-output-bytevector`,
  `get-output-bytevector`, `read-u8`, `peek-u8`, `u8-ready?`, `write-u8`,
  `read-bytevector`, `write-bytevector`.
- Interoperation: `--scheme-standard` library tags, `guard`/`raise` on a closed port,
  bytevectors, `eval` (through the generated table), the run-time `(scheme read)` reader
  reading from a string port (its pushback / fold-case state must be per port, not one
  cell keyed on `*standard-input*`).
- `(scheme file)` only if it falls out cheaply; otherwise a follow-up todo.

## Test plan

- Failing cases first in `scheme-spec.yaml`, expected output from `gosh -r7`.
- `SchemeLoweringTest` / `SchemeBuiltinsTest` for the table rows; `SchemeReferenceTest`
  (a page + catalog entry per new name, both trees).
- Size: a program using no port name compiles to the same class/wasm bytes before and
  after; measure one that does. Record in `.kb/scheme-frontend.md`.

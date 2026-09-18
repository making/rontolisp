# Scheme: bytevectors (`#u8(...)` and the `(scheme base)` bytevector procedures)

Difficulty: Medium

Split off from `.todo/826` (the `bytevectors` row).

## Scope

- Reader: `#u8(...)` literal in `SchemeReader` (self-evaluating), in the run-time
  `(scheme read)` reader in `scheme.lisp`, under `#!fold-case` too.
- `(scheme base)` procedures, tag `base`: `bytevector`, `make-bytevector`, `bytevector?`,
  `bytevector-u8-ref`, `bytevector-u8-set!`, `bytevector-length`, `bytevector-copy`,
  `bytevector-copy!`, `bytevector-append`, `utf8->string`, `string->utf8`.
- `equal?` compares bytevectors by content; `write`/`display` print `#u8(1 2 3)`.
- Visible to `eval` and `--scheme-standard r7rs`.
- Out of scope: binary ports (`open-input-bytevector`, `read-u8`, ...) -- a follow-up.

## Plan

Lower onto the `(unsigned-byte 8)` pack (`.kb/packed-integer-vectors.md`); change no
backend. Run-time helpers in `scheme.lisp`.

## Test plan

- Failing cases first in `scheme-spec.yaml` (all four backends), Gauche `gosh` as the oracle.
- Output size: a program without bytevectors unchanged (class/wasm); one with them measured.
- Docs: `doc/{en,ja}/scheme/` (reader, libraries, deviations) + one reference page per name.

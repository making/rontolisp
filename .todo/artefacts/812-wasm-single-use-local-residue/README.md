# 812: counting the inliner's residue

`residue.sh` counts the two populations the item separates, off `wasm-tools print`:

```bash
./residue.sh a.wasm b.wasm ...
```

- **adjacent-set-get** -- a `local.set N` whose immediately next instruction is
  `local.get N`. This is what `WasmPeephole`'s existing rule would collapse to
  `local.tee N` if anything ran after `WasmInliner`. Do not count these off the raw
  bytes: `0x21`/`0x20` occur as immediates too, and a byte scan reports pairs in zlib
  that the disassembly says are not there.
- **single-assign-single-use** -- one `local.set`/`local.tee` and one `local.get` in the
  whole function. `local.tee` is a read AND a write and is counted in both. This is an
  UPPER BOUND on what a copy-propagation or sinking rule could reach: every one of them
  is non-adjacent, so each has intervening code and needs a legality argument.

Measured 2026-09-14 at `--optimize=size`:

| program | backend | adjacent | single-use |
| --- | --- | ---: | ---: |
| `../810-.../reactor.lisp` | `--no-gc` | 1 | 1 |
| `size-report/programs/pi_approx/pi_approx-nogc.lisp` | `--no-gc` | 0 | 8 |
| `size-report/programs/pi_approx/pi_approx.lisp` | GC | 0 | 4 |
| `size-report/programs/zlib/zlib.lisp` | GC | 0 | 723 |

`dsect.mjs` prints per-section payloads if the code section size is wanted alongside.

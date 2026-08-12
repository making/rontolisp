# %string-concat renders through the value printer, and read-sequence keeps read-char

Difficulty: High

What is left of todo 334's printer question after the spelled-literal gate landed
(`--optimize=size` zlib 77,444 -> 72,457; every name-armed ladder row gone, the
dispatchable set is exactly `valueFuncIds`). The remaining ~3.4 KB of printer/reader
bytes in a program whose only I/O is octets is not gate residue -- it is two designs:

## 1. `_string_concat` IS the capture-mode renderer: ~2.5 KB

`WasmRuntimeBuilder.buildToStringBody` builds `_princ_to_str`, `_prin1_to_str` AND
`_string_concat` as the same shape: turn on `_write_str`'s capture mode and render each
argument through `FUNC_PRINC_VAL`. So any module with one immutable-string rebuild site
(`%schar-set-runtime` rides in on every `(setf (aref var i) v)` spelling) carries the
whole generic value printer: `FUNC_PRINC_VAL` 1,739 + `FUNC_PRINT_F64_NO_NL` 379 +
`FUNC_PRINT_I32/I64_NO_NL` 163+160 + `FUNC_BIG_PRINT`/`_MAG` 170. In zlib the chain is
`CRC32-TABLE -> %SCHAR-SET-RUNTIME -> FUNC_STRING_CONCAT -> FUNC_PRINC_VAL`.

A byte-copy concat is expressible entirely in existing helpers: normalize both args via
`FUNC_CHARVEC_TO_STR` (already live in every such module), `_str_to_mem` both into the
capture area, `_str_fresh` over the result. The open question is the argument CONTRACT:
`%string-concat` is internal, but its lowerings may today pass any value (a symbol
designator, a number?) and get princ semantics. Audit every `fmtCall(STRING_CONCAT`
site in `LispMacroExpander` first; if a non-string can reach it, either keep a princ
fallback arm OUT OF LINE (so the shaker sees it dead when no site can pass one) or
narrow the callers. The JVM/interpreter sides concatenate real strings already, so this
is a WASM-runtime-only change with a four-backend differential obligation.

Probes measured and REVERTED in the 334 session (kept here so they are not re-run):
swapping `(string c)` for a one-element character vector in `scharSetRuntimeDefun` cut
only `FUNC_PRINC_TO_STR` (153 B) and cost +245 B of inline make-array -- worthless
while `_string_concat` itself renders through `FUNC_PRINC_VAL`. Fix the concat engine
first; then `(string c)` is the last `princ_to_str` holder and the same probe becomes
worth re-measuring.

## 2. `FUNC_READ_CHAR` (649 B) in a byte-only reader

`expandReadSequence` chooses read-char vs read-byte on a RUNTIME `(stringp seq)` test
-- deliberate (.todo/219: alexandria allocates the buffer from
`stream-element-type`, so no expansion-time inspection can see it). zlib's buffer is
`(make-array 8192 :element-type '(unsigned-byte 8))` bound one `let*` up, so the char
arm and `FUNC_READ_CHAR` ride in dead. The lever is a certainly-NOT-string judgment
for a LET-BOUND buffer (the designator-propagation shape of
`compiler.LetBoundDesignators`, but for element types), or a
`compiler.StringValuedForms`-style `certainlyNonString` over the seq form consulted by
the expansion's caller. Watch the standing-down cases: a buffer that is a parameter, a
`setq`, or `stream-element-type`-derived stays runtime-tested.

## 3. Bignum + ratio runtime (carried from 333/334)

Reachable only as the overflow fallback of generic arithmetic; chipz declares
`(unsigned-byte 32)` throughout, so type-directed narrowing is available in principle
-- and it changes what an undeclared overflow does, so it is a semantics decision
(todo 331 measured the floor: one `(logand (+ a b) 255)` defun already pays 1,392 B).

## Watch

- A program is verified only on all four backends; zlib needs `-W exceptions=y` on
  both wasm runs; gunzip a fixture (< 8 KB compressed -- the program reads stdin into
  one 8192-byte buffer) and compare byte for byte.
- Measure the row, not the probe: todos 329-334 all delivered from a different
  mechanism than their first probe suggested (334's lever turned out to be the gate's
  probe SOURCE, not the ladders' contents).
- `size-report/measure.sh` re-measures the row; prose travels in
  `size-report/notes/wasm-flags.md`, never `results/`.

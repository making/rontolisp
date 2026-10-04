# c94. A name dispatch arm costs a repeated type test and a linear walk

Difficulty: Medium

Every shared dispatch of a runtime name over a static name set -- `%global-access` (symbol-value,
`set`, the eval runtime's variable access), `%progv-bind-name` / `%progv-unbind-name` -- is
`LispMacroExpander.nameChain`: an `if` per name over `(%symbol-is n 'S)`. On wasm each test
re-does `ref.test` + `ref.cast` + `struct.get` of the SAME parameter before the offset compare,
~22 B of the ~44 B an arm costs (`.kb/dynamic-special-variables.md`, "One home"); the JVM's
`"S".equals(n)` is ~8 B. And the walk is linear in declaration order.

Measured 2026-10-04 (after `.todo/c89`):
- hello-ningle Worker: the accessor is ~200 arms, 9,012 B of wasm (two segments); the change that
  made eval go through it cost the module +16.4 KB (+0.7%, gzip +4.7 KB), the rest the names
  the arms need in the string table. hello-clack: 42 arms, 1,852 B.
- 200k `(eval '(setq *ex* (+ *ex* *e99*)))` over 101 specials, both names last in declaration
  order: JVM 370 ms, wasm 170 ms -- three walks of 101 arms an iteration (the mirror alist it
  replaced found both names at its head: 125 / 43 ms).

Two independent cuts:
- Hoist the key: `(let ((k (%symbol-key n))) ... (%key-is k 'S) ...)` -- wasm the string-table
  offset once (an unboxed i32 local, -1 for a non-symbol), each arm `local.get` / `i32.const` /
  `i32.eq` (~9 B); the JVM keeps `n`.
- Make the walk sublinear: wasm a sorted offset table + binary search into a `br_table`, the JVM
  `hashCode()` into a `lookupswitch` then `equals`. Arms shrink to the variable access itself.

Measure size-report, bench-report, examples and the ci-spec program on P1, `--optimize=size`,
component and JVM before and after; the progv / set / symbol-value pins in
`JvmLispCompilerTest` and `WasmLispCompilerTest` (`*DoNotPayForTheSpecialSet`) bound the
segments, not the arm.

# d11. A package walk normalizes each symbol in about a millisecond on the compiled backends

Difficulty: Medium

`find-all-symbols` / `apropos-list` / `do-all-symbols` (`%package-symbols-where`, prelude in
`LispPreludeLibrary`) run `%package-spelling-normalize` once per distinct row, and in a large
program each call costs ~0.7 ms on WASM. With `_intern` now a hash table this is what owns
the walk.

Measured 2026-10-04, jar, the ci-spec corpus program (66 packages, 16,245 rows, ~2,450
distinct), statements appended after the corpus:

- WASM P1: normalizing the 2,459 distinct rows 1.7 s; `prin1-to-string` of them 0.34 s,
  `symbol-package` 0.07 s; the `%do-symbols-list` rows of every package 0.3 s.
- `runtime-package-api` is still ~2.5 s of the P1 and component legs (4 walks, ~0.55 s each),
  3.8 s on the JVM.
- Per call, corpus program vs. a program of only the probe: `(string< :cl-user :cl)` 34 vs
  1.4 us (JVM 33 us), `(prin1-to-string 'car)` 11 vs 0.6 us (JVM 26 us),
  `(symbol-package 'car)` 14 vs 0.6 us (JVM 18 us), `(list-all-packages)` 11 ms vs ~0.
  So the cost grows with the program's package universe on both compiled backends.

Fix direction: find what scales with the universe in the printer's package-prefix decision,
`symbol-package` and `find-symbol` over a computed package (likely scans of the baked packed
strings or of `%baked-packages%`), and index it; then pin it with a ratio test like
`JvmLispCompilerTest#compileAndRunAPackageWalkCostsItsUniverseNotItsSquare` over a program
with a large baked universe.

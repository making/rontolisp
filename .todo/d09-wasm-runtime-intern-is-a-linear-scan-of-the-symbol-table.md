# d09. WASM runtime intern is a linear scan of the symbol table

Difficulty: Medium

`_intern(off, len)` (`WasmReadRuntimeBuilder.buildInternBody`) scans the compile-time intern
table, then the runtime table, entry by entry; a miss appends. So every runtime `intern`,
`read` of a symbol and package enumeration row costs O(symbols in the program + symbols
interned so far), and interning n fresh symbols is O(n^2).

Measured 2026-10-04 (jar, idle box):

- 32,000 fresh symbols walked by `do-symbols` (the program in
  `JvmLispCompilerTest#compileAndRunAPackageWalkCostsItsUniverseNotItsSquare`): WASM 4.6 s
  per walk, the 64-row-piece half 5.8 s; JVM 0.1 s. Why that test is JVM-only.
- ci-spec corpus, `runtime-package-api`: each `find-all-symbols` / `do-all-symbols` walk
  (16,245 rows) ~1.1 s on WASM, ~4.4 s of the P1 leg's 12.6 s run; the same 16,245 interns in
  a program holding only the corpus prefix take 0.15 s, so the cost is the table size.

Fix direction: a hash index over both tables (open addressing in linear memory, keyed by the
token bytes), keeping the canonical-offset contract (`.kb/symbol-runtime-api.md`, "WASM
canonical-offset discipline") and the host-arena high-water mark. Then add the WASM twin of
the JVM walk-cost test.
Not `.todo/156` (symbol identity): this is the lookup cost of the existing table only.

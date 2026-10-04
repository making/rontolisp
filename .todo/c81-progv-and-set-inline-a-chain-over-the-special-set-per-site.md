# c81. `progv` (and computed `set`) inline a chain over the whole special/global set at each site

Difficulty: Medium

c78 moved a computed `symbol-value` to one shared, segmented dispatch
(`LispMacroExpander.symbolValueDynamicRuntime`). Two siblings still spell an O(set) chain at
EVERY site:

- `progv` (`LispMacroExpander.expandProgvForCompile`): a bind chain and an unbind chain over
  every special, per site. Measured 2026-10-04 (JVM method bytes): ci-spec's `tlf-bound`
  (one `progv`, ~359 specials) is 36,341 B; a top-level form with one `progv` over 600 specials
  is 72,004 B and fails to compile (`_top$0 ... exceeds the JVM's 65535-byte limit`). ci-spec's
  largest top-level chunk is 54,731 B, near the limit.
- computed `set` / `(setf (symbol-value x) v)` (`JvmSymbolApiCompiler.compileSet`,
  `WasmSymbolApiCompiler.compileSet`): a name-equals chain over `ctx.globals` inline per site
  (`.kb/symbol-runtime-api.md`). Not yet measured.

Runtime: the dispatch is a linear `equal` chain. With the shared symbol-value dispatch over 300
specials, 2M lookups (half hitting the last arm) take ~2.1 s on the JVM and ~22 s on wasm
(~11 us a lookup), so `equal` on a symbol is the wasm cost to look at.

Plan:

1. Measure the `set` site cost on the ci-spec corpus.
2. Give `progv` shared runtimes over the special set -- a bind returning the previous state (or
   a "no arm" marker for the mirror path) and an unbind -- segmented like the symbol-value
   dispatch; keep `--reentrant` (`WasmDynVars.emitProgvBind`) and the mirror maintenance.
3. The same for computed `set`.
4. Consider a cheaper name test than `equal` (wasm: the canonical string-table offset).
5. Pin per-site size on the JVM and wasm; record size-report/bench/examples/ci-spec deltas.

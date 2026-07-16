# Purge .todo/NN and todo-number references from Java sources

The standing rule (2026-07-14) is that no `.todo/NN` / todo-number reference
may appear anywhere in code -- comments and Javadoc included, error strings
included; only `.kb/*.md` and `.todo/*.md` may cite todo numbers. About 55
files under `src/main/java`, `src/web/java`, and `src/test/java` still carry
pre-rule references (e.g. `LispNames`, `WasmComponentBuilder`,
`WasmExportCompiler`, `LoadInliner`, `NoGcWasmCompilerTest`), found with:

```bash
grep -rlniE "todo[-/ ]?[0-9]+|\.todo/" src/main/java src/web/java src/test/java
```

Rewrite each comment to carry the information itself (or point at the
matching `.kb` file) instead of the todo number. Two occurrences in
`WasmExprCompiler` were already rewritten this way (2026-07-16). Comment-only
work: no behavior change, but run the format gate afterwards.

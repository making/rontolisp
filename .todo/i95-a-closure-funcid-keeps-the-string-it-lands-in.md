# i95. A closure's funcId keeps the string-blob range it lands in (Preview 1)

Difficulty: High

A closure's creation spells its funcId as `i32.const` (`TYPE_CLOSURE` field 0 is i32;
`WasmLambdaCompiler.emitClosureValue`, `WasmQuoteCompiler.compileSharedFunctionBox`, the async
entries). On Preview 1 and `--no-wasi` the funcIds fall inside the string blob at 256, so the
shaker's citation scan keeps whatever shakeable string each live funcId hits
(`.kb/optimize-dead-code-elimination.md`, "String blob: droppable ranges"; numbers there: 33
dead bytes on a 906-byte reactor). The ladders already moved their ids to `i64.const` for the
same reason ("The ids are i64 constants, never i32").

Candidate: make the funcId field i64. Creation stays one LEB constant, the dispatch bias and
compares lose their `i64.extend_i32_u`, and no funcId is an `i32.const` any more. Every reader
of field 0 changes (about 40 `TYPE_CLOSURE` sites in 11 files, plus raw readers in
`WasmRuntimeBuilder`); P1 and component both, `.kb/running-backends.md`. Whether it pays is a
corpus size question (`.kb/size-measurement.md`); decide on those numbers.

# m47. A Java `@WitExport` world on `--no-gc --component`

Difficulty: Medium

A Java reactor compiles `--no-gc` to a core module (`.kb/java-frontend.md`, "A reactor on
`--no-gc`"); `--no-gc --component` refuses a Java program by name (`CompileFrontend.run`). Its
exports would be a `@WitExport` world (`.kb/java-frontend.md`, "WIT exports"), whose lowering
wraps the top level in `(%reporting-uncaught #'line nil init..)` so that an export call writes
the line a command writes before it traps. The scalar backend has no condition to write a line
from: an error there is a trap, nothing printed.

Decide whether the scalar component's uncaught report is the bare trap (as the `--no-wasi`
reactor of public static methods keeps its silent trap), and if so make the wrapper's forms
the start function's; then measure the component of `examples/java/rust-calls-java`'s counter
against its wasm-GC one (7,853 B, 15,056 with the report).

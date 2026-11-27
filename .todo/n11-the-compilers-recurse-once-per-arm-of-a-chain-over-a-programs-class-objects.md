# n11. The compilers recurse once per arm of a chain over a program's class objects

Difficulty: Medium

The Java front end lowers the tables of reflection (`%array-of`, `%class-members`,
`%class-constructor`, `%declared-methods`, ..) as ONE `if` chain over the program's class
objects, an arm a class, and the passes after it recurse once per arm:
`LispMacroExpander.settleTail` / `settleIf` (two frames an arm) and
`WasmLetCompiler.collectAssignedValues` (one). A program of enough class objects overflows
the stack of a compile that runs on a thread of the default size.

Measured 2026-11-27 (m83; a harness calling `CompileFrontendAccess.file` and
`WasmLispCompiler.compile` on a thread of a given stack):

- The corpus case `a-file-of-tests-is-run-and-reported-as-junit-and-assertj-do` has 1,237
  class objects (5.5 MB of IR) and compiles on 1 MiB.
- The same with two more test files (1,456 class objects, 7.3 MB of IR) overflows 1 MiB
  in `settleTail` (948 frames deep where it stopped) and passes on 2 MiB; under surefire
  the `WASM` leg of `JavaSpecE2eTest#standalone` threw `StackOverflowError` in
  `collectAssignedValues` while the `WASM_COMPONENT` leg of the same program passed --
  at the edge, which leg overflows depends on what is compiled already. m83 put its tests
  in a case of their own for that reason (and for the lowering it changes).
- The CLI is not reached today: it compiles on a thread of its own stack
  (`RontoLispCli.LaunchStack`, `--stack`).

Either the chains are made shallow (a table by the class object's number, or a chain
split into functions of N arms, as `%jdk-methods`' suspending arms were split), or the
passes walk an `if` chain's else branch in a loop. Decide by measuring the size both ways
on that corpus case. The margin today is under 18% of the class objects.

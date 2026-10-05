# d31. On WASM a closure whose creator was shaken keeps its ladder arm

Difficulty: Medium

The JVM half is done: a dispatcher case there is followed only while a kept body makes its
value (`.kb/optimize-dead-code-elimination.md`, "A dispatcher case lives while a kept body
makes its value"). WASM has the same source of the case -- a funcId joins `valueFuncIds` when
Pass 2 compiles its `(lambda ...)`, wherever that code sits -- and the tree shaker follows the
ladder's `call` to every arm, so a closure made only in a function the shake drops (a built-in
wrapper body the program never calls: `reduce :from-end`'s argument-swapping lambdas) keeps its
function and everything it reaches. Not measured on WASM yet; on the JVM the same closures were
bench-report `clos`/`sort`/`string` -8..-11%, `zlib` -1.6%, the HTTP-server examples up to -41%.

## Plan

- Measure first: per module, the closure functions whose only `call` is a ladder arm and whose
  creating `struct.new`/funcId constant sits in no kept function (bench-report, size-report,
  the examples' wasm legs, ci-spec).
- If it pays: the shake learns the same edge -- an arm is live while a kept function makes the
  value (the creator's funcId constant, the registry blob's rows) -- and the ladder is rebuilt
  over the live arms, P1 and component alike. Output pinned identical on all four backends.

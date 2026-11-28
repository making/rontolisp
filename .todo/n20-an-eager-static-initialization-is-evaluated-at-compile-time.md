# An eager static initialization is evaluated at compile time

Difficulty: High

`JavaInitOrder` already proves a class's initialization EAGER when it prints nothing, throws
nothing, writes no other class's static field and reads only final immutable fields of other
classes (`.kb/java-frontend.md`, "Static initialization"). Such an initialization computes the
same values on every run, yet every output runs it at start (`%initialization` before `main`):
a lookup table, a `Map` filled from constants, an enum's values, a pattern compiled from a
literal, a library's static tables.

Evaluate it once, at compile time, with the compile-time evaluator, and emit the values it
leaves in the static fields as literal data, so the output neither runs nor carries the code
that made them (the shaker then drops what only the initialization called).

- **First measure**: on `examples/java/shop`, the YAVI programs and a corpus case with a large
  static table, the share of eager initialization in start-up time and in output bytes (the
  functions only it reaches). Small: record it in `.kb/java-frontend.md` and close.
- What a value may be: numbers, characters, strings, vectors, the program's structures, hash
  tables (insertion order kept, `.kb/hash-tables.md`), shared and circular references kept
  shared. A value that is none of these (a closure, a stream, a host object) leaves that class
  initialized at run time as today. Whether each backend can emit such a literal (WASM data
  and constant expressions, JVM `<clinit>` or a resource, the interpreter as is) decides the
  set; `.todo/171` (structure print and read syntax) is related.
- Identity: a value made at compile time must not be `eq` to a literal of the program that the
  run-time initialization would not have shared.
- The same pass may serve a Lisp program's top-level `defparameter`/`defvar` of a pure form;
  decide whether to include it or file it.
- Pin with the corpus (output unchanged on four backends) and a size test; `.kb` with the numbers.

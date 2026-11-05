# i73. coalton on the compile paths

Difficulty: High

Measured 2026-11-05 (develop with i58's work), `-o Prog.class` of a program that quickloads coalton and runs one
`coalton-toplevel`: the compile stops within a minute, before any of the standard library is typechecked.

- `library/types.lisp:20:1: the reader macro function signalled: No applicable method: CONCRETE-SYNTAX-TREE:RAW on
  SYMBOL`. The read-time image (`.kb/readtables.md`, "The compile paths' read") runs coalton's
  `coalton-toplevel` reader macro, whose eclector-CST read answers a plain datum there.
- eclector alone does not compile: `(ql:quickload "eclector")` and `(eclector.reader:read-from-string "(a b)")`
  with `-o x.class` fail `DEFMETHOD is only supported as a top-level form` (a `defmethod` reaches
  `JvmExprCompiler` below top level). A top-level `macrolet` or `locally` around methods compiles (probed), so it is
  some other wrapper in eclector's sources.
- Past those: a `coalton-toplevel` is expanded at READ time, its reader macro running coalton's typechecker over the
  global environment every earlier form built. The read-time image would run the whole library as the interpreter
  does (about an hour on a loaded box, `.kb/asdf.md`, "Libraries that load"), the real pass then reprocesses what it
  answered, and the program carries the generated code of the whole library. Whether that is a reasonable thing for
  a compile to do at all is the first question.

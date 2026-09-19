# macOS: hand-check the GUI outputs after the sized-main launcher

Difficulty: Low

The compiled `main` now runs the program on a 16 MiB worker thread
(`JvmSizedMainBuilder`, `.kb/interpreter-stack.md`). A program reaching `objc:`/`appkit:`
is excluded, and its outputs were compared byte for byte on linux-x64 (2026-09-19):
`examples/macos/counter.lisp` as `-o Counter.class --class-name Counter` and as
`-o counter.jar` are identical before and after. `JvmSizedMainTest#anObjcProgramKeepsItsMainOnThreadZero`
pins the exclusion. The change was made on Linux, so the manual check CLAUDE.md requires
("After Task Completion") was not run.

## To do

- On a Mac, run `examples/macos/counter.lisp` on `java -jar`, the native binary, `java Counter`
  and `java -jar counter.jar`: the window opens, clicks count, closing it exits.
- Also run one NON-objc compiled program there (`java Prog`, `java -jar prog.jar`): on macOS the
  `java` launcher already runs `main` off thread 0 while thread 0 parks in a `CFRunLoop`, and the
  launcher now adds a second hop. Expected harmless; confirm the output and the exit code.
- Optional, only if it is wanted: whether an objc program could also move onto the worker (under
  `java` its `main` is already not thread 0 on macOS, and every AppKit entry hops through
  `MainThread.sync`). That is a GUI change and needs this same manual check.

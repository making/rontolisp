# `uiop/launch-program`: a child kept running while the program goes on

Difficulty: Medium

**What landed (2026-11-07, with the real groveller, `.kb/cffi.md`):** 16 of the 26
externals of `uiop/launch-program` + `uiop/run-program`. The escape family
(`escape-*`, `easy-sh-character-p`) is real on all four backends; `run-program`,
`slurp-input-stream`, `vomit-output-stream`, `subprocess-error` and its three readers
are real, `run-program` starting a program on the JVM family (interpreter, native
binary, compiled class, one spawn: `runtime/RontoProcess`) and signalling
`not-implemented-error` on both WASM backends. The decision this item had left open
-- uniform signal or JVM family real -- was taken for the JVM family: the groveller is
plain Lisp over `run-program`, so the interpreter needs it, and a compiled class
starting one too keeps the divergence WASM's alone, the `ffi:` line (`.kb/uiop.md`,
"uiop/launch-program and uiop/run-program").

## What is left: the asynchronous half, 10 externals

```
uiop/launch-program CLOSE-STREAMS LAUNCH-PROGRAM PROCESS-ALIVE-P PROCESS-INFO
                    PROCESS-INFO-ERROR-OUTPUT PROCESS-INFO-INPUT
                    PROCESS-INFO-OUTPUT PROCESS-INFO-PID
                    TERMINATE-PROCESS WAIT-PROCESS
```

Still stubs. `launch-program` hands back a `process-info` whose `:stream` input and
outputs the program reads and writes while the child runs, and `wait-process` /
`terminate-process` / `process-alive-p` act on it. Under the policy above it is the
JVM family's too: a `java.lang.Process` behind a `process-info` instance, its pipes as
Lisp streams (a Gray-stream-shaped wrapper on the interpreter; on the JVM backend a
runtime class over the program's stream representation), `run-program`'s non-`:stream`
specs reused. WASM keeps the signal.

Consumers to measure first: what in the Quicklisp cache calls `launch-program` (the
inferior-shell / `uiop:run-program` split; lisp-binary's build steps), so the stream
shapes they read decide the wrapper.

## Gate

`UiopCoverageTest` reports both sub-packages complete. A `launch-program` program --
write to the child's input, read its output line by line, `wait-process` -> exit code,
`terminate-process` of a `sleep` -> `process-alive-p` nil -- answers SBCL 2.2.9's on the
interpreter and the JVM (a `testsupport` program like `RunProgramPrograms`), and both
WASM backends signal `not-implemented-error` naming the reason.

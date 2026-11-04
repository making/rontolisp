# i22. SIGINT in a compiled JVM program

Difficulty: Medium

Measured 2026-11-04 (`.kb/error-handling.md`, "SIGINT"). A compiled class names
`rontolisp:interactive-interrupt` and can signal it, but a real SIGINT ends it with status 130 and no handler
runs. The interpreter and SBCL run the handler. Consequence for clingon: the status is right, the
`Received SIGINT, exiting.` line and the cleanups are missing.

A JVM can receive the signal (`sun.misc.Signal`) but compiled code has no poll and `Thread.stop` is gone. The
candidate is a poll at function entry and loop back edges, emitted only in a program naming the condition, so
any other program stays byte-identical. Weigh that blast radius against the one visible difference above; the
two WASM backends have no signals and stay as they are.

# `uiop/launch-program` + `uiop/run-program`: subprocesses, and the escaping that does not need one

Difficulty: Medium

Depends on `.todo/353`, `.todo/354`, and `.todo/359` (`slurp-input-stream` /
`vomit-output-stream` are stream plumbing).

26 externals across the two; one present -- `run-program`, which is one of the
three *undefined function* stubs `.todo/353` exists to abolish. The **25**
missing:

```
uiop/launch-program CLOSE-STREAMS EASY-SH-CHARACTER-P
                    ESCAPE-COMMAND ESCAPE-SH-COMMAND ESCAPE-SH-TOKEN
                    ESCAPE-SHELL-COMMAND ESCAPE-SHELL-TOKEN ESCAPE-TOKEN
                    ESCAPE-WINDOWS-COMMAND ESCAPE-WINDOWS-TOKEN
                    LAUNCH-PROGRAM PROCESS-ALIVE-P PROCESS-INFO
                    PROCESS-INFO-ERROR-OUTPUT PROCESS-INFO-INPUT
                    PROCESS-INFO-OUTPUT PROCESS-INFO-PID
                    TERMINATE-PROCESS WAIT-PROCESS
uiop/run-program    SLURP-INPUT-STREAM VOMIT-OUTPUT-STREAM SUBPROCESS-ERROR
                    SUBPROCESS-ERROR-CODE SUBPROCESS-ERROR-COMMAND
                    SUBPROCESS-ERROR-PROCESS
```

## Split the item by what needs a process

**Ten of the 26 are pure string functions** -- the `escape-*` family plus
`easy-sh-character-p`. They quote a token or a command line for `sh` or for
Windows and never spawn anything. They are portable, testable, run on all four
backends, and a program that builds a command line for something else to run
(a Makefile, a `.desktop` entry, a log line) uses them without a subprocess.
Do these for real, first.

**`slurp-input-stream` / `vomit-output-stream`** are the generic
stream-to-designator pumps `run-program`'s `:output`/`:input` arguments dispatch
through. Also portable -- they belong with `.todo/359`'s designator helper and
should share it.

**`subprocess-error` and its three readers** are condition classes: real, and
needed even by a program that only handles the error.

**The process family cannot be honest anywhere yet.** `launch-program`,
`run-program`, `process-info` + its four readers, `wait-process`,
`terminate-process`, `process-alive-p`, `close-streams`: no rontolisp backend
spawns a process. The JVM *could* (`ProcessBuilder`), and that is the decision
this item must make deliberately rather than by default:

- **Uniform**: `not-implemented-error` on all four, with "no backend spawns
  processes" as the reason. Cheap, honest, and keeps the cross-backend rule.
- **JVM + interpreter real, WASM signalling**: more useful for scripts, but it
  buys a permanent four-backend divergence in a family whose whole contract is
  side effects on the host -- and `.kb/uiop.md` would have to carry it forever.

Recommend the uniform answer and take it: a program that works on the
interpreter and dies when compiled is worse than one that fails the same way
everywhere, and WASI's `wasi:cli` has no process API to converge on later. If
that is ever revisited, the trigger is a WASI process proposal -- write that
into `.kb/uiop.md` as the re-evaluation condition.

## Gate

`UiopCoverageTest` reports both sub-packages complete with no undefined-function
residue. `LispEvaluatorTest` pins the `escape-*` family against upstream's own
outputs (quote a token with a space, a single quote, a `$`); a `ci-spec.yaml`
case runs `escape-sh-command` on all four backends, and one asserts that
`run-program` signals `not-implemented-error` rather than *undefined function* --
that assertion is the point of the item.

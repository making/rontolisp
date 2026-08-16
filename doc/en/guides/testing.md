# Testing (rove)

[Rove](https://github.com/fukamachi/rove) — Eitaro Fukamachi's testing
framework, the successor of Prove — loads verbatim via `(ql:quickload "rove")`
(v0.10.0), and a test suite written in its shape runs with the spec reporter on
all four backends: the interpreter, a compiled JVM class, WASM Preview 1 and a
WASI 0.3 component. Its dependencies resolve automatically: cl-ppcre and
[dissect](https://github.com/Shinmera/dissect) from their real sources, uiop /
trivial-gray-streams / bordeaux-threads to the built-in shims.

## Running a suite: `rontolisp test`

`rontolisp test TARGET` runs a rove target and **exits with its verdict** — 0
when every test passed, 1 when one did not. It is this tree's version of rove's
own `roswell/rove.ros` script, so a CI step, a `make test` or a git hook can
read `$?`:

```bash
rontolisp test tests/main.lisp     # a test file
rontolisp test my-app.asd          # the system the .asd is named after
rontolisp test my-app/tests        # an ASDF system designator
```

| Target | What runs |
| --- | --- |
| `FILE.lisp` | The file is loaded. If its `defpackage` names an ASDF system on the search path, that system is loaded and tested instead (ASDF's package-inferred rule); otherwise the suite of the file's own package is run — unless the file already ran it, which is detected, so its tests run exactly once |
| `FILE.asd` | The system the file is named after |
| `SYSTEM` | `asdf:load-system` + `asdf:test-system`, then `rove:run` for a system that declares no `:perform (test-op ...)` |

The status is **0** when every test passed, **1** when one failed, when the
program signalled, or when *no test ran at all* — a suite that stopped
registering its tests is a failure rather than a vacuous pass — and **2** when
the command line itself was wrong.

| Option | Meaning |
| --- | --- |
| `-r`, `--reporter spec\|dot\|none` | rove's reporter style, `spec` by default |
| `--disable-colors`, `--color` | Force the ANSI colors off / on. The default follows the destination: a terminal gets them, a pipe does not |
| `--system-path DIRS` | Directories searched for `NAME.asd` (like `PATH`) |
| `-o FILE` | Compile the run instead of performing it (see below) |

A plain `rontolisp FILE` is unchanged and keeps Common Lisp semantics: the value
of the last top-level form is dropped and the status stays 0, exactly as
`sbcl --script` drops it. A program that wants a status of its own says
`uiop:quit`.

## Writing tests

The full assertion surface works: `deftest`, `testing`, `ok`, `ng`, `signals`
(with a user-defined condition class or a built-in one like `'type-error`),
`outputs`, `expands`, `pass`, `fail`, `skip`, `failing`, `setup`, `teardown`,
`defhook` and `diag`. An assertion whose form signals mid-evaluation is recorded
as a failure with its condition — the run continues.

```console
$ cat tests/main.lisp
(defpackage #:my-app/tests/main
  (:use #:cl
        #:rove
        #:my-app/main))
(in-package #:my-app/tests/main)

(deftest add-test
  (testing "adding two integers"
    (ok (= (add 1 2) 3))
    (ng (= (add 1 2) 4))))

(deftest parse-token-test
  (testing "invalid tokens"
    (ok (signals (parse-token "") 'app-error)
        "Parse error")))
```

## The two entry points

**System-driven** — `rove:run` takes an ASDF system designator, loads it, and
runs every suite it contains. Both system shapes work: a
`:package-inferred-system` (the suite is found through the system's package
dependencies) and a plain `defsystem` test system (the suite is found through
the file-to-package map rove records per `deftest`, keyed on `*load-pathname*`):

```console
* (rove:run :my-app/tests)
```

**File-driven** — the README FAQ style: end the test file with `run-suite`, so
loading the file runs it:

```console
(rove:run-suite *package*)
```

`rove:run-test` (one test symbol) and `rove:run-tests` (a list) work too. Each
entry point returns whether everything passed as its first value.

For non-interactive output, turn the ANSI colors off first — rove's default is
colors ON outside Emacs (`rontolisp test` already does this whenever its output
is not a terminal):

```console
(setf rove:*enable-colors* nil)
```

## Running on the four backends

A compiled test program is self-contained: the systems named by top-level
`asdf:load-system` calls are spliced in at compile time, and rove's own runtime
`load-system` of an already-loaded system is a no-op. Point `--system-path` at
the directories holding the `.asd` files (the app under test, rove, dissect,
cl-ppcre).

`rontolisp test -o` compiles the run instead of performing it, and the emitted
artifact carries the same exit contract; every compiler flag applies:

```bash
SP="path/to/my-app:path/to/rove:path/to/dissect:path/to/cl-ppcre"
T="rontolisp test --system-path $SP tests/main.lisp"

# 1. Interpreter
$T

# 2. JVM
$T -o Tests.class && java Tests

# 3. WASM Preview 1
$T -o tests.wasm && wasmtime run -W gc=y -W exceptions=y tests.wasm

# 4. WASI 0.3 component
$T -o tests-comp.wasm --component && \
  wasmtime run -W gc=y -W exceptions=y tests-comp.wasm
```

Both WASM runs need `-W exceptions=y`: rove records a failing test through
`handler-bind`, which puts the module in EH mode. A test program that is its own
runner (below) compiles the same way with a plain `rontolisp`, no `test`.

## The exit code

`rontolisp test` owns the exit code, and that is where it belongs: a
`uiop:quit` written inside a test file kills the process the moment anything
*else* loads that file — another suite, the REPL, a system that depends on it.
The file owns the tests; the runner owns the exit. Upstream draws the same line:
`rove.ros` calls `uiop:quit`, and a `.asd`'s `:perform (test-op ...)` leaves the
exit to whoever invoked ASDF.

Writing it by hand is right in exactly one place: a one-line runner of your own.
`rove:run` returns the passed-p boolean, and `uiop:quit` really ends the process
on every backend:

```console
(uiop:quit (if (rove:run :my-app/tests) 0 1))
```

## Examples that check themselves

Three examples in this repository are written this way, and the example harness
runs them on every backend — copy whichever shape fits:

| Example | Shape |
| --- | --- |
| [`examples/console/roman.lisp`](https://github.com/making/rontolisp/blob/develop/examples/console/roman.lisp) | A program that prints its demo and then asserts what it printed |
| [`examples/cloudflare-workers/httpbin/check.lisp`](https://github.com/making/rontolisp/blob/develop/examples/cloudflare-workers/httpbin/check.lisp) | A driver: it `load`s the program under test, exercises it and asserts the parsed answers |
| [`examples/browser/minesweeper/minesweeper-core-test.lisp`](https://github.com/making/rontolisp/blob/develop/examples/browser/minesweeper/minesweeper-core-test.lisp) | A test file beside a program that cannot run head-less, over the rendering-free core it shares |

None of them defines a package or an ASDF system. For a single file,
`(use-package :rove)` in `cl-user` plus `run-suite *package*` at the end is the
whole of it:

```console
(asdf:load-system :rove)
(use-package :rove)
(setf *enable-colors* nil)

(deftest arithmetic
  (testing "adding two integers"
    (ok (= (add 1 2) 3))))

(uiop:quit (if (run-suite *package*) 0 1))
```

## Limitations

- **A raw WASM trap ends the run.** On the interpreter and the JVM a test body
  that hits `(car 1)` or `(/ 1 0)` becomes a recorded failure; on the WASM
  backends those compile to raw traps, which no handler can catch. A test that
  SIGNALS (any `error` call, `check-type`, a bad `aref`) is recorded fine
  everywhere.
- **No backtraces in failure reports** — dissect's stack introspection is the
  empty no-op interface on every backend, so the `at file:line` / stack lines
  SBCL prints are absent.
- **Symbols in assertion descriptions print package-qualified**
  (`Expect (= (MY-APP/MAIN:ADD 1 2) 3) ...` where SBCL prints `(= (ADD 1 2) 3)`)
  — the printer does not yet consult `*package*` accessibility.
- **`deftest`'s `:compile-at :run-time` option is interpreter-only** — it routes
  the body through `compile`, whose eval runtime cannot expand user macros on
  the compiled backends.
- **`:style :none` on a compiled program** needs the program to load
  `rove/reporter/none` itself — `make-reporter` loads an unknown style's system
  at run time, which only the interpreter can do. `:spec` (the default) and
  `:dot` are built in. `rontolisp test -r none -o ...` loads it for you.
- **A `handler-case` inside a test body does not shadow rove's recorder.** rove
  wraps each test body in a `handler-bind`, and an intervening `handler-case`
  does not yet stop that outer handler from running — so code under test that
  catches its own error (a parse with a fallback, say) is reported as "Raise an
  error while testing." and the test ends there, on every backend. Drive such
  code *before* the test and assert the value it returned;
  `cloudflare-workers/httpbin/check.lisp` above does exactly that.

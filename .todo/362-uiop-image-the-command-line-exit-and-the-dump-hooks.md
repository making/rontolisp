# `uiop/image`: the command line, exit, backtraces and the dump hooks

Difficulty: Medium

**`.todo/353` (the skeleton) has landed.** The 15 sub-packages are registered,
the target is the checked-in `uiop-exports.txt` (435 export rows / 429 distinct
symbols), and every export nothing implements yet already signals
`uiop:not-implemented-error` naming the operation -- so this item REPLACES stubs,
it does not add names. Read `.kb/uiop.md` first: a definition carries its HOME
sub-package's spelling, and a new one goes in that sub-package's `.lisp` resource.
Measured coverage here today (`UiopCoverageTest.printCoverage`, the authority for
every count below): **1 / 30 (`print-condition-backtrace`)**.

Depends on `.todo/353`, `.todo/354`.

30 externals; one present (`print-condition-backtrace`, lite -- it prints the
condition and no frames). The **29** missing:

```
ARGV0 COMMAND-LINE-ARGUMENTS *COMMAND-LINE-ARGUMENTS*
RAW-COMMAND-LINE-ARGUMENTS SETUP-COMMAND-LINE-ARGUMENTS
QUIT DIE SHELL-BOOLEAN-EXIT *LISP-INTERACTION*
PRINT-BACKTRACE RAW-PRINT-BACKTRACE
FATAL-CONDITION FATAL-CONDITION-P HANDLE-FATAL-CONDITION
CALL-WITH-FATAL-CONDITION-HANDLER WITH-FATAL-CONDITION-HANDLER
CREATE-IMAGE DUMP-IMAGE RESTORE-IMAGE *IMAGE-DUMPED-P*
*IMAGE-DUMP-HOOK* *IMAGE-RESTORE-HOOK* REGISTER-IMAGE-DUMP-HOOK
REGISTER-IMAGE-RESTORE-HOOK CALL-IMAGE-DUMP-HOOK CALL-IMAGE-RESTORE-HOOK
*IMAGE-ENTRY-POINT* *IMAGE-PRELUDE* *IMAGE-POSTLUDE*
```

## Three groups, three answers

**The command line is real and is the reason to do this item.** `argv0`,
`command-line-arguments`, `raw-command-line-arguments`,
`*command-line-arguments*` and `setup-command-line-arguments` are how a script
reads its arguments, and rontolisp has no other spelling of that today. It must
work on all four backends: the JVM has `String[] args`, WASI Preview 1 has
`args_get`/`args_sizes_get`, and the component world has
`wasi:cli/environment@0.3.0`'s `get-arguments` -- the same import
`environment.lisp` already uses for `getenv`, so the pattern to follow is right
there (`EnvironmentLibrary`, `.kb/wasi-component.md`).

**Exit is real**: `quit` (with `:unix-status`), `die` (message + status) and
`shell-boolean-exit`. Each backend has an exit path; wire them, and check what
`quit`'s `finish-output` obligation means for the component adapter's buffered
writer -- an exit that drops buffered stdout is the classic bug here.

**Backtraces are lite and stay lite.** `print-backtrace` /
`raw-print-backtrace` join `print-condition-backtrace` in printing the condition
without frames, because no backend carries Lisp-level frames. The fatal-condition
quartet (`fatal-condition`, `fatal-condition-p`, `handle-fatal-condition`,
`with-fatal-condition-handler`) is real on top of `handler-bind` -- it is
`*lisp-interaction*`-gated error reporting, not frame walking.

**The image family is `not-implemented-error`**, and this is the clean case for
`.todo/353`'s house style: there is no image to dump. Make the hooks
(`register-image-dump-hook`, `*image-dump-hook*`, `call-image-dump-hook`,
`*image-restore-p*` and friends) REAL anyway -- they are just hook lists, a
library may register into one at load time, and only the act of dumping is
impossible. `*image-dumped-p*` is nil.

## Gate

`UiopCoverageTest` reports `uiop/image 30/30`. `ci-spec.yaml` gains a case
printing `(uiop:command-line-arguments)` with arguments passed on all four
backends -- the driver already runs each backend with its own launcher, so this
also pins that argument passing agrees, which nothing tests today.

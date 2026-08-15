# uiop/image

`uiop/image` is what a program does at its edges: end the process with a status
code, report a condition nobody handled, and register work to run when an image
is restored or dumped. **25 of the 30 exports are implemented**; the five that
are not are the command-line family, named at the [bottom of this
page](#what-is-missing-the-command-line).

Every name is reachable through either spelling: `uiop:quit` and
`uiop/image:quit` are the same function
([The uiop Package](../uiop.md#sub-packages)).

Three decisions here are rontolisp's own, and each is a decision rather than a
gap:

- **`uiop:quit` is the host's exit on all four backends**, and nothing runs after
  it — see [Exiting](#exiting).
- **Backtraces carry no frames.** No backend keeps a Lisp-level call stack, so
  the honest rendering of "the backtrace for this condition" is the condition and
  nothing else. Real UIOP's own fallback for an implementation without a
  backtrace API has the same shape.
- **There is no image to dump, restore or create**, so those three signal — but
  the hooks around them are real, because registering into one is just a list
  push. See [Image hooks](#image-hooks).

## Exiting

| Function | What it does |
|----------|--------------|
| `uiop:quit` | end the process with a status code (`0` by default), after finishing the standard output streams |
| `uiop:die` | report a `format` message on `*error-output*`, then quit with the given code |
| `uiop:shell-boolean-exit` | quit with `0` when the argument is true and `1` when it is `nil` — a shell's idea of a boolean |

```console
$ cat quit.lisp
(print :before)
(uiop:quit 3)
(print :after)
$ rontolisp quit.lisp
:BEFORE
$ echo $?
3
```

The same program compiled to a class, to a Preview 1 module or to a component
prints the same line and exits `3`: the primitive underneath is `System.exit`
on the JVM, `proc_exit` on WASM Preview 1 and `wasi:cli/exit`'s
`exit-with-code` under `--component`, and the interpreter raises an exit signal
the CLI turns into the process code.

Two consequences follow from that being a real host exit, and they hold on every
backend:

- **Nothing runs afterwards.** An `unwind-protect` cleanup around a `quit` does
  not run — the process ends where the call stands.
- **It is not a condition.** `handler-case`, `ignore-errors` and a `catch` tag
  cannot see it, so a `quit` inside a library's error handling still quits.

The status code is masked to eight bits, which is what a POSIX host does with it
anyway and what `wasi:cli/exit`'s `u8` accepts: `(uiop:quit 300)` exits `44`
everywhere rather than 300 on one backend and 44 on another.

A test runner's exit code is the usual reason to reach for this:

```console
$ cat run-tests.lisp
(uiop:quit (if (rove:run :my-app/tests) 0 1))
```

`uiop:quit` needs a host process to end, so it is **refused at compile time**
under `--no-wasi` and `--no-gc`: those emit a reactor whose entry points are
exports a host calls, and a reactor returns from an export rather than exiting.

## Fatal conditions

A *fatal condition* is a `serious-condition` — the type is a `deftype` alias, so
`typep` and a `handler-bind` clause both match it.

| Name | What it does |
|------|--------------|
| `uiop:fatal-condition` | the type: `serious-condition` |
| `uiop:fatal-condition-p` | `(typep c 'uiop:fatal-condition)` |
| `uiop:handle-fatal-condition` | report the condition on `*error-output*` and `uiop:die` with status `99` |
| `uiop:call-with-fatal-condition-handler` | call a thunk with that handler bound |
| `uiop:with-fatal-condition-handler` | the macro over it: `(uiop:with-fatal-condition-handler () body...)` |
| `uiop:*lisp-interaction*` | `nil` |

```lisp
(list (uiop:fatal-condition-p (make-condition 'error))
      (uiop:fatal-condition-p (make-condition 'warning))
      (uiop:fatal-condition-p 42))   ; => (T NIL NIL)
```

`uiop:*lisp-interaction*` is `nil` here where upstream defaults to `t`. It asks
"is this an interactive Lisp environment, or is it batch processing?", and every
rontolisp backend runs a program and ends: there is no debugger to enter and no
REPL underneath a compiled artifact. That value is what makes
`uiop:handle-fatal-condition` report and exit rather than call an
`invoke-debugger` that does not exist.

```console
$ cat fatal.lisp
(print :start)
(uiop:with-fatal-condition-handler ()
  (error "the sky is falling"))
(print :unreachable)
$ rontolisp fatal.lisp
:START
Fatal condition:
the sky is falling
the sky is falling
the sky is falling
$ echo $?
99
```

The condition appears three times because upstream prints it three times — once
as the report, once with the backtrace, once as `die`'s message — and the middle
one has no frames above it here.

## Backtraces

| Function | What it prints |
|----------|----------------|
| `uiop:raw-print-backtrace` | the `:condition` argument, when there is one |
| `uiop:print-backtrace` | the same, through `uiop:raw-print-backtrace` |
| `uiop:print-condition-backtrace` | its condition argument, on `:stream` (`*error-output*` by default) |

```lisp
(let ((report (with-output-to-string (s)
                (uiop:print-condition-backtrace
                 (make-condition 'simple-error :format-control "boom")
                 :stream s))))
  (string-right-trim (list #\Newline) report))   ; => "boom"
```

`:count` is accepted and ignored: there are no frames to limit.
`lack-middleware-backtrace` is the library that reaches for this — its error
report opens with `uiop/image:print-condition-backtrace`, and here that report
is one line long.

## Image hooks

| Name | What it does |
|------|--------------|
| `uiop:register-image-restore-hook` | push a function onto `uiop:*image-restore-hook*`, calling it now unless the second argument is `nil` |
| `uiop:register-image-dump-hook` | push a function onto `uiop:*image-dump-hook*`, calling it now only if the second argument is true |
| `uiop:call-image-restore-hook` | call the restore hooks, in registration order |
| `uiop:call-image-dump-hook` | call the dump hooks |
| `uiop:*image-restore-hook*` / `uiop:*image-dump-hook*` | the two lists |
| `uiop:*image-prelude*` / `uiop:*image-entry-point*` / `uiop:*image-postlude*` / `uiop:*image-dumped-p*` | `nil` |

The hooks are **real** even though nothing here can dump an image: a library may
register into one while it loads, and that must not be an error.

```lisp
(defvar *log* nil)
(uiop:register-image-restore-hook (lambda () (push :restored *log*)) nil)
(uiop:call-image-restore-hook)
*log*   ; => (:RESTORED)
```

`uiop:*image-dumped-p*` is `nil` and stays `nil` — nothing sets it, because
nothing dumps.

## Dumping an image

| Function | What it signals |
|----------|-----------------|
| `uiop:dump-image` | `uiop:not-implemented-error` — no backend can save its heap; compile the program instead |
| `uiop:restore-image` | `uiop:not-implemented-error` — a program is started from source, never resumed |
| `uiop:create-image` | `uiop:not-implemented-error` — there are no Lisp object files to link |

rontolisp has no image in the SBCL sense. A program is read and run, or read and
compiled into one artifact:

```bash
rontolisp app.lisp -o App.class      # a JVM class
rontolisp app.lisp -o app.wasm       # a WASM module
```

which is what `uiop:dump-image` would have been for.

## What is missing: the command line

`uiop:argv0`, `uiop:command-line-arguments`,
`uiop:raw-command-line-arguments` and `uiop:setup-command-line-arguments` are
**not implemented yet**: they signal `uiop:not-implemented-error` like every
other unfilled uiop name, and `uiop:*command-line-arguments*` is `nil`. A
program that needs input today reads it from the environment
([`uiop:getenv`](../functions/uiop-getenv.md)) or from standard input.

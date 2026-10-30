# SWANK (SLIME)

`rontolisp --swank` serves the Common Lisp [REPL](repl.md) over SWANK, the protocol of
Emacs's [SLIME](https://slime.common-lisp.dev). It listens on localhost, on SWANK's port
4005 unless `--swank=PORT` names another (`--swank=0` takes a free one), and prints the
line SWANK's own `create-server` prints:

```console
$ rontolisp --swank
;; Swank started at port: 4005.
```

In Emacs, `M-x slime-connect RET 127.0.0.1 RET 4005 RET` attaches SLIME's REPL to it.
The server speaks the protocol of SLIME 2.32 with its usual `slime-fancy` contribs; a
SLIME of another version asks "Versions differ" first, as it does for any SWANK (setting
`slime-protocol-version` to `'ignore` skips the question).

| In SLIME | What rontolisp does |
|---|---|
| The REPL | every form of the input evaluated in turn; each value of the last one shown, `*` `**` `***`, `/` `//` `///` and `+` `++` `+++` set; output streamed as it is written; `(read-line)` reads what is typed into the REPL; an `in-package` changes the REPL's package |
| `C-x C-e`, `C-M-x`, `C-c :` | the form evaluated in the buffer's package (its `in-package`), the values shown in the echo area |
| `C-c C-c` | the top-level form compiled -- here, evaluated -- with notes: a warning it signals, the error that stops it, where it stops reading, a call of a function nothing defines |
| `C-c C-k`, `C-c C-l` | the file loaded (there is no file compiler: loading it is compiling it), with the same notes at file positions |
| `TAB`, `C-M-i`, `C-c M-i` | the symbols a prefix completes, one part per `-` (`m-v-b`), or fuzzily |
| Echo area | the argument list of the operator around the cursor, the argument being typed marked; a variable's value |
| `C-c C-d d`, `C-c C-d f` | a symbol's description: what it names, its lambda list and documentation string, a variable's value |
| `C-c C-m`, `C-c M-m` | a macro's expansion, one step or every macro in the form |
| An error | the debugger (SLDB) opened where it was signalled, below |
| `C-c C-b` | the running evaluation stopped in the debugger, `c` going on with it |

Each editor request runs on a thread of its own, the REPL's on one thread, as SWANK's
`:spawn` style does, and each reads and runs in the package it names: a function
redefined with `C-c C-c` while a loop runs in the REPL is the one the loop calls next.
Every connection shares one image.

## The debugger

An error no handler handles opens SLIME's debugger where it was signalled, before
anything unwinds, so every restart the program established is still there:

```console
boom 1
   [Condition of type SIMPLE-ERROR]

Restarts:
 0: [RETRY] Retry SLIME REPL evaluation request.
 1: [*ABORT] Return to SLIME's top level.
 2: [ABORT] abort thread (#<THREAD "repl-thread" RUNNING {5E}>)

Backtrace:
  0: (EVAL (ERROR "boom ~a" 1))
```

`q` returns to the top level, `a` invokes the first abort restart, `c` the continue
restart (a `cerror`'s, an interrupt's), and a number the restart it lists, a
`restart-case`'s or a `with-simple-restart`'s included. A frame is a function still
waiting for its value, with its arguments; `t` on it lists its locals (its parameters and
the variables bound where it waits) and `e` evaluates an expression with them in reach.
An error inside the debugger opens the next level, whose `ABORT` restart returns to the
one below. `C-c C-b` stops a running evaluation the same way, a sleep or a read included.

Compiling a buffer or a file keeps its errors as notes and opens no debugger. A frame
cannot be restarted or returned from, stepping is not offered, a frame shows no source
and no catch tags, and the report of a `with-simple-restart` or a `cerror` restart shows
its format control without the arguments.

## Differences from SBCL

Not answered: the inspector, cross references and `M-.` (no definition is found),
tracing and profiling, presentations, changing the working directory. A package prints
as its keyword (`:CL-USER`), a macro expansion on one line, a condition with its report
(`#<SIMPLE-ERROR "boom 1">`), and a value or a name the way rontolisp prints it
(`CL-USER::F`, where SBCL writes `COMMON-LISP-USER::F`). The server runs on the
interpreter only; SLIME's fork SLY and Lem's micros speak protocols of their own and are
not served.

A program cannot start the server: `swank:create-server` (the built-in `swank` system,
[Systems (asdf)](../guides/asdf-systems.md)) still signals, naming `rontolisp --swank`.

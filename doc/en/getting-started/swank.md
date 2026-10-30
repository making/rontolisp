# SWANK (SLIME, SLY, Lem)

`rontolisp --swank` serves the Common Lisp [REPL](repl.md) over SWANK, the protocol of
Emacs's [SLIME](https://slime.common-lisp.dev), and over the protocols of its fork
[SLY](https://github.com/joaotavora/sly) and of [Lem](https://lem-project.github.io)'s Lisp
mode ([below](#sly-and-lem)). It listens on localhost, on SWANK's port
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
| `C-c M-e` (macrostep) | a macro form expanded in place one step at a time, the macro forms in the expansion marked |
| `M-.` | the definition of the name at point: where `C-c C-c`, `C-c C-k` or a `load` (`C-c C-l`, a program's, an ASDF system's) ran it -- a function, macro, generic function and its methods, variable, class and its accessors, structure and its functions, package; one typed at the REPL has no place, as in SBCL; a built-in operator has none |
| `C-c C-w c` `w` `r` `b` `s` `m` `a`, `C-c <`, `C-c >` | cross references in the code compiled, loaded or typed at the REPL while the server runs: who calls a function and what it calls, who references, binds or sets a special variable, who expands a macro, which methods specialize on a class |
| `C-c I` | the inspector: a list's or an array's elements, a hash table's entries, an instance's slots, what a symbol names, a number's representations, a class, slot definition, generic function and its methods, package and its symbols, pathname, each part inspected in turn, with the inspector's actions (remove an entry, unbind a symbol, set a slot, remove a method ...) |
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
cannot be restarted or returned from, stepping is not offered, and a frame shows no source
and no catch tags.

## Differences from SBCL

Not answered: tracing and profiling, presentations, changing the working directory.
Cross references cover only code compiled from the editor, and a function typed at the
REPL has no place to go to. A package prints as its keyword (`:CL-USER`), a macro
expansion on one line, a condition with its report (`#<SIMPLE-ERROR "boom 1">`), and a
value or a name the way rontolisp prints it (`CL-USER::F`, where SBCL writes
`COMMON-LISP-USER::F`). The server runs on the interpreter only.

## SLY and Lem

The same server answers SLY and Lem, each request in the protocol of the client that sent
it, so the three editors can be connected at once. In SLY, `M-x sly-connect RET 127.0.0.1
RET 4005 RET` opens SLY's REPL; the server speaks the protocol of SLY 1.0.43, and a SLY of
another version asks "Versions differ" first. In Lem, `M-x slime-connect` with the same host
and port opens Lem's REPL (measured against Lem 2.3.0).

The table's rows hold for both, on their own keys, the debugger included: an error or an
interrupt opens SLY's or Lem's debugger with the same restarts, frames and levels. SLY's
REPL reads on while its debugger is open, its prompt showing the level (an error typed
there opens the next one). SLY's REPL shows a number with its other notations
(`3 (2 bits, #x3, #o3, #b11)`), completes flexibly (`mvbind` finds `multiple-value-bind`),
inspects a value it printed, and reads `#v3:0` as the first value of its history entry 3.
Lem's completion shows each candidate's kind, argument list and description, `C-c Return`
expands a macro one step, and a printed value or an evaluation's result opens in its
inspector. SLY's stickers and trace dialog are not answered.

## From a running program

A program run by the interpreter (a file, `-e`, the REPL) starts the same server on its
own image with `swank:create-server` from the built-in `swank` system
([Systems (asdf)](../guides/asdf-systems.md)), and goes on running while SLIME inspects
and redefines it:

```console
$ cat app.lisp
(asdf:load-system "swank")
(defvar *requests* 0)
(swank:create-server :port 4005 :dont-close t)
$ rontolisp app.lisp
;; Swank started at port: 4005.
```

It takes SWANK's keys, prints SWANK's line on `*error-output*` and returns the port. With `:dont-close t`
it accepts every connection and returns at once; without it (SWANK's default) it waits for
the first connection and then closes the port. `:interface` names the address to listen
on (localhost by default). `(swank:stop-server 4005)` closes the port; connections already
made go on. A program that ran off its end keeps serving until the port is closed and the
editor disconnects; quitting from SLIME ends the process. A compiled program has no
interpreter to serve: there `swank:create-server` signals and `swank:stop-server` returns
`nil`.

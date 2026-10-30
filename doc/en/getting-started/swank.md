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
| `C-c C-b` | the running evaluation stopped |

Each editor request runs on a thread of its own, the REPL's on one thread, as SWANK's
`:spawn` style does, and each reads and runs in the package it names: a function
redefined with `C-c C-c` while a loop runs in the REPL is the one the loop calls next.
Every connection shares one image.

There is no debugger: an error, like an interrupt, aborts the evaluation and SLIME prints
the condition (`; Evaluation aborted on #<SIMPLE-ERROR "boom">.`) instead of opening its
debugger. Not answered: the inspector, cross references and `M-.` (no definition is
found), tracing and profiling, presentations, changing the working directory. A package
prints as its keyword (`:CL-USER`), a macro expansion on one line, and a value or a name
the way rontolisp prints it (`CL-USER::F`, where SBCL writes `COMMON-LISP-USER::F`). The
server runs on the interpreter only; SLIME's fork SLY and Lem's micros speak protocols of
their own and are not served.

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

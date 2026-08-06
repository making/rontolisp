# `read-char` on a closed socket answers `nil` on the component, `end-of-file` elsewhere

Difficulty: Medium

Split out of `.todo/264` (which made `write-string` / `write-char` / `read-char`
real on the interpreter and the JVM). With all four backends now answering the
same code points, exactly ONE edge of the character surface still differs.

## Repro

```lisp
(let* ((listener (rontolisp:tcp-listen 0 "127.0.0.1"))
       (port (rontolisp:tcp-local-port listener))
       (client (rontolisp:tcp-connect "127.0.0.1" port))
       (server (rontolisp:tcp-accept listener)))
  (close client)
  (print (read-char server nil :eof))
  (print (handler-case (read-char server) (end-of-file () :signalled) (error (e) :other))))
```

| backend | line 1 | line 2 |
|---|---|---|
| interpreter | `:EOF` | `:SIGNALLED` |
| JVM | `:EOF` | `:SIGNALLED` |
| `--component` | `:EOF` | `NIL` |

The eof-CARRYING shape already agrees everywhere (`%io-read-char-eof` signals
the same `end-of-file` class the native lowering does), and that is the shape
the Gray-streams fall-through arm and every real driver spell -- which is why
this is an edge and not a fire.

## Where it comes from

`sockets.lisp`'s `%io-read-char` hands the socket read's value straight back,
and every socket read answers `nil` at EOF (`%sock-read-char-f` ->
`%sock-read-byte-f` -> nil). That is the same lite convention the component's
`read-line` follows -- and `read-line` is DOCUMENTED to answer nil at peer close
on all four backends (rontolisp's `read-line` defaults `eof-error-p` to nil).
`read-char` does not have that excuse: CL defaults `eof-error-p` to **t**, and
the interpreter/JVM (and every non-socket designator on the component, through
`%read-char-raw`) signal.

## The decision this needs

Whether a component socket read follows CL's signalling `read-char` or the lite
nil convention. If signalling (the likely answer -- it is what the other three
backends and the component's own non-socket arm do):

- `%io-read-char`'s socket arm must signal `(error 'end-of-file)` on a nil read,
- and so must the ASYNC-promoted path, or an async body keeps answering nil for
  the identical source line. The promotion targets `%read-char-future`, which
  has no eof test and is shared by `%io-read-char`; a signalling variant has to
  be a separate future defun (`%read-char-eof-future` already exists with the
  eof parameters -- promoting onto it with `t nil` may be the whole fix).
- Both dispatch providers stay in step: `stdin-dispatch.lisp` defines
  `%io-read-char` too (`StdinLibraryTest#theTwoDispatchSplicesDefineTheSameNamesAndShapes`),
  and stdin at EOF has the same question.

## Risk to check first

Whether any spliced library reads a socket with the bare `(read-char sock)` and
relies on the nil (cl-postgres reads bytes, but `ClPostgresE2eTest`'s four
`--component` legs are the regression net that matters).

## Acceptance

- The repro's second line is `:SIGNALLED` on all four backends (Preview 1 keeps
  its call-time tcp error), in a sync body AND inside an async one.
- `.kb/tcp-sockets.md`: the "one edge that still differs" paragraph goes away.

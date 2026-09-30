# Dumping the IR (`--dump-ir`)

The compilers never see the source you wrote: by the time a backend runs, the front end
has inlined every top-level `(load "...")`, expanded the macros, spliced the libraries the
program references, and lowered a Scheme file to the core forms. `--dump-ir` prints that
final form -- the IR, one top-level form per line, in source order -- and exits without
compiling or running:

```console
$ cat greeting.lisp
(defmacro shout (s) `(concatenate 'string ,s "!"))
(print (shout "hello"))
$ rontolisp greeting.lisp --dump-ir
(PRINT (CONCATENATE (QUOTE STRING) "hello" "!"))
```

The dump is ordinary Common Lisp: feeding it back to `rontolisp` runs the same program.
That makes the flag a diff tool for the front end -- what a macro expansion or the Scheme
lowering really produced is one `diff` away, without reading bytecode or adding temporary
prints.

The dump happens before any backend is chosen, so it is the same for every target; `-o`
is refused beside the flag, the dump compiling nothing. The library
splices a program triggers ride the dump too, so a dump of a program using `scheme` or
`vec:` carries their definitions ahead of the program's own forms. Warnings print as on a
compile, on standard error; the dump itself is the whole standard output.

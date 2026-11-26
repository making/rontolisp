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

The last expansion happens in the dump itself: a built-in macro call (`cond`, `setf`,
`unless`, ...) is lowered to the core forms beneath it, the same expansion the interpreter
performs at eval time and the compilers at codegen -- so what a built-in macro did is one
diff too. A call whose name a local `flet`/`macrolet`/`symbol-macrolet` shadows, and a
call whose lowering is the compile path's own runtime machinery (the condition designators,
the definition forms), prints as it stands for the consumer to expand; quoted data is never
entered. The lowered dump runs identically -- the CLI suite round-trips one.

The library splices a program triggers ride the dump too, so a dump of a program using
`scheme` or `vec:` carries their definitions ahead of the program's own forms. Warnings
print as on a compile, on standard error; the dump itself is the whole standard output.

## The IR of a target

The front end reads with the target's features (`#+rontolisp-wasm`, `#+rontolisp-reactor`,
...) and lowers for it: which libraries are spliced, and what a Java program with no
`main` exports, depend on the target. So the dump is the IR of one compile: the one `-o`
and its flags name, writing nothing -- neither the output nor a file beside it. Adding
`--dump-ir` to a compile's command line prints what that compile would compile:

```console
$ rontolisp Calc.java -o calc.wasm --no-wasi --dump-ir
...
(RONTOLISP:WASM-EXPORT '|Calc.add(int,int)| :AS "add" :PARAMS '(:S32 :S32) :RETURNS :S32)
```

With no `-o`, a flag only a `.wasm` output takes (`--no-wasi`, `--component`, `--no-gc`,
`--host-fetch`, `--host-boundary`, ...) names a `.wasm` compile, `--native` a native
executable's, and no such flag a `.class` compile's. So `rontolisp Calc.java --no-wasi
--dump-ir` prints the same reactor, and `rontolisp Calc.java --dump-ir` is refused as the
`.class` compile is: a class with no `main` is a program only where a host calls it. The
compile's refusals are the dump's (`--host-fetch` beside `-o out.class`, for one).

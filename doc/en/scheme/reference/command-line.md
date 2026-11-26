# command-line

`(command-line)`

The program's command line as a list of strings: its name first, then its arguments. An
interpreted program's arguments follow `--` and its name is the file; a compiled one is
handed both by its host. A program run with no command line (a REPL, an embedded run)
answers `()`.

```console
$ cat args.scm
(import (scheme base) (scheme write) (scheme process-context))
(write (command-line))
$ rontolisp args.scm -- alpha "two words"
("args.scm" "alpha" "two words")
$ rontolisp args.scm -o args.wasm && wasmtime run args.wasm alpha "two words"
("args.wasm" "alpha" "two words")
```

```scheme
(write (list? (command-line)))
```

```
#t
```

# File Interpretation

Pass a path to a `.lisp` file and rontolisp interprets it directly, without
producing any compiled artifact. The file's top-level forms are read and
evaluated in order on the tree-walking interpreter, sharing one environment, so a
function or variable defined near the top is available to everything below it.

```bash
rontolisp program.lisp
```

Unlike the REPL, a script does not echo the value of each form -- output is
whatever the program writes explicitly with `print`, `format`, and the like. The
process exits with status `0` when the file runs to completion, or non-zero if a
form signals an error.

Example (`program.lisp`):

```lisp
(defun square (x) (* x x))
(print (square 5))
(print (square 12))
```

```
25
144
```

This is the same source you would later hand to the JVM or WASM compiler; running
it through the interpreter first is the fastest way to check a program's behavior
before compiling it.

## Interpretation Speed

The interpreter is built for turnaround, not throughput: it starts immediately
and runs the exact semantics the compilers are pinned against, but a tight loop
pays interpretation on every iteration, and nothing warms up over time. On
loop-heavy code that costs **20x-200x** over the same file compiled: summing the
first ten million integers takes several seconds interpreted and a fraction of a
second as a compiled class or WASM module.

For a script that reads a file, calls an HTTP API, or serves requests, the
factor is invisible -- the time goes to I/O -- and interpretation is the right
default. When a program is CPU-bound (numeric loops, parsing, crypto), compile
it and run the artifact instead:

```bash
rontolisp program.lisp -o program.jar
java -jar program.jar
```

The compiled output is the fast path, on the JVM and on WASM alike; see
[Compile to JVM Bytecode](../compiling/jvm.md) and
[Compile to WASM](../compiling/wasm.md).

## Programs Given on the Command Line

`-e` (long form `--eval`) takes the program from the argument itself instead of a
file. It runs exactly like a file -- no value is echoed, only what the program
prints:

```bash
rontolisp -e "(print (+ 1 2))"
```

```
3
```

The option is repeatable, and the occurrences make up one program evaluated in
order, as if the forms were written on successive lines of a single file:

```bash
rontolisp -e "(defun square (x) (* x x))" -e "(print (square 5))"
```

```
25
```

It combines with everything a file accepts, including the compilers
(`rontolisp -e "(print (+ 1 2))" -o Prog.class`), but not with an input file --
give the program one way or the other. There being no source file, a relative
`(load "...")` resolves against the working directory.

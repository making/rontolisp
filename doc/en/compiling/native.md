# Compile to a Native Executable

`--native` with `-o` compiles a program to ONE self-contained executable: the
[WASM](wasm.md) output, precompiled to machine code by wasmtime and appended to
a small runner. Running it needs neither wasmtime nor a JVM:

```bash
echo '(print (+ 1 2))' > hello.lisp
rontolisp hello.lisp --native -o hello
./hello
```

```lisp
(print (+ 1 2))
```

```
3
```

The executable is the WASI command module `-o hello.wasm` writes, run the way
`wasmtime run` runs it: the same standard output, and the same exit status --
the program's own `(uiop:quit n)` code, 0 when it returns, 134 after an uncaught
error. Its arguments are the program's `(uiop:command-line-arguments)` and it
sees the process environment and standard input. Nothing else is written: the
`.wasm` and its precompiled form stay in memory.

## Files

The current directory and `/` are open to the program, so relative paths
(`../x` included) and absolute paths work as they do in any native program.

## Flags

Every flag of the default WASM output applies (`--simd`, `--optimize`,
`--dynamic`, ...). The flags that ask for a different kind of module are
refused by name: `--component`, `--no-wasi`, `--no-gc`, `--host-random`,
`--host-fetch`, `--host-boundary`, `--reentrant` and `--emit-js-glue`. So is an
`-o` name ending in `.wasm`, `.class`, `.jar` or `.war`.

## Where It Runs

The executable is built for the platform the compiler runs on (Linux or macOS,
x86_64 or aarch64) and may need that machine's CPU features. On Linux it is
statically linked, so it runs on any distribution of that architecture, whatever
its C library. The released binaries and the executable JAR carry the
precompiler; a build of rontolisp that carries none for the host says
`--native is not available for <os>-<arch>` (building it from source:
[Build & Install](../getting-started/build.md)).

The precompiler is a shared library that `rontolisp` extracts once to
`$XDG_CACHE_HOME/rontolisp` (else `~/.cache/rontolisp`, or
`~/Library/Caches/rontolisp` on macOS); the system property
`rontolisp.native.cache` moves it (`-Drontolisp.native.cache=DIR`).

## Size and Speed

An executable is a runner (3.0 MB on Linux x86_64, 2.4 MB on Linux aarch64,
1.7 MB on macOS) plus roughly 11 times the `.wasm`: 3.0 MB for `hello` on Linux
x86_64, 3.2 MB for a 28 KB module. It starts in about 10 ms and runs at
about the speed of `wasmtime run` on the same module.

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

By default the executable is built for the platform the compiler runs on (Linux
or macOS, x86_64 or aarch64) and uses only the CPU features every processor of
that platform has, so it runs on any machine of the platform however old. On
Linux it is statically linked, so it runs on any distribution of that
architecture, whatever its C library. The released binaries and the executable JAR carry the
precompiler; a build of rontolisp that carries none for the host says
`--native is not available for <os>-<arch>` (building it from source:
[Build & Install](../getting-started/build.md)).

`--native-target` builds for another platform: `linux-x86_64`, `linux-aarch64`
or `macos-aarch64`. The released binaries and the executable JAR carry the
runner of each; a build from source carries its own platform's only, and names
what it carries when asked for another.

```bash
rontolisp hello.lisp --native --native-target linux-aarch64 -o hello-arm64
```

`--native-cpu` chooses the CPU features the machine code may use: `baseline`
(the default), `host` (every feature of the compiling machine's CPU), or on
x86_64 a level, `x86-64-v2`, `x86-64-v3` or `x86-64-v4`. An executable started
on a CPU that lacks one of its features refuses to run and names the feature.
Measured on the benchmark programs, the newer features did not make the code
rontolisp generates faster, so the default costs nothing in practice.

The precompiler is a shared library that `rontolisp` extracts once to
`$XDG_CACHE_HOME/rontolisp` (else `~/.cache/rontolisp`, or
`~/Library/Caches/rontolisp` on macOS); the system property
`rontolisp.native.cache` moves it (`-Drontolisp.native.cache=DIR`).

## macOS GUI Programs

On Apple silicon (`macos-aarch64`) an executable also runs programs that use the
`objc`, `appkit`, `metal` and `scene` packages: its runner calls the Objective-C
runtime itself, and the program runs on the process's first thread, where AppKit
wants it. See [macOS GUI](../guides/objc-appkit.md#a-native-executable). For every
other target such a program is a compile error naming the reference.

## Size and Speed

An executable is a runner (2.1 MB on Linux x86_64, 1.9 MB on Linux aarch64,
1.7 MB on macOS) plus roughly 11 times the `.wasm`: 2.1 MB for `hello` on Linux
x86_64, 2.3 MB for a 28 KB module. It starts in about 10 ms and runs at
about the speed of `wasmtime run` on the same module.

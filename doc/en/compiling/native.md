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

## HTTP

[`rontolisp:fetch`](../guides/http-fetch.md) works in an executable as it does
on the interpreter and the JVM: the request is running when `fetch` returns,
several requests overlap, `await` answers `(:status :headers :body)` with
`:body` a stream of the reply's octets, and a request that fails (a refused
connection, a certificate no trusted root vouches for) signals at the `await`.
The runner makes the requests itself, over HTTP/1.1 and one connection each,
and like the JDK's client it neither follows redirects nor decompresses.

HTTPS trusts Mozilla's root certificates, which are built into the executable,
so it needs no certificate bundle on the machine. `SSL_CERT_FILE` names a PEM
file of certificates to trust instead, for a private or TLS-inspecting
certificate authority:

```bash
SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt ./fetcher
```

A program that fetches starts with a larger runner, the one that carries HTTP
and TLS; every other program keeps the small one
([Size and Speed](#size-and-speed)).

## Serving HTTP

A [Clack](../guides/clack.md) application started with `(clack:clackup app
:server :rontolisp)`, a [Ring](../clojure/reference/ring.md) handler started
with `run-server`, or a handler served by
[`rontolisp:http-handler`](../reference/functions/rontolisp-http-handler.md)
serves from the executable: the runner binds `:port` and
`:address`, accepts the connections and speaks HTTP/1.1, and the program answers
the requests.

```bash
rontolisp app.lisp --native -o app
./app        # serves until stopped (Ctrl-C)
```

The runner reads every connection on a thread of its own (a request's head and
its whole body, `Content-Length` or chunked) and keeps connections alive as
HTTP/1.1 says, closing one that stays idle for a minute. The application answers
one request at a time, on the program's one thread, and its response goes out
whole, with a `Content-Length`. So `clackup`, `run-server` and `http-handler` never return:
`:use-thread` and `:join? false` have no second thread to hand the program back
on, and the server stops with the process. The request is the one the
interpreter and the JVM build, the peer's address and port included; the files
a handler serves are read from the file system as in any program
([Files](#files)).

## Flags

Every flag of the default WASM output applies (`--simd`, `--optimize`,
`--dynamic`, ...). The flags that ask for a different kind of module are
refused by name: `--component`, `--no-wasi`, `--no-gc`, `--host-random`,
`--host-fetch`, `--host-boundary`, `--reentrant` and `--emit-js-glue`. So is an
`-o` name ending in `.wasm`, `.class`, `.jar` or `.war`. A program that fetches
or serves needs no flag: the runner answers `fetch` and the server itself
([HTTP](#http), [Serving HTTP](#serving-http)).

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
x86_64, 2.3 MB for a 28 KB module. The runner of a program that fetches or serves HTTP is
3.3 MB on Linux x86_64 and 2.9 MB on Linux aarch64. It starts in about 10 ms and runs at
about the speed of `wasmtime run` on the same module.

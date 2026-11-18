# Licenses of a Compiled Program

A program rontolisp compiles carries code that is not yours. Distributing it means
passing on the licence notices of that code.

## What a Compiled Program Carries

- **rontolisp's run-time code**, in every output (`.class`, `.jar`, `.war`, `.wasm`, a
  native executable). rontolisp is licensed under the Apache License 2.0, which asks for
  a copy of the licence and of rontolisp's `NOTICE` to go with it.
- **Third-party code and data rontolisp is built from**, in the outputs that use them:
  the Unicode tables behind case mapping and character classes, the WASM backends'
  math functions (fdlibm) and float printing (Schubfach), the built-in Ring and riddley
  namespaces, the ASDF, UIOP and CFFI run time, tiny-routes' path templates, Starlark's
  `str.format`, the Q8_0 quantization (ggml) and the WASI interfaces of `--component`.
- **The native runner**, in a [native executable](native.md): it links wasmtime and some
  110 Rust crates (MIT, Apache-2.0, BSD-3-Clause, ISC, Unicode-3.0, Zlib,
  CDLA-Permissive-2.0), whose licences ask for their notices in a binary distribution.

## Getting the Notices

`rontolisp notices` writes them all into one file to ship next to the program:

```console
rontolisp notices -o NOTICES.txt                  # a .class, .jar, .war or .wasm
rontolisp notices --native -o NOTICES.txt         # a native executable for this machine
rontolisp notices --native --native-target linux-aarch64 -o NOTICES.txt
```

The file holds rontolisp's licence and `NOTICE`, then every third-party entry a compiled
program may carry, whether or not yours uses it, and with `--native` the crates of the
runner, each with its licence text.

## Libraries a Program Loads

A library a program loads -- from Quicklisp or another dist, a `deps.edn` dependency, a
`--java-dep` coordinate -- is compiled into the output with the program, and no notice of
it travels there by itself. `rontolisp notices` does not list it: following its licence
is up to you. Most ask for their copyright notice and licence text (the library's
`LICENSE` file) to go with the program. A copyleft licence (LGPL or LLGPL, GPL, AGPL,
EPL) may ask more, such as offering the library's source code.

## The rontolisp Tool

`rontolisp notices --tool` prints what the rontolisp jar or native binary itself carries:
its licence, `NOTICE`, the third-party code it is built from, JLine, and the Rust crates
of its precompile shim and runners. The jar also holds them under `META-INF/`.

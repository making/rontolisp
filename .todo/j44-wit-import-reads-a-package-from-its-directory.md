# WIT import: a package read from its directory, as the package is published

Difficulty: Medium

A WIT package is published as a directory, and `wit-import` reads one file
(`WitImportInliner.read`: `SourceLoader.load`). WebAssembly/wasi-keyvalue's `wit/` is `store.wit`,
`atomic.wit`, `batch.wit`, `watch.wit` and `world.wit`: only `world.wit` declares
`package wasi:keyvalue@0.2.0-draft2;`, and `atomic.wit`, `batch.wit` and `watch.wit` take their types
from `store.wit` (`atomic.wit`: `use store.{bucket, error};`). No part of it imports:

- the directory: `cannot read file wit/keyvalue: Is a directory`;
- `atomic.wit` with `:interface "wasi:keyvalue/atomics@0.2.0-draft2"`: `no interface ... (found:
  atomics)`, the file declaring no package (a bare `:interface "atomics"` binds on the interpreter,
  under the id `atomics`, which no provider of `wasi:keyvalue/atomics@0.2.0-draft2` answers);
- `store.wit` and `atomic.wit`, a `wit-import` each, under `--component`: `'increment': the WIT type
  of the result is 'error', which the file does not define (nor import with a use clause)`.

So a program concatenates the files into one by hand
([counter-api](https://github.com/categolj/counter-api-lisp)'s `wit/keyvalue.wit`), as rontolisp's
own libraries do (`sockets.wit`, `stdin.wit`, ...: "Keep in lockstep with the vendored file").
wasm-tools, wit-bindgen and cargo-component read a directory as the package, and its `deps/` as the
packages it uses (`wkg wit fetch` writes them there).

## Goal

`wit-import`'s path may name a directory: its `*.wit` files are one package, and each entry of its
`deps/` (a directory or a `.wit` file) is a package its `use` clauses may name.
`(rontolisp:wit-import "wit/keyvalue" :interface "wasi:keyvalue/atomics@0.2.0-draft2" :package kva)`
over wasi-keyvalue's `wit/` as published binds what it binds over the concatenated file, on every
backend; the component is byte-identical. A file path reads as today.

## Direction

- The parser already resolves several packages in one document (`package wasi:clocks@0.3.0 { ... }`
  nested, as `http.wit` does): a directory can become that document, the files agreeing on the
  package (one declaring it is enough) and each `deps/` entry nested. A top-level `use` is scoped to
  its file in WIT, so a merge must not let one file's `use` reach another's names.
- An error names the file and line it comes from, not a line of the merged document.
- `SourceLoader` lists directories (`isDirectory` / `listDirectory`); the playground's loader lists
  none, so a directory there is an error saying so.
- `wit-export` reads its WIT through the same loader: decide whether it takes a directory too.
- Whether rontolisp's own libraries then read the vendored `src/wasm-component/deps/` instead of
  their copies is a separate decision (a resource directory is not listed in a native image).

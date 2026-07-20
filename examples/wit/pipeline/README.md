# wit/pipeline -- three components in one `wac compose`

[`wit/lisp-calls-rust/`](../lisp-calls-rust) and
[`wit/rust-calls-lisp/`](../rust-calls-lisp) each wire **one** import to **one**
export, which `wac plug` does in a single command. This example is the case
`plug` cannot do: a **chain** of three components across two languages, where one
component is a plug for another. That is a [`wac compose`](https://github.com/bytecodealliance/wac)
job -- a small script that writes out every edge by hand.

```
   app.lisp                rust-shouter                 stats.lisp
   (Lisp command)          (Rust reactor)               (Lisp reactor)
   ------------            --------------               --------------
   sh:emphasize  ───────▶  export shout.emphasize
   "hello world"           uppercases the text
                           import vowel-count  ───────▶  export vowel-count
                                              ◀───────   counts the vowels: 3
                           appends "!" x 3
   "HELLO WORLD!!!" ◀────  returns the string
```

`emphasize` is Rust's job (uppercasing); `vowel-count` is Lisp's job (counting).
One call to `sh:emphasize` crosses the language boundary twice -- Lisp → Rust →
Lisp -- and the app makes just one Lisp function call.

## Prerequisites

- a rontolisp binary (or the executable JAR -- set `RONTOLISP` accordingly)
- a Rust toolchain with the `wasm32-unknown-unknown` target
  (`rustup target add wasm32-unknown-unknown`)
- [`cargo-component`](https://github.com/bytecodealliance/cargo-component)
  (`cargo install cargo-component`) to build the Rust component
- [`wac`](https://github.com/bytecodealliance/wac) (`cargo install wac-cli`) and
  `wasmtime` 46+ (plus `wasm-tools` if you want to inspect the components)

[`build.sh`](build.sh) runs it all end to end:

```bash
RONTOLISP=../../../target/rontolisp ./build.sh
# hello world  ->  HELLO WORLD!!!
# component model  ->  COMPONENT MODEL!!!!!
# rust and lisp  ->  RUST AND LISP!!!
```

## 1. The three components

Same shapes as the two `plug` examples, combined:

| Component | Role | in [`wit/pipeline.wit`](wit/pipeline.wit) |
|---|---|---|
| [`app.lisp`](app.lisp) | Lisp command | `wit-import`s interface `example:pipeline/shout` |
| [`rust-shouter/`](rust-shouter) | Rust reactor | world `shouter`: exports `shout`, imports `vowel-count` |
| [`stats.lisp`](stats.lisp) | Lisp reactor | world `statistician`: exports `vowel-count` |

The Rust crate was scaffolded with [`cargo-component`](https://github.com/bytecodealliance/cargo-component)
(`cargo install cargo-component`), then pointed at the shared WIT --
`cargo component new --lib rust-shouter --name shouter --namespace example`, its
crate-local `wit/` deleted, and `[package.metadata.component.target]` in
[`Cargo.toml`](rust-shouter/Cargo.toml) set to `path = "../wit"`,
`world = "shouter"`. See [`wit/lisp-calls-rust/`](../lisp-calls-rust) for that
step spelled out. Its [`src/lib.rs`](rust-shouter/src/lib.rs) exports `emphasize`
and calls the imported `bindings::vowel_count`.

```bash
rontolisp app.lisp   -o app.wasm   --component --optimize
rontolisp stats.lisp -o stats.wasm --component --optimize
( cd rust-shouter && cargo component build --release --target wasm32-unknown-unknown && cd .. )
cp rust-shouter/target/wasm32-unknown-unknown/release/shouter.wasm shouter.wasm
```

## 2. The composition -- one script, every edge

`wac plug` fills a single socket's imports from plug components; it will not wire
one plug into another. The chain here (stats → shouter → app) needs exactly that,
so it is written out in [`composition.wac`](composition.wac):

```wac
package example:composed;

let stats   = new example:stats { ... };
let shouter = new example:shouter { "vowel-count": stats["vowel-count"], ... };
let app     = new example:app { "example:pipeline/shout": shouter["example:pipeline/shout"], ... };

export app...;
```

- `new example:stats { ... }` instantiates a component; `--dep example:stats=stats.wasm`
  (below) binds the name to the file.
- An instantiation argument is named after the component's **import**: the Rust
  shouter imports the plain function `vowel-count`, the Lisp app imports the
  interface `example:pipeline/shout`. Its value comes from another instance's
  matching **export**, `stats["vowel-count"]` / `shouter["example:pipeline/shout"]`.
- `...` lets every unmentioned import -- the WASI interfaces the Lisp components
  need -- fall through as an import of the composed component, which wasmtime
  satisfies at run time.
- `export app...;` re-exports the app's own exports (its `wasi:cli/run` entry
  point) so `wasmtime run` has something to start.

```bash
wac compose composition.wac \
  --dep example:app=app.wasm \
  --dep example:shouter=shouter.wasm \
  --dep example:stats=stats.wasm \
  -o pipeline.wasm
```

`pipeline.wasm` imports only WASI now -- every `example:pipeline` edge is wired
inside it.

## 3. Run it

```bash
wasmtime run -W gc=y pipeline.wasm
```
```console
hello world  ->  HELLO WORLD!!!
component model  ->  COMPONENT MODEL!!!!!
rust and lisp  ->  RUST AND LISP!!!
```

`component model` has five vowels, so it gets five `!` -- the count came from the
Lisp component, the uppercasing from Rust, and the orchestration from the Lisp
app, all through the one interface.

## plug vs. compose

| | [`wac plug`](../lisp-calls-rust) | `wac compose` (here) |
|---|---|---|
| input | CLI flags only | a `.wac` script |
| wiring | automatic, by WIT type | written out per edge |
| topology | one socket ← plugs | any graph (chains, fan-out, …) |
| plug into a plug | no | yes |

For the two-component examples, `plug` is the shortest path; a chain like this one
is where `compose` earns its place. Only `string` and an `s32` cross the boundary
here, the flat no-`result` subset, so no `-W exceptions=y` is needed.

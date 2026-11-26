# hello — the smallest rontolisp Worker

Three Lisp functions ([`worker.lisp`](worker.lisp)) that the Worker calls the
way it would call any JavaScript function. No library, no allocator, no WASI
shim: the compiled module imports **nothing**, and
[`src/index.js`](src/index.js) is the entire host side.

```bash
./build.sh          # worker.lisp -> src/worker.wasm
npx wrangler dev    # http://localhost:8787
npx wrangler deploy
```

```console
$ curl http://localhost:8787/
Hello from Lisp, compiled to WebAssembly!
$ curl 'http://localhost:8787/add?a=2&b=3'
2 + 3 = 5
$ curl 'http://localhost:8787/fib?n=20'
fib(20) = 6765
```

## The module

```lisp
(rontolisp:wasm-export 'add   :params '(:s32 :s32) :returns :s32)
(rontolisp:wasm-export 'fib   :params '(:s32)      :returns :s32)
(rontolisp:wasm-export 'greet :params '()          :returns :string)
```

Compiled with `--no-gc --optimize`, that is a plain MVP module — no wasm-GC, no
WASI, nothing to link against:

```console
$ node -e 'const m = new WebAssembly.Module(require("fs").readFileSync("src/worker.wasm"));
           console.log(WebAssembly.Module.imports(m), WebAssembly.Module.exports(m).map(e => e.name))'
[] [ 'add', 'fib', 'greet', 'memory', '__ronto_alloc', '__ronto_alloc_mark', '__ronto_alloc_reset' ]
```

so instantiating it is one line, once per isolate, and `lisp.add(2, 3)` returns
`5` — a `:s32` is an i32 on both sides:

```js
const lisp = new WebAssembly.Instance(module, {}).exports;
```

## Strings, and why there is no bookkeeping here

WebAssembly has no string type, so `greet` returns **two i32 values** — a
pointer into linear memory and a length — and the host decodes the bytes:

```js
const [ptr, len] = lisp.greet();
new TextDecoder().decode(new Uint8Array(lisp.memory.buffer, ptr, len));
```

Linear memory is not garbage collected, so in general those bytes are the
host's to reclaim. Note what is *absent* here: the module exports
`__ronto_alloc` and the arena pair `__ronto_alloc_mark`/`__ronto_alloc_reset`,
and this Worker never touches them. Nothing crosses the boundary *into* the
module, so the host never allocates and the returned string lands in a fixed
scratch area the next call reuses — measured, 150 000 calls to `add` + `fib` +
`greet` leave linear memory exactly where it started.

Pass a string *in* and that changes: see [`../httpbin`](../httpbin) and its
[Two heaps](../httpbin/README.md#two-heaps) section.

## The non-GC subset

`--no-gc` is what makes this module tiny and dependency-free, and it is
available because `worker.lisp` stays inside the
[numeric/string subset](../../../doc/en/guides/wasm-nogc.md): integers, a string
literal, `dotimes`. Add a cons cell, a hash table or the JSON library and the
build needs the full language — `--no-wasi` instead, which is what `../httpbin`
does.

## Rebuilding

```bash
./mvnw clean package -DskipTests   # from the repository root
examples/cloudflare-workers/hello/build.sh
```

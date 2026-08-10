# hello — the smallest rontolisp Worker

Three Lisp functions ([`worker.lisp`](worker.lisp)) that the Worker calls the
way it would call any JavaScript function. The compiled module imports
**nothing** and needs no WASI shim, no allocator and no bindings library —
[`src/index.js`](src/index.js) is the entire host side, and the module is by far
the smallest thing [measured](../../../size-report/results/cloudflare-workers.md).

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

so instantiating it is one line, and it happens once per isolate:

```js
const lisp = new WebAssembly.Instance(module, {}).exports;
```

`add` and `fib` are then just functions: `lisp.add(2, 3)` returns `5`. A `:s32`
is an i32 on both sides, and JavaScript sees a number.

## Strings, and why there is no bookkeeping here

WebAssembly has no string type, so `greet` returns **two i32 values** — a
pointer into the module's linear memory and a length — and the host decodes the
bytes:

```js
const [ptr, len] = lisp.greet();
new TextDecoder().decode(new Uint8Array(lisp.memory.buffer, ptr, len));
```

That is the whole boundary, and the bytes live in **linear memory** — not on a
garbage-collected heap. WebAssembly has no string type, so this is the only way a
string can cross; the engine never traces linear memory, so in general those
bytes are the host's to reclaim.

Note what is *absent* here, though: the module also exports `__ronto_alloc` and
the arena pair `__ronto_alloc_mark` / `__ronto_alloc_reset`, and this Worker
never touches them. Nothing crosses the boundary *into* the module, so the host
never allocates, and the returned string lands in a fixed scratch area that the
next call reuses. Memory therefore stays flat on its own — measured, 150 000
calls to `add` + `fib` + `greet` on one instance:

```console
memory after 150000 calls: 65536 -> 65536
```

Pass a string *in* and that changes: the host has to allocate, and then it has
to reclaim. That is [`../httpbin`](../httpbin), and the arena bracket there is
the reason it looks more complicated than this — see
[Two heaps](../httpbin/README.md#two-heaps-wasm-gc-collects-one-of-them-you-collect-the-other)
for which memory wasm-GC does and does not cover. (`--no-gc`, used here, has no
garbage collector at all; the point holds either way, because the boundary was
never the collector's business.)

## The non-GC subset

`--no-gc` is what makes this module tiny and dependency-free, and it is
available because `worker.lisp` stays inside the
[numeric/string subset](../../../doc/en/guides/wasm-nogc.md): integers, a string
literal, `dotimes`. Add a cons cell, a hash table or the JSON library and the
build needs the full language — `--no-wasi` instead of `--no-gc`, which is
exactly what `../httpbin` does.

## Rebuilding

```bash
./mvnw clean package -DskipTests   # from the repository root
examples/cloudflare-workers/hello/build.sh
```

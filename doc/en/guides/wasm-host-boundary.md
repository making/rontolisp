# WASM Host Boundary (`wasm-export` / `wasm-import`)

Two complementary directives declare what crosses the module/host boundary in
rontolisp's own type designators. Both work in every WASM output shape (the
same source runs on every backend — the directives are no-ops or defun stubs
on the interpreter and the JVM).

For the typed WIT-driven boundary, see the
[WIT contracts guide](wit-contracts.md).

## Exporting Lisp Functions

By default a compiled module only exposes its entry point (`_start`). To make
an individual Lisp function callable directly from a host (`wasmtime --invoke`,
JavaScript, or another module), mark it with the `rontolisp:wasm-export`
directive, declaring the WASM-boundary types of its parameters and result:

```lisp
(defun fact (n) (if (<= n 1) 1 (* n (fact (- n 1)))))
(rontolisp:wasm-export 'fact :params '(:int) :returns :int)
```

```bash
rontolisp fact.lisp -o fact.wasm
wasmtime run --invoke fact -W gc fact.wasm 5
```

```
120
```

The directive itself is the same in every output shape; what changes per shape
is the **host contract** of the export — a raw core function on the core-module
shapes, a typed component-model export under `--component`. On the interpreter
and JVM backends the directive is a no-op (it just returns the named symbol),
so the same source runs on every backend.

The type designators and their boundary representations are:

| Designator | WASM boundary | Notes |
| --- | --- | --- |
| `:int` | `i32` | full 32-bit signed range |
| `:long` | `i64` | full 64-bit signed range on every backend |
| `:float` | `f64` | |
| `:bool` | `i32` | `0` is `nil`, any non-zero value is `t` |
| `:string` | `(ptr, len)` | UTF-8 bytes in linear memory; a component-model `string` under `--component` |
| `:s-expr` | `(ptr, len)` | s-expression text (any value except a function); GC value model only |
| `:bytes` | `(ptr, len)` argument / `(ptr, cap) -> len` result | an `(unsigned-byte 8)` vector as **raw bytes** — no UTF-8 in either direction; GC core-module shapes only (not `--component`, not `--no-gc`) |

`:string` carries a *value* (decoded, allocated per call); `:bytes` carries a
*transfer*: the **caller passes the buffer**, the `read(2)` shape. A `:bytes`
**result** appends a `(ptr, cap)` pair to the export's parameters — the host
reserves `cap` bytes (e.g. with `__ronto_alloc`) and the wrapper copies at most
`cap` bytes there — and the single `i32` result is the vector's **full**
length, so an undersized buffer is a retry, not a truncation. No per-call
allocation is spent on the transfer, which is what keeps a chunked pull loop's
memory flat.

A side-effecting function can declare a **void** result by omitting `:returns`
(or giving it as `nil`, `'()` or `:void`); the wrapper then discards the Lisp
return value and has no WASM result. Likewise an omitted, `nil` or `'()`
`:params` means no arguments.

```lisp
(defun log-it (n) (print n))
(rontolisp:wasm-export 'log-it :params '(:int))           ; (i32) -> () , prints n
```

`:as` renames the export — useful when the host-facing API wants a name that is
not an idiomatic Lisp symbol, e.g. camelCase for JavaScript:

```lisp
(defun draw-board (w h) (* w h))
(rontolisp:wasm-export 'draw-board :as "drawBoard" :params '(:int :int) :returns :int)
```

Limitations shared by every shape:

- Only a top-level `defun` can be exported, the declared parameter count must
  match its arity, and functions that take or return function values are out
  of scope.
- The exported name defaults to the bare Lisp name (`fact`) and can be renamed
  with `:as`; how arguments are written depends on the host
  (`wasmtime --invoke fact module.wasm 5`, `instance.exports.fact(5)`, ...).
- The exported function may be a [`rontolisp:async-defun`](../reference/special-forms/rontolisp-async-defun.md):
  the boundary resolves the future it answers, so the host receives the
  declared type and never a future.

### Export Modes at a Glance

The same directive compiles into four different host contracts depending on
the `--no-gc` / `--component` flags:

| | GC core module (default / `--no-wasi`) | GC `--component` | `--no-gc` core module | `--no-gc --component` |
| --- | --- | --- | --- | --- |
| Host requirements | wasm-GC engine (`wasmtime -W gc`, Node 22+, current browsers) | wasmtime 46+ (`-W gc=y`) or a component host with wasm-GC + JSPI (a [browser via jco](wasm-browser.md) loads and computes, but cannot print yet) | **any** WebAssembly engine | any component-model host, **no flags** — including a [browser via jco](wasm-browser.md), with no dependencies at all |
| Export shape | raw core function | typed component-model export (WAVE `--invoke`, jco) | raw core function | typed component-model export (WAVE `--invoke`, jco) |
| Scalars | `:int`/`:long`/`:float`/`:bool`/void | `:int`/`:long`/`:float`/`:bool`/void | `:int`/`:long`/`:float`/`:bool`/void | `:int`/`:long`/`:float`/`:bool`/void |
| `:string` | manual `(ptr,len)` + `__ronto_alloc` | component-model `string` (canonical ABI) | manual `(ptr,len)` + `__ronto_alloc` | component-model `string` (canonical ABI) |
| `:s-expr` | manual `(ptr,len)` | component-model `string` (printed text) | not supported | not supported |
| `:bytes` | manual `(ptr,len)` / caller-buffered result | not supported (no `list<u8>` lift yet) | not supported | not supported |
| Function body may use | the full language | the full language | the [non-GC subset](wasm-nogc.md#eligible-subset) | the [non-GC subset](wasm-nogc.md#eligible-subset) |
| I/O inside the export | works (real WASI imports; under `--no-wasi` output is discarded, `random` runs on a built-in generator, `getenv`/file lookups answer nothing, the clock is the one the host wrote through `__ronto_set_time` and input traps) | usually works even in a sync export; [`:async t`](wasm-component.md#component-model-function-exports-wasm-export) removes the residual trap risk | `print` only (one `fd_write` import) | `print` only (built-in WASI 0.3 stdout bridge; the exports become async lifts) |
| Program top level | runs as `_start` | co-exists as `wasi:cli/run` | `defun` + directives only | `defun` + directives only |
| Per-call string memory | host-managed (`__ronto_alloc` + the [arena API](wasm-gc-module.md#reclaiming-the-hosts-buffer-the-arena-api); the Lisp side is the engine's) | freed by the canonical post-return | host-managed (`__ronto_alloc` + the [arena API](wasm-nogc.md#reclaiming-memory-the-arena-api); automatic for scalar returns) | freed by the canonical post-return |
| Typical size | ~100 KB (~2 KB with [`--optimize`](../compiling/wasm.md#optimize-tree-shaking)) | ~110 KB | tens of bytes to a few KB | hundreds of bytes to a few KB |

Each shape's own guide details how its exports are called, what runs inside
them, and what each host must provide:
[wasm-GC core module](wasm-gc-module.md),
[WASI 0.3 component](wasm-component.md),
[--no-gc output and its compact component wrap](wasm-nogc.md).

## Importing Host Functions

`rontolisp:wasm-import` is the reverse of `wasm-export`: it declares a function
the **host** provides and makes it callable from Lisp under the given name
exactly like a top-level `defun` — including `#'name`, `funcall`, `mapcar` and
`eval`. `:from` names the import module (default `"env"`), `:as` names the
field inside it (default: the Lisp name), and the type designators are the
same table as above:

```lisp
; main.lisp
(rontolisp:wasm-import 'add :from "host" :params '(:int :int) :returns :int)
(defun add10 (n) (add n 10))
(rontolisp:wasm-export 'add10 :params '(:int) :returns :int)
```

In wasmtime, satisfy the imports by preloading another module that exports
them — here a host module that is itself written in Lisp, exporting its
function under the `:as` alias `add`:

```console
$ cat host.lisp
(defun host-add (a b) (+ a b))
(rontolisp:wasm-export 'host-add :as "add" :params '(:int :int) :returns :int)
$ rontolisp host.lisp -o host.wasm --no-wasi
$ rontolisp main.lisp -o main.wasm --no-wasi
$ wasmtime run -W gc --preload host=host.wasm --invoke add10 main.wasm 32
42
```

In a browser (or Node) the import object *is* the module table — one key per
`:from` name, one property per `:as` name. This is also the escape hatch for
anything the WASM backend does not provide; for example it has no
trigonometric built-ins, so borrow JavaScript's:

```lisp
(rontolisp:wasm-import 'sin :from "math" :params '(:float) :returns :float)
(rontolisp:wasm-import 'cos :from "math" :params '(:float) :returns :float)
```

```js
const imports = { math: { sin: Math.sin, cos: Math.cos } };
const { instance } = await WebAssembly.instantiate(bytes, imports);
```

The [WebGL triangle example](https://github.com/making/rontolisp/tree/develop/examples/browser/webgl-triangle)
is the hello world of this pattern: ten imported functions, no exports, and a
colored triangle drawn entirely from Lisp. The
[WebGL cube example](https://github.com/making/rontolisp/tree/develop/examples/browser/webgl-cube)
adds 3D: the perspective and rotation matrices are computed in Lisp every
frame. The
[WebGL galaxy example](https://github.com/making/rontolisp/tree/develop/examples/browser/webgl-galaxy)
is the same idea grown into a complete browser program: the entire WebGL
pipeline is driven from Lisp — the GLSL shaders live in the Lisp source, and
Lisp compiles, links, buffers and issues every draw call through 32 imported
host functions, while JavaScript supplies only one-line bindings over a handle
table -- generated from the [WIT](wit-contracts.md#importing-a-wit-interface-wit-import)
that declares the boundary.

Boundary details beyond the scalar types:

- A `:string`/`:s-expr` **argument** reaches the host as a `(ptr, len)` pair
  into the module's exported `memory` (an `:s-expr` argument is printed to
  readable text first).
- A `:string` **result** must be written into linear memory by the host —
  reserve the buffer with the exported `__ronto_alloc`, then return the
  `(ptr, len)` pair (a two-element array in JavaScript).
- An `:s-expr` **result** is parsed with the embedded reader, so the host can
  hand back a whole list structure as text.
- A `:bytes` **argument** is an `(unsigned-byte 8)` vector staged as a raw
  `(ptr, len)` pair — no UTF-8 encode, so arbitrary binary crosses exactly.
- A `:bytes` **result** is caller-buffered: the Lisp signature gains one
  trailing parameter, the `(unsigned-byte 8)` vector to receive into, and the
  host is called with a trailing `(ptr, cap)` pair — *write up to `cap` bytes
  at `ptr`, return the full length `n`*. The call answers `n` (an `n` above the
  buffer's length means "retry with a bigger buffer"), and the wrapper's
  staging is popped on return, so a pull loop over one reused buffer keeps
  linear memory flat.
- An **asynchronous** host function — a `WebAssembly.Suspending`-wrapped
  import under JSPI — is declared with `:async t`: the call then returns a
  future that `rontolisp:await` resolves, the build prints the host's
  obligations (`Suspending` on the import, `promising` on the exports that can
  reach it, serialised calls — a re-entered export refuses with a trap instead
  of corrupting both calls, unless the module was compiled
  [`--reentrant`](#overlapping-calls---reentrant)), and a call reachable from a top-level form of a
  `--no-wasi` module is a compile error (`_initialize` cannot suspend). The
  [reference page](../reference/functions/rontolisp-wasm-import.md) has the
  full contract.

Limitations:

- Default (wasm-GC) Preview 1 output only: `--component` and `--no-gc` reject
  the directive with an error.
- On the interpreter and JVM backends the directive defines a stub that
  signals an error when called, so a shared source still loads everywhere, but
  actually calling an import needs the WASM host.
- Imported functions have the same 10-parameter arity limit as other functions
  under the wasm-GC value model.
- Instantiating the module requires every declared import to be provided:
  `wasmtime run` needs a `--preload <module>=<file>.wasm` per import module
  name, and a JavaScript host passes an import object.

## Choosing the Body Boundary (--host-boundary)

An HTTP reactor — a `--no-wasi` module whose entry point a host calls, which is
what [`clack:clackup`](clack.md) and
[`rontolisp:http-handler`](../reference/functions/rontolisp-http-handler.md)
compile to there — speaks one JSON envelope in each direction. What
`--host-boundary` decides is whether a **body** rides inside that envelope or
crosses beside it, and it changes what the **module imports**, so it is a flag
of its own rather than a value on `--emit-js-glue`.

| | `envelope` (default) | `streaming` |
| --- | --- | --- |
| Request body | the envelope's `"body"` key | `env.readRequestBody(ptr, cap) -> i32`, a chunk per call |
| Response body | the head's `"body"` key | `env.writeResponseBody(ptr, len)`, a chunk per call |
| `rontolisp:fetch` reply body (`--host-fetch`) | the reply head's `"body"` key | `env.readResponseBody(ptr, cap) -> i32` |
| Host-side state | none | a cursor per reading import |
| Binary body | does NOT survive — `ff fe 41` arrives as `ef bf bd ef bf bd 41` | crosses exactly |
| Large body | linear memory grows with it | stays flat |
| Streamed upstream reply | buffered, then forwarded | forwarded chunk at a time |
| Generated host half | `instantiate`, `defaultHost()` and `worker(module)` — the same on both |

```console
$ rontolisp worker.lisp -o worker.wasm --no-wasi --host-fetch
$ wasm-tools print worker.wasm | grep -oE '\(import "[^"]+" "[^"]+"'
(import "env" "fetch"
```

**`envelope` is the default, and it is the one to want.** A body that is a
*document* — a Worker that reads one JSON request and answers one JSON reply —
pays a copy nobody can measure and gets back a boundary with no host-side state
in it, which is where the bugs on this surface have all been. Ask for
`--host-boundary=streaming` when one of these is true:

- **a body is BINARY** — an image, a file, protobuf, anything already compressed.
  The envelope carries a body as JSON text, so bytes that are not valid UTF-8 do
  not survive: `ff fe 41` arrives as the seven bytes
  `ef bf bd ef bf bd 41`, two replacement characters where two octets were, with
  the `content-length` beside it still saying three. Nothing reports it.
- **a body is LARGE** — the envelope puts it in linear memory whole, so memory
  grows with the body; the split reads through one reused buffer and stays flat
  however big it gets.
- **you are relaying an upstream reply** — the split forwards it a chunk at a
  time instead of holding the whole thing first.

Neither shape is a subset of the other, and the module sizes land within about
1% of each other either way round, so this is not a size decision either. It is
not an ergonomics decision either: `--emit-js-glue` writes the host half of
both, so the JavaScript is three lines whichever you pick.

**The default moved here, and a rebuild is how you feel it.** Before this, every
`--no-wasi` reactor took the bodies out of the envelope; a module rebuilt without
the flag now keeps them in it, which is a real regression for the three cases
above and nothing at all for everything else. Add `--host-boundary=streaming` and
the module is byte-for-byte what it was.

`--host-boundary` needs `--no-wasi` and a `.wasm` output, without `--component`
or `--no-gc`: those two are in band already (a component's host functions cross
the canonical ABI, and `--no-gc` imports nothing at all), and so is a plain WASI
command module, whose host is `wasmtime run` and satisfies no `env.*` import. A
hand-written reactor — one that spells out its own envelope adapter instead of
going through `clack:clackup` — follows the build with the `rontolisp-body-imports`
reader feature, which is present exactly where those imports are:

```lisp
#+rontolisp-body-imports
(rontolisp:wasm-import '%read-request-body :from "env" :as "readRequestBody"
                       :params '() :returns :bytes :async t)
```

## Generating the Host Glue (--emit-js-glue)

Everything above is derived from a declaration, so the JavaScript half can be
too. `--emit-js-glue` writes it next to the module (`out.wasm` -> `out.js`):
the import object, the `(ptr, len)` staging in both directions, the
`__ronto_alloc` bracket around a call, the `WebAssembly.Suspending` wrappers,
the `WebAssembly.promising` entry for exactly the exports the build lists, and
the one-call-at-a-time queue a module that can suspend needs.

```console
$ rontolisp worker.lisp -o worker.wasm --no-wasi --host-fetch --emit-js-glue
$ ls worker.*
worker.js  worker.lisp  worker.wasm
```

The generated file asks for the one thing a declaration cannot state: what
each host function *does*. `host` is a plain function per import, keyed by
import module and field, taking and answering ordinary JavaScript values —
never a `(ptr, len)` pair:

```js
import { instantiate, suspending } from "./worker.js";

const lisp = instantiate(module, {
  env: {
    fetch: suspending(async (request) => hostFetch(request)),
    readResponseBody: suspending(async () => nextChunk()),
    readRequestBody: () => take(requestBody),
    writeResponseBody: (chunk) => chunks.push(chunk),
  },
});
const reply = await lisp.handleRequest(head);
```

A chunk source must eventually answer `null`, or the module pulls the same
octets forever — `take()` above hands the body over once and then reports the
end. The glue holds whatever did not fit and drops it at the next call into the
module; a host whose source moves *inside* one call (a new upstream reply, say)
drops it with `lisp.drop("env.readResponseBody")`, since only that side knows.

`suspending()` is how a host says which of its entries answer a promise, and
it is per entry because the wrapper is not free: an import that answers
*synchronously* through one still parks the stack and returns to the event
loop. Mark one and the file switches into its JSPI shape — the marked imports
are wrapped, the entry points the build listed are entered through
`promising`, and every call rides one promise chain. Mark none and the same
file drives a synchronous host, where an entry point answers a value rather
than a promise. A callback that answers a promise without being marked is
reported by name rather than handing the module a `Promise` where an `i32` was
due.

Host state belonging to ONE call — what the module pulls during it, what the
call leaves behind — is set inside that same critical section, because a
suspended call returns to the event loop and the next request would otherwise
move it:

```js
const reply = await lisp.serially(async (entry) => {
  requestBody = bytes;
  chunks = [];
  return entry.handleRequest(head);
});
```

An import a declaration alone would miss is written too: under `--host-random`
the entropy source is *implemented* rather than asked for, since preview1 fixes
what `random_get(buf, len)` does. And a `:bytes` result is answered with chunks
(`null` ends them), never with the module's buffer: the generated cursor keeps
whatever did not fit, so which source the chunks come from — a
`ReadableStream`, a `Uint8Array` — is all a host is left to decide.

**Where the transport already fixed a host function, the file writes that too.**
Two halves of a reactor's boundary are not the program's choice at all, so the
generated file exports them: `defaultHost()`, the `env.fetch` half
`--host-fetch` fixes in both directions, and `worker(module)`, which maps a
`Request` onto the envelope and a `Response` off it. That holds on **either**
[boundary](#choosing-the-body-boundary---host-boundary): where a body leaves the
envelope, the reader it comes from is the `Request` `worker()` is already
holding and the `Response` it is already building, so the body imports are
written too. A Worker is then three lines:

```js
import module from "./worker.wasm";
import { worker } from "./worker.js";

export default worker(module);
```

Both are defaults, not replacements. `worker(module, options)` takes `host` —
import entries laid over the derived ones one at a time — and `remoteAddr`, a
`(request, env, ctx) => string` for the envelope's optional client address,
which is the one thing a runtime-neutral file may not guess (on Cloudflare it is
`(r) => r.headers.get("cf-connecting-ip")`). What is NOT written is an import
the program declared itself: `instantiate` still names it, and the sketch at the
top of the generated file then says `worker(module, { host })` instead.

The flag needs `--no-wasi` and a `.wasm` output: a component is instantiated
through its own bindings generator, and a `--no-gc` module imports nothing, so
`new WebAssembly.Instance(module, {})` is already the whole of its glue. Nine
worked examples on both boundaries — every reactor under
[examples/cloudflare-workers](https://github.com/making/rontolisp/tree/develop/examples/cloudflare-workers)
but one: `src/worker.js` is generated and checked in, and `src/index.js` is the
three lines above.
[httpbin](https://github.com/making/rontolisp/tree/develop/examples/cloudflare-workers/httpbin)
is the exception, and says so: it declares its `rontolisp:wasm-export` by hand,
and only the *synthesized* bridge is recognised as the envelope's own entry
point, so no `worker()` is written for it and its host stays hand-written.

## Overlapping Calls (--reentrant)

A module that can suspend refuses a second call by default: nothing in it owns
its state per call, so every export wrapper carries a re-entry guard and the
build's obligation line says *serialise calls*. That is correct, and it costs
the whole width of an I/O-bound workload — eight concurrent upstream round
trips through one serialised instance take eight round trips. One instance per
in-flight call avoids the queue but pays an instantiation per call and a GC
heap per instance.

`--reentrant` is the opt-in that makes overlap sound on **one** instance: the
module then owns its per-call state, the guard is dropped, and a JSPI host may
start a call while another is parked. What overlaps is the parked time — one
stack still runs at a time — so this buys I/O overlap, never CPU parallelism.

What moves, and what a host owes for it:

- Every dynamically bound special variable lives in a per-call task record
  instead of the shared module global, so two overlapped calls binding the
  same variable each read their own binding back.
- Linear-memory staging that must survive a park moves off the scratch stack
  into recycled park blocks (`__ronto_park_alloc` / `__ronto_park_free`, both
  exported). Three rules follow: a `:string`/`:s-expr` **export result**'s
  `(ptr, len)` is a park block the *reader* frees after decoding; a
  `:string`/`:s-expr` **import result** must be written into a park block,
  which the module frees; and a `:bytes` receive buffer a host passes into an
  export must be a park block too.
- An arena bracket (`__ronto_alloc_mark` / `__ronto_alloc_reset`) around an
  entry call is popped *synchronously* the moment the call starts — the
  arguments are consumed at entry — and the reset never goes below a live park
  block.

[`--emit-js-glue`](#generating-the-host-glue---emit-js-glue) writes all of
this and drops the queue, so a generated host needs nothing by hand; the
build's obligation lines state the same rules for a hand-written one.

The flag requires a program that can suspend (an `:async t` import, or
`--host-fetch` with `rontolisp:fetch` used) and a `--no-wasi` core module. It
cannot be combined with
[`--host-boundary=streaming`](#choosing-the-body-boundary---host-boundary) —
its body imports are a host-side cursor ("the current request's body") with no
per-call identity, exactly what overlapped calls lack — or with `--dynamic`.
Reach for it when a workload is I/O-bound *and* cannot afford an instance per
request: measured on the envelope Worker shape, eight concurrent 100 ms
upstream round trips answer in about 125 ms on one instance, against about
800 ms serialised.

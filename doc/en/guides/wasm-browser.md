# Running WASM in a Browser

Two paths deliver a rontolisp WASM build to a browser:

- **Components via `jco transpile`** — turns a component into plain
  JavaScript modules whose exports become JavaScript functions.
- **Reactor modules by hand** — a `--no-wasi` or `--no-gc` core module has
  no imports, so `WebAssembly.instantiate` + `instance.exports` is the whole
  host side. Node and the browser use the same code.

## Running a Component in a Browser (jco)

A component is not a wasmtime-only artifact. `jco transpile` turns one into
JavaScript, and the result runs in a browser — the exports become plain
JavaScript functions.

The example used throughout this section is `count-vowels`: one exported
function taking a string and returning how many vowels it contains.

```lisp
;; count-vowels.lisp
(defun vowelp (c)
  (or (char= c #\a) (char= c #\e) (char= c #\i) (char= c #\o) (char= c #\u)
      (char= c #\A) (char= c #\E) (char= c #\I) (char= c #\O) (char= c #\U)))

(defun count-vowels (s)
  (let ((n 0))
    (dotimes (i (length s))
      (when (vowelp (char s i))
        (setq n (+ n 1))))
    n))

(rontolisp:wasm-export 'count-vowels :params '(:string) :returns :int)

(count-vowels "Hello, World!")   ; => 3
```

It is pure compute — no cons, no I/O — so it stays inside the
[`--no-gc` subset](wasm-nogc.md#eligible-subset) and compiles to a component
with zero imports. jco camel-cases the component-model export name, so
`count-vowels` arrives as `countVowels`. (Verified with jco 1.25.2 on Chrome
149.) The same program driven from a Node host and from a Java host, with the
export declared in WIT instead of `wasm-export`, is
[`examples/count-vowels`](https://github.com/making/rontolisp/tree/develop/examples/count-vowels).

**A `--no-gc --component` needs nothing at all.** Its world has no imports,
so jco emits one self-contained ES module — the core WASM base64-inlined
inside it, about 90 KB for `count-vowels` — with no `import` statements of
its own. The page supplies no shim, no import map and no polyfill:

```bash
rontolisp count-vowels.lisp --no-gc --component --optimize -o cv.wasm
npx @bytecodealliance/jco transpile cv.wasm -o dist
```

```html
<script type="module">
  const { countVowels } = await import('./dist/cv.js');
  console.log(countVowels('Hello, World!'));  // 3
</script>
```

**A printing `--no-gc --component` cannot run through jco yet.** Its
[print micro-adapter](wasm-nogc.md#compact-component-output---no-gc---component)
imports `wasi:cli/stdout@0.3.0` and lifts every export async, so it hits
the same jco gaps as the GC component below (jco cannot call an
async-lifted export, and its `future` runtime is incomplete) — and the
WASI 0.3 shim is Node-only anyway. Keep the program print-free if the
component's destination is jco or a browser; the
[plain module path](#appendix-calling-a-module-from-javascript) with a
hand-written import object is unaffected.

**A wasm-GC `--component` loads and computes, but cannot print there yet.**
Chrome supports wasm-GC, JSPI and the canonical ABI, and the component's
synchronous exports return correct values. Two gaps are in the way of the
rest, both on the JavaScript side (wasmtime runs all of it):

- The WASI 0.3 imports it needs have no browser implementation:
  `@bytecodealliance/preview3-shim` declares only a `node` condition in its
  package `exports` and pulls in `node:worker_threads`, `node:net`,
  `node:http`, ... A page must hand-write a stand-in for the nine members
  jco destructures at module top level — `environment.getEnvironment`,
  `stdout.writeViaStream`, `stderr.writeViaStream`, `stdin.readViaStream`,
  `monotonicClock.now`, `systemClock.now`, `preopens.getDirectories`,
  `types.Descriptor`, `random.getRandomU64` — which for a pure-compute
  export only have to exist.
- Printing then fails inside jco's own generated code, which *references*
  `FutureReadableEnd` / `FutureWritableEnd` / `FutureEnd` but defines none
  of them (`ReferenceError: FutureReadableEnd is not defined`). It is
  reached through `wasi:cli/stdout`'s `write-via-stream`, whose WIT result
  is a `future`. Separately, jco cannot yet *call* an async export (its
  0.3 async ABI gap again), which is what an
  [`:async t`](wasm-component.md#component-model-function-exports-wasm-export)
  I/O export is.

Node is the weaker host here: Node 22 has no JSPI
(`WebAssembly.Suspending is not a constructor`), so it cannot even
instantiate a transpiled GC component, while Chrome can.

## Appendix: Calling a Module from JavaScript

A reactor module (`--no-wasi` or `--no-gc`) imports nothing, so the whole
host side is "instantiate, then call the exports" — and it is the same code
in Node and the browser. Here is a complete, copy-paste example end to end.
Start with a small kit of three exports:

```lisp
;; mathkit.lisp
(defun fact (n) (if (<= n 1) 1 (* n (fact (1- n)))))
(defun area (r) (* 3.141592653589793 r r))
(defun in-range (x lo hi) (if (< x lo) nil (if (> x hi) nil t)))
(rontolisp:wasm-export 'fact     :params '(:int)           :returns :int)
(rontolisp:wasm-export 'area     :params '(:float)         :returns :float)
(rontolisp:wasm-export 'in-range :params '(:int :int :int) :returns :bool)
```

Compile it with `--no-gc` (runs on any engine) and `--optimize` (drops
everything unreachable from the exports — here the whole module is ~200
bytes):

```bash
rontolisp mathkit.lisp --no-gc --optimize -o mathkit.wasm
```

On Node 18+, save this as `run.mjs` and run `node run.mjs`:

```js
import { readFile } from 'node:fs/promises';

// Node reads the .wasm from disk. In a browser, use the streaming fetch shown below.
const bytes = await readFile(new URL('./mathkit.wasm', import.meta.url));
const { instance } = await WebAssembly.instantiate(bytes);   // no import object

const ex = instance.exports;
console.log(ex.fact(10));                         // 3628800
console.log(ex.area(2));                          // 12.566370614359172
console.log(Boolean(ex['in-range'](5, 0, 10)));   // true   (:bool crosses as 0 / 1)
console.log(Boolean(ex['in-range'](42, 0, 10)));  // false
```

```
3628800
12.566370614359172
true
false
```

The browser differs only in how the bytes are loaded — `instantiateStreaming`
takes a `fetch` directly — so a whole page is:

```html
<!doctype html>
<script type="module">
  const { instance } = await WebAssembly.instantiateStreaming(fetch('./mathkit.wasm'));
  const ex = instance.exports;
  document.body.textContent = `fact(10) = ${ex.fact(10)}, area(2) = ${ex.area(2)}`;
</script>
```

A few boundary details worth knowing:

- A hyphenated Lisp name such as `in-range` is not a valid JavaScript
  identifier, so reach it with bracket access: `ex['in-range'](...)`.
- `:int`/`:float` arrive as plain JS numbers; `:bool` crosses as an `i32`
  (`0`/`1`), so wrap it in `Boolean(...)` for a real JS boolean.
- A **`--no-gc`** module runs on **any** WebAssembly engine; a GC
  **`--no-wasi`** module needs a wasm-GC-capable one (Node 22+, current
  browsers). The JavaScript above is byte-for-byte identical for both —
  swap the compile flag and nothing else changes.

### Passing strings (`:string`)

The scalar example above needs no memory because `:int`/`:float`/`:bool`
cross the boundary as plain numbers. A `:string` instead passes a `(ptr,
len)` pair through the module's exported `memory`: the host writes the
argument bytes into memory (at an offset reserved by the exported
`__ronto_alloc(size)` bump allocator), passes `(ptr, len)`, then decodes
the `(ptr, len)` the export returns.

`:string` works under `--no-gc`, so the module still runs on **any** engine
— as long as the function stays within the non-GC string subset (see the
[eligible subset](wasm-nogc.md#eligible-subset)). A greeting builder is
enough to show the protocol:

```lisp
;; greetkit.lisp
(defun greet (name) (concatenate 'string "Hello, " name "!"))
(rontolisp:wasm-export 'greet :params '(:string) :returns :string)
```

```bash
rontolisp greetkit.lisp --no-gc --optimize -o greetkit.wasm
```

```js
import { readFile } from 'node:fs/promises';

const bytes = await readFile(new URL('./greetkit.wasm', import.meta.url));
const { instance } = await WebAssembly.instantiate(bytes);   // no import object
const ex = instance.exports;
const enc = new TextEncoder(), dec = new TextDecoder();

// Copy a JS string into linear memory; return its (ptr, len).
function write(str) {
  const b = enc.encode(str);
  const ptr = ex.__ronto_alloc(b.length);
  new Uint8Array(ex.memory.buffer, ptr, b.length).set(b);
  return [ptr, b.length];
}
// Decode a (ptr, len) result. Re-read ex.memory.buffer AFTER the call: a call may grow
// memory, which detaches the previous ArrayBuffer.
const read = (ptr, len) => dec.decode(new Uint8Array(ex.memory.buffer, ptr, len));

console.log(read(...ex.greet(...write('rontolisp'))));     // Hello, rontolisp!
```

```
Hello, rontolisp!
```

With [`--no-gc --component`](wasm-nogc.md#compact-component-output---no-gc---component)
the same `:string` export instead crosses as a typed component-model
`string`, and all of the host-side glue above disappears (the canonical ABI
does the copying, and a post-return function keeps the heap flat).

Richer string functions (`string-upcase`, `subseq`, `string=`, …) are
outside the non-GC subset; using one means compiling for the wasm-GC
backend (`--no-wasi`) instead — the boundary protocol is identical, only
the engine must be wasm-GC capable. The `:s-expr` example below shows that
path.

### Passing lists (`:s-expr`)

A `:s-expr` carries **any** Lisp value as s-expression *text*: the module
parses the input with its embedded reader and prints the result back, over
the same `(ptr, len)` / `__ronto_alloc` protocol. That reader/printer/cons
machinery is **wasm-GC only**, so `:s-expr` (and the richer string
functions above) need `--no-wasi` and a wasm-GC-capable engine (Node 22+, a
current browser):

```lisp
;; textkit.lisp
(defun shout (s) (string-upcase s))
(defun rev (lst) (reverse lst))
(rontolisp:wasm-export 'shout :params '(:string) :returns :string)   ; "hello" -> "HELLO"
(rontolisp:wasm-export 'rev   :params '(:s-expr)  :returns :s-expr)    ; a list, reversed
```

```bash
rontolisp textkit.lisp --no-wasi --optimize -o textkit.wasm
```

```js
// Same instantiate + write/read helper as above (textkit.wasm needs a wasm-GC engine).
console.log(read(...ex.shout(...write('hello'))));         // HELLO
console.log(read(...ex.rev(...write('("a" "b" "c")'))));   // ("c" "b" "a")
```

```
HELLO
("c" "b" "a")
```

In the browser only the loading line changes
(`WebAssembly.instantiateStreaming(fetch(...))`); the
`write`/`read`/`memory`/`__ronto_alloc` logic is identical. A function that
returns a multi-value `(ptr, len)` shows up in JS as a two-element array,
hence `read(...ex.shout(...))`.

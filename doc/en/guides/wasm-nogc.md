# WASM Non-GC Output (`--no-gc`)

Every GC-value-model output — even an optimized reactor — needs a **wasm-GC
capable** runtime, because every value is a GC heap type (`i31ref`, the
float struct, `(ref eq)`). Add `--no-gc` to emit a plain **MVP** module
instead: no rec group, no `struct`/`array`/`i31` type, no `eqref` and no
import (a plain linear memory is added only when the program uses strings —
see [below](#strings) — and the single `fd_write` import only when it
[prints](#printing-print--princ--terpri)). A print-free module instantiates
with no import object and runs on any MVP-class runtime with **no `-W gc`**:

```bash
rontolisp fact.lisp --no-gc --optimize -o fact.wasm
wasmtime run --invoke fact fact.wasm 5      # => 120, ~76 bytes, no -W gc needed
```

It achieves this by lowering each value directly onto an unboxed wasm
scalar, plus a small linear-memory representation for strings — so the
eligible subset is a restriction of the language, not a different one. The
program shape is also restricted: the top level may contain **only**
`defun`s and `rontolisp:wasm-export` directives (a pure-compute reactor —
there is no `_start`), and the boundary designators are `:int`, `:long`,
`:float`, `:bool`, `:string` (and `:void`/omitted); `:s-expr` is **not**
supported — it would need the cons/reader/printer runtime this backend
deliberately omits.

Numeric vector kernels (the [`vec:` package](simd-acceleration.md)) work
under `--no-gc` too, lowered to plain scalar loops by default — so a vector
program keeps the "runs on any MVP runtime" property above. Add
[`--simd`](../compiling/wasm.md#simd-acceleration---simd) to lower those
kernels to native WebAssembly SIMD (`v128`) instead, which then needs a
runtime with the SIMD proposal (on by default in wasmtime).

## Eligible subset

A function is eligible only if its **entire transitive call graph** stays
inside this subset:

- numbers and booleans: arithmetic (`+ - * / mod rem 1+ 1- abs min max sqrt`),
  the integer bitwise operators (`logand logior logxor lognot ash`),
  comparison and predicates (`= < <= > >= not zerop plusp minusp evenp
  oddp`);
- control and binding: `if`/`when`/`unless`/`cond`/`progn`/`let`/`let*`,
  recursion and calls to other eligible functions;
- iteration and local mutation: `dotimes`/`do`/`do*` and the underlying
  `while`/`setq`/`return`, with a let/`do`-bound variable freely reassigned;
  `loop` is eligible only for its non-consing clauses (numeric `for`,
  `sum`/`count`/`maximize`/`minimize`, `repeat`/`while`/`until`/`do`/
  `return`) — its `collect`/`append`/`nconc` and `for ... in`/`on` clauses
  allocate lists and are not;
- float/int conversions: `float truncate floor ceiling round`;
- strings and characters: string literals, character literals,
  `(concatenate 'string ...)`, `length`, `subseq`, `string=`, `char`,
  `char-code`/`code-char`, `char=` and `princ-to-string` (of integers,
  floats and strings). There is no separate character type: a character is
  represented by its code point, so the portable idioms
  `(char= (char s i) #\x)` and `(char-code (char s i))` behave exactly like
  the other backends, while a bare `(char s i)` crossing an `:int` boundary
  shows the code;
- printing: `print`, `princ` and `terpri` (without the optional stream
  argument) — see [below](#printing-print--princ--terpri);
- memory reclamation:
  [`rontolisp:with-arena`](#reclaiming-from-lisp-rontolispwith-arena).

Anything else that would allocate a heap object (cons/list, symbols,
vectors, hash tables, `eval`/`apply`, I/O, `dolist`/list iteration, a free
variable or assignment to a global, a lambda-list keyword such as
`&optional`/`&rest`/`&key` — the rest list is a cons) makes the function
ineligible. Rather than miscompile silently, that is a **compile error**
naming the offending operation, so the boundary stays explicit.

## Numeric model

Each value's wasm type is chosen by static type inference: integers use
`i64`, floats use `f64`. Types are inferred with a fixpoint over the call
graph seeded by the export boundary designators, and where an integer and a
float meet (e.g. `(* 3.14 n)`) the integer is promoted to `f64`. Using `i64`
makes integer arithmetic exact to 2^63 — far wider than both the GC
backend's `i31` fixnums and what an all-`f64` lowering (exact only to 2^53)
could offer; for example `a*a - (a-1)*(a+1)` stays exactly `1` even when the
intermediates exceed 2^53.

Inference also widens automatically: a let/`do`-bound variable takes the
join of its initializer and every value assigned to it, so an integer
accumulator summed with floats becomes an `f64`:

```lisp
(defun sum-squares (n)        ; sum of i*i for i in 0..n-1, as a float
  (let ((acc 0))              ; acc starts as an integer 0 ...
    (dotimes (i n)
      (setq acc (+ acc (* (float i) (float i)))))  ; ... and widens to f64 here
    acc))
(sum-squares 5)  ; => 30.0
```

Under `--no-gc` this infers `acc` (and the return value) as `f64` while the
loop counter `i` stays `i64`.

There is no rational type, so two things differ from full Common Lisp and
from the GC backend: `/` is floating-point division (no `1/3` ratios), and a
value is false in a boolean context exactly when it is zero (Common Lisp
treats only `nil` as false). The **boundary** designators stay host-width —
`:int`/`:bool` cross as a 32-bit `i32` (as in the GC backend), so a returned
value outside the 32-bit range wraps; the wide `i64` range applies only to
the internal computation. When a parameter or result can exceed the 32-bit
range, declare it `:long` — it crosses the boundary as `i64` with no
`wrap`/`extend` (`:long` is `--no-gc`-only; the GC backend rejects it, its
integers being `i31ref`). For the numeric kernels this mode targets
(factorials, math/finance functions, validators) the results match the
interpreter and the GC backend.

## Strings

A string is an `i32` pointer to a `[length][bytes]` header in linear memory,
and `(concatenate 'string ...)` bump-allocates a fresh buffer — so building
up a string is just an accumulator loop:

```lisp
(defun stars (n)               ; an n-character run of '*'
  (let ((out ""))
    (dotimes (k n)
      (setq out (concatenate 'string out "*")))
    out))
(stars 5)  ; => "*****"
```

Slicing and inspection work on the same representation: `length` reads the
header, `subseq` copies a slice into a fresh buffer, `string=` compares
content byte-wise, `char` indexes a byte, and `princ-to-string` renders an
integer — enough for routing/parsing kernels, not just accumulation:

```lisp
(defun describe-int (n)
  (let ((s (princ-to-string n)))
    (concatenate 'string s " has " (princ-to-string (length s)) " chars")))
(describe-int -42)  ; => "-42 has 3 chars"
```

A module that uses strings gains a (growable) linear memory, and exports
that `memory` plus a `__ronto_alloc(size)` bump allocator alongside your
functions. A `:string` parameter arrives as a `(ptr, len)` pair the host
writes into memory, and a `:string` result is returned the same way — so a
string-valued export needs a host that can read/write the exported memory
(JavaScript, a small Node script, the browser playground) rather than just
`wasmtime --invoke`. The [browser guide](wasm-browser.md#passing-strings-string)
walks through the JS side, and
[`--no-gc --component`](#compact-component-output---no-gc---component)
removes the manual protocol entirely.

This is what lets the ASCII-art Mandelbrot renderer run with no wasm-GC:
[`examples/console/mandelbrot-nogc.lisp`](https://github.com/making/rontolisp/blob/develop/examples/console/mandelbrot-nogc.lisp)
keeps the floating-point escape-time loop but returns the rendered grid as
one string instead of printing it:

```console
$ rontolisp examples/console/mandelbrot-nogc.lisp --no-gc --optimize -o mandelbrot.wasm
$ node -e '(async () => {
  const ex = (await WebAssembly.instantiate(
    require("fs").readFileSync("mandelbrot.wasm"), {})).instance.exports;
  const [p, n] = ex.mandelbrot(-2.5, 1.0, -1.2, 1.2, 70, 30, 30);
  process.stdout.write(Buffer.from(new Uint8Array(ex.memory.buffer, p, n)).toString());
})()'
```

The export's type is not written in that Lisp file at all: a checked-in
world ([`mandelbrot_component.wit`](https://github.com/making/rontolisp/blob/develop/examples/console/mandelbrot_component.wit))
declares `export mandelbrot: func(x0: f64, ..., max-iter: s32) -> string;`,
and [`rontolisp:wit-export`](wit-contracts.md#implementing-a-wit-world-wit-export)
says the program implements it. One directive serves both builds: the core
module above is byte-identical to the hand-written `wasm-export` it
replaced, and the same source compiles as a component where
`wasmtime run --invoke 'mandelbrot(-2.5, 1.0, -1.2, 1.2, 70, 30, 30)'`
returns the string with no host memory code and no runtime flags.

## Printing (`print` / `princ` / `terpri`)

An exported function can print: `print` (readable form plus a trailing
newline, so strings come out quoted), `princ` (display form, no newline)
and `terpri` (a newline) work inside the eligible subset, with output
byte-identical to the interpreter:

```console
$ cat show.lisp
(defun show (n)
  (print n)
  (print (* 1.5 n))
  (print "done"))
(rontolisp:wasm-export 'show :params '(:int) :returns :void)
$ rontolisp show.lisp --no-gc -o show.wasm
$ wasmtime run --invoke show show.wasm 4
4
6.0
"done"
```

Floats print through the same digit-extraction printer as the GC backend,
including the IEEE edges (`NaN`, `Infinity`/`-Infinity`, `-0.0`; a magnitude
≥ 2^63 uses the WASM backends' `E`-notation shape). Each `print` of a
number renders its text into a transient string that is reclaimed
immediately, so a print loop does not grow the heap.

Two things to know:

- **A printing module has one import.** `print`/`princ`/`terpri` write
  through a single `wasi_snapshot_preview1.fd_write` import — added **only
  when the program prints**, so a print-free module keeps zero imports and
  its exact bytes. Any WASI Preview 1 host provides `fd_write` for free
  (`wasmtime run`, Node's built-in `node:wasi` module), but a printing
  module no longer instantiates with an empty `{}` import object the way
  the [Mandelbrot snippet](#strings) does — a raw JavaScript embedder must
  supply `{ wasi_snapshot_preview1: { fd_write } }` (or use `node:wasi`).
- **Booleans print by literal only.** The value model has no runtime
  boolean type: `(print t)` / `(print nil)` print `t` / `nil`, but a
  *computed* boolean such as `(print (> a b))` prints its `0`/`1` integer.
  The optional stream argument and printing a packed float array are
  compile errors.

## Reclaiming memory (the arena API)

`__ronto_alloc` is a bump allocator that never frees, so a **resident** host
— one that keeps a single instance alive and calls it in a loop, allocating
a fresh input buffer each time — grows its linear memory without bound. Two
mechanisms keep it flat:

- **Automatic, for scalar returns.** When an export returns a non-memory
  scalar (`:int`/`:long`/`:float`/`:bool`/`:void`), its wrapper snapshots
  the heap top on entry and restores it on exit, so everything the *call*
  allocates (the internal copy of a `:string` argument, plus any
  `concatenate`/`subseq`/`princ-to-string` scratch) is reclaimed on return.
  Nothing to do host-side.
- **Manual, for the host's own buffer.** The host allocates its input
  buffer *before* the call, so it sits below the wrapper's auto-reset mark
  and is left live. To reclaim it too, the string-using module also exports
  a matched pair over the same heap pointer:

| export | signature | meaning |
| --- | --- | --- |
| `__ronto_alloc_mark` | `() -> i32` | snapshot the current bump-heap top |
| `__ronto_alloc_reset` | `(i32 mark) -> ()` | restore the top to a saved mark |

Snapshot **before** allocating the input, restore **after** reading the
result, and a resident instance stays perfectly flat no matter how many
times it is called:

```bash
node -e '(async () => {
  const ex = (await WebAssembly.instantiate(
    require("fs").readFileSync("count_vowels.wasm"), {})).instance.exports;
  const enc = new TextEncoder();
  const countVowels = (s) => {
    const b = enc.encode(s);
    const mark = ex.__ronto_alloc_mark();        // snapshot BEFORE allocating input
    const ptr = ex.__ronto_alloc(b.length);
    new Uint8Array(ex.memory.buffer, ptr, b.length).set(b);
    const n = ex.count_vowels(ptr, b.length);    // scalar result read out here
    ex.__ronto_alloc_reset(mark);                // pop the input + wrapper scratch
    return n;
  };
  const before = ex.memory.buffer.byteLength;
  for (let i = 0; i < 100000; i++) countVowels("Hello, World! " + i);
  console.log(before, "->", ex.memory.buffer.byteLength);   // 65536 -> 65536 (flat)
})()'
```

The arena is a manual stack, not a garbage collector, so two rules apply:

- Only reset to a mark taken **before** everything still live — popping to
  a mark taken *after* data you still need frees that data.
- A `:string`-**returning** export does *not* auto-reset (its result is a
  live heap pointer). **Read the returned bytes out of memory before
  calling `__ronto_alloc_reset`** — resetting first frees the string and
  the next allocation overwrites it.

The [`count-vowels` example](https://github.com/making/rontolisp/tree/develop/examples/count-vowels)
walks through this recipe with both a Node and an
[Endive](https://endive.run) (Java) host.

The wasm-GC backend exports the same
`__ronto_alloc_mark`/`__ronto_alloc_reset` pair with the same host recipe
(see the [wasm-GC arena API](wasm-gc-module.md#reclaiming-the-hosts-buffer-the-arena-api)),
but only there does the *host's* buffer need reclaiming — the engine
handles everything the Lisp side allocates. The automatic scalar-return
reset is `--no-gc`-only: it is sound because nothing a `--no-gc` call
allocates can outlive it (no cons, closures, hash tables or global `setq`
in the subset).

## Reclaiming from Lisp (`rontolisp:with-arena`)

Both mechanisms above fire at the **export boundary** — nothing is freed
*within* one call. A loop that allocates each iteration
(`concatenate 'string` builds a fresh buffer, `vec:zeros`/`vec:ones` a
fresh vector) therefore grows the heap for the duration of the call.
[`rontolisp:with-arena`](../reference/macros/rontolisp-with-arena.md) names
that reclamation boundary in the source: it snapshots the bump heap
pointer, runs its body, and pops everything the body allocated — keeping
only the body's own value (a string or packed float array result is copied
down to the snapshot point):

```lisp
(defun train (epochs n)
  (let ((acc 0.0))
    (dotimes (i epochs)
      (rontolisp:with-arena ()                    ; everything allocated inside ...
        (setq acc (+ acc (vec:sum (vec:ones n)))) ; ... is popped here
        ))
    acc))
```

With the arena, a hundred thousand iterations stay within the initial
linear memory; without it, the same loop grows by one vector per
iteration. The escape contract is the same as `__ronto_alloc_reset`'s:
**nothing allocated inside the body may be reachable after it, except the
body's own value.** On the interpreter, the JVM backend and the default
(wasm-GC) output, `with-arena` is observationally a plain `progn` — a real
garbage collector already reclaims — so the same source runs on every
backend.

## Compact Component Output (`--no-gc --component`)

Add `--component` to wrap the same MVP core module as a **WASM component**
whose exports become typed component-model exports, callable through the
canonical ABI with WAVE syntax. A print-free core module has zero imports,
so the wrap needs no WASI adapter, no shared-memory module and no wasm-GC
— the whole component stays in the hundreds of bytes for a small program
and runs with **no wasmtime flags at all**:

```bash
rontolisp fact.lisp --no-gc --component -o fact.wasm
wasmtime run --invoke 'fact(5)' fact.wasm
# 120
```

The typed WIT signature maps `:int` → `s32`, `:long` → `s64`, `:float` →
`f64`, `:bool` → `bool`, `:string` → `string`, and an omitted `:returns` →
no result. The component also transpiles with jco (`jco transpile`, where
`:long` surfaces as a JavaScript BigInt) and runs on any component-model
host, with no wasm-GC support required.

`:long` is valid here, unlike the GC component path — use it when a value
can exceed the 32-bit range, matching the backend's internal `i64`
arithmetic:

```lisp
;; cube.lisp
(defun cube (n) (* n n n))
(rontolisp:wasm-export 'cube :params '(:long) :returns :long)
```

```bash
rontolisp cube.lisp --no-gc --component -o cube.wasm
wasmtime run --invoke 'cube(2000000)' cube.wasm
# 8000000000000000000
```

A `:string` boundary crosses as a real component-model `string` — no manual
pointer handling on either side. The host lowers the argument bytes into
the module's own memory and reads the result back out through the
canonical ABI, and the module frees every per-call allocation afterwards
(a canonical *post-return* function pops the bump allocator to its base),
so a resident instance stays flat across repeated calls:

```bash
rontolisp greet.lisp --no-gc --component -o greet.wasm
wasmtime run --invoke 'greet("world")' greet.wasm
# "Hello, world"
```

[Printing](#printing-print--princ--terpri) works here too: a program that
prints gets a built-in **print micro-adapter** — three tiny fixed core
modules that implement the core's single `fd_write` import over WASI 0.3
(`wasi:cli/stdout`'s `write-via-stream` plus the async stream/future
built-ins), wired in only when the program prints. WASI 0.3 has no
synchronous write, so the exports of a printing program become **async
lifts** (the WIT world shows them as `async func`) — which is why the
component still runs with zero flags: everything it uses is base
component-model async, on by default in wasmtime 46+ (the wasmtime floor
for a *printing* component; a print-free one has no imports at all and
runs on older hosts too). The print output is byte-identical to the
interpreter — with the earlier `show.lisp`:

```bash
rontolisp show.lisp --no-gc --component -o show.wasm
wasmtime run --invoke 'show(4)' show.wasm
# 4
# 6.0
# "done"
# ()
```

Trade-offs against the plain `--no-gc` output, and current limits:

- A component needs a component-model-capable host; the raw core module
  runs on **any** WebAssembly engine through the plain embedding API. Both
  outputs stay available — pick per host, and note the component is *not*
  the default for `--no-gc`. (Without `--component`, a `:string` crosses
  as the manual `(ptr,len)` core ABI instead.)
- The component is a pure reactor: there is no `wasi:cli/run` entry
  (nothing runs at the top level). Printing inside an export works through
  the micro-adapter above; every other I/O stays outside the `--no-gc`
  subset as usual. `:async t` is rejected — a printing program's exports
  are lifted async automatically, and there is nothing else an export
  could suspend on.
- The export name must be a lower-kebab-case component-model name; for a
  Lisp name outside that grammar the compiler asks you to rename it with
  `:as`.
- `--optimize` composes: the core module is tree-shaken before the wrap.
- [`--emit-wit`](wit-contracts.md#emitting-the-wit-world---emit-wit)
  composes too, and writes a tiny import-free world of just the typed
  exports (plus the `wasi:cli/stdout@0.3.0` import — and `async func`
  export signatures — when the program prints).

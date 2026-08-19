# The Clock and Randomness

Two values a program cannot work out for itself: what time it is, and a number
nobody can predict. Both come from outside, which is why they are the pair whose
behaviour depends on the backend — and the pair a module with **no** host has to
have an answer for.

## Where the values come from

| build | `(random n)` | `rontolisp:random-bytes` | the clock |
| --- | --- | --- | --- |
| interpreter, JVM | the JVM's generator | works | the machine's clock |
| WASM (default, Preview 1) | the host's WASI `random_get` | works | WASI `clock_time_get` |
| WASM `--component` | the host's `wasi:random` | works | `wasi:clocks` |
| WASM `--no-wasi` | a built-in generator, **same sequence every instance** | signals | what the host wrote through `__ronto_set_time`; signals until it does |
| WASM `--no-wasi --host-random` | the host's `env.random_get` | works | as above |

Only the `--no-wasi` rows need a decision from you; everywhere else both values
are the host's own, and the rest of this page is about that last case.

## Randomness

Common Lisp's [`random`](../reference/functions/random.md) is a pseudo-random draw
from `*random-state*`, not an entropy API: nothing in its contract promises
unpredictability, and an image may start from a fixed state.
[`rontolisp:random-bytes`](../reference/functions/rontolisp-random-bytes.md) is the
separate API that does promise it, and it is available only where a real entropy
source is.

```lisp
(print (list (random 1) (< (random 10) 10)))   ; => (0 T)
```

The result type follows the limit — an integer limit yields an integer, a float
limit a float — so `(random 1)` is always `0`.

## The clock

[`get-universal-time`](../reference/functions/get-universal-time.md) reports
seconds since 1900-01-01 GMT; [`get-internal-real-time`](../reference/functions/get-internal-real-time.md)
and [`get-internal-run-time`](../reference/functions/get-internal-run-time.md)
report milliseconds, and only their differences are meaningful. All three return
an **integer on every backend**.

```lisp
(print (list (integerp (get-universal-time)) (>= (get-internal-real-time) 0)))   ; => (T T)
```

[`encode-universal-time`](../reference/functions/encode-universal-time.md) and
[`decode-universal-time`](../reference/functions/decode-universal-time.md) convert
between that integer and calendar fields with pure arithmetic, so they behave
identically everywhere — with one deliberate deviation: a missing time zone means
**GMT, not the machine's local zone**, because no backend-portable source of the
local zone exists (WASI exposes no timezone at all).

Waiting is the clock's other half. [`sleep`](../reference/functions/sleep.md) parks
the thread on the interpreter and the JVM, waits on the real host timer under
`--component` (costing no CPU), and busy-waits on the clock on WASM Preview 1,
whose imports include a clock but no timer. On `--no-wasi` it signals — see below.

## A module with no host — `--no-wasi`

A [`--no-wasi` module](wasm-gc-module.md#no-wasi-reactor-mode) imports nothing, so
neither value has anywhere to come from. What it does about that follows the rule
the whole flag follows: **a stub answers when the answer is true of the module,
and refuses when answering would mean inventing a value you could not tell from a
real one** — and a value the *host* hands in is not an invention, which is what
the two hooks below are for.

Randomness lands on the answering side by itself. The module carries its own
generator, which is inside `random`'s contract — `make-random-state` here answers
`nil`, so no state object is observable and "the sequence repeats" is a property
of the contract rather than a claim about the host. The consequence is worth
stating plainly: **unseeded, every instance of one module produces the same
sequence.** Because that generator is not entropy, `rontolisp:random-bytes`
signals rather than draw from it.

The clock lands on the refusing side by itself: a reading of 0 is not "no time",
it is 1970, and nothing the module could invent would *be* the time. So until a
host sets it, all three built-ins signal a catchable error naming the operator.

A library that reads the clock while it *loads* has no caller to catch that, so
the **build** names it for you instead of leaving it to the first run — see
[what the build tells you](wasm-gc-module.md#what-the-build-tells-you-before-you-run-it).

### Seeding the generator — `__ronto_seed_random`

The module cannot *import* the host's random by default: a core WebAssembly import
is not optional, so asking for one would break the very thing the flag is for
(instantiating with `{}`). It exports a hook instead. Call it once, **before
`_initialize`**, and even a library's load-time `(random ...)` draws from your seed:

```js
const instance = new WebAssembly.Instance(module, {});
instance.exports.__ronto_seed_random(
  new BigUint64Array(crypto.getRandomValues(new Uint8Array(8)).buffer)[0],
);
instance.exports._initialize();
```

Skip the call and you get the deterministic sequence, unchanged. The hook is on the
core-module shape only — a reactor component (`--component --no-wasi`) runs its top
level at instantiation, so there is no window before the first draw.

Seeding makes the sequence unpredictable per instance but **does not** re-enable
`rontolisp:random-bytes`: the generator is invertible from a single output, so a
seeded stream is not cryptographically strong, and the API that promises entropy
keeps saying no rather than handing you something that only looks like a CSPRNG.

### Setting the clock — `__ronto_set_time`

The clock's hook is the same shape, and takes **nanoseconds since the Unix epoch**:

```js
const instance = new WebAssembly.Instance(module, {});
instance.exports.__ronto_set_time(BigInt(Date.now()) * 1000000n);
instance.exports._initialize();
```

Calling it before `_initialize` is what makes a library that timestamps while it
*loads* loadable at all — `lack-middleware-session` reads the clock from a
top-level form, and without this the module dies during initialization rather
than at the first request.

The clock does not tick on its own: it holds the value you wrote until you write
another. That is less of a restriction than it sounds — a Cloudflare Worker's own
clock is frozen for the duration of a request as a timing-attack mitigation — and
the natural rhythm is to set it once per request, which is what
[the Worker examples](https://github.com/making/rontolisp/tree/develop/examples/cloudflare-workers)
do. The one thing it cannot support is waiting: `(sleep n)` signals here, because
nothing can make an interval elapse while your call is running.

Like the seed hook, it is on the core-module shape only. A reactor component runs
its top level at instantiation, so there is no moment at which a host could set
the time first; the clocks signal there, and say so.

### Drawing from the host — `--host-random`

`--host-random` replaces the built-in generator with a host call, so every draw is
the host's entropy — including draws inside a quickloaded library, which never
learns where the bytes came from:

```bash
rontolisp app.lisp --no-wasi --host-random -o app.wasm
```

The module then imports exactly one function, `env.random_get(buf, len) -> errno`.
That is preview1's signature, so a host that already has a WASI implementation can
forward it unchanged; from JavaScript it is one property:

```js
const instance = await WebAssembly.instantiate(module, {
  env: {
    random_get(ptr, len) {
      crypto.getRandomValues(new Uint8Array(instance.exports.memory.buffer, ptr, len));
      return 0;                                  // errno 0 = success
    },
  },
});
instance.exports._initialize();
```

Because the entropy really is the host's, `rontolisp:random-bytes` works here. No
`__ronto_seed_random` is exported — there is no module-local state left to seed.
`__ronto_set_time` is unaffected: the two services are independent, and only one of
them has a module-local generator to make redundant.

The zero-import default is unchanged; this is the opt-in, and the module now has an
import the host **must** provide. The tree shaker still drops it if the program
never draws. The flag is core-module only: a reactor component imports nothing by
contract, and a plain `--component` build already has `wasi:random`.

There is no `--host-clock` counterpart, because the export answers the same
question without costing the zero-import property. A live clock that advances
*during* a call would need one; nothing has needed that yet.

## Redefining `random`

A program's own `(defun random ...)` is called by the interpreter and ignored by the
compile backends, which emit the standard operator at the call site and warn that
they did — see
[Redefining a COMMON-LISP function](../reference/function-namespace.md#redefining-a-common-lisp-function).

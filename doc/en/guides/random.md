# Randomness (random)

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

## Where the numbers come from

| build | `(random n)` | `rontolisp:random-bytes` |
| --- | --- | --- |
| interpreter, JVM | the JVM's generator | works |
| WASM (default, Preview 1) | the host's WASI `random_get` | works |
| WASM `--component` | the host's `wasi:random` | works |
| WASM `--no-wasi` | a built-in generator, **same sequence every instance** | signals |
| WASM `--no-wasi --host-random` | the host's `env.random_get` | works |

Only the last two rows need a decision from you; the rest is the host's own
generator either way.

## The reactor's own generator (`--no-wasi`)

A [`--no-wasi` module](wasm-gc-module.md#no-wasi-reactor-mode) imports nothing, so
there is no host generator to reach. It carries its own instead, which is inside
`random`'s contract — `make-random-state` here answers `nil`, so no state object is
observable and "the sequence repeats" is a property of the contract rather than a
claim about the host. The consequence is worth stating plainly: **unseeded, every
instance of one module produces the same sequence.** Because that generator is not
entropy, `rontolisp:random-bytes` signals rather than draw from it.

There are two ways out, and they answer different questions.

### Seeding it from the host — `__ronto_seed_random`

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

The zero-import default is unchanged; this is the opt-in, and the module now has an
import the host **must** provide. `--optimize` still drops it if the program never
draws. The flag is core-module only: a reactor component imports nothing by
contract, and a plain `--component` build already has `wasi:random`.

## Redefining `random`

A program's own `(defun random ...)` is called by the interpreter and ignored by the
compile backends, which emit the standard operator at the call site and warn that
they did — see
[Redefining a COMMON-LISP function](../reference/function-namespace.md#redefining-a-common-lisp-function).

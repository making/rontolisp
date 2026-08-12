# A suspending host import should be a future, not a secret

Difficulty: High

`rontolisp:wasm-import` declares a host function and makes it callable like a
defun (`.kb/wasm-import.md`). Nothing in the declaration can say that the host
function is ASYNCHRONOUS -- and with JSPI it does not have to: a
`WebAssembly.Suspending`-wrapped import is called exactly like a synchronous one,
the wasm stack parks until the promise settles and resumes with the result. That
is measured and deployed (`examples/cloudflare-workers/dog-fetcher`).

Two things follow from "does not have to", and both are costs:

- **The source cannot say what it does.** The same call is `(host-fetch url)`
  here and `(await (fetch url))` on a component. A program that wants to run on
  both spells its I/O twice, which is what the shipped example does by not using
  `rontolisp:fetch` at all.
- **The obligation is invisible.** A suspending import may only be called on a
  stack entered through `WebAssembly.promising`, so an export that can reach one
  traps if the host calls it directly, and `_initialize` must never reach one at
  all. Today that is prose in a README; nothing in the build knows it, and the
  program cannot be asked.

## Proposal

An `:async t` option on `rontolisp:wasm-import`: the call returns a FUTURE, and
`rontolisp:await` resolves it -- the language's existing async surface, pointed at
the host boundary.

```lisp
(rontolisp:wasm-import 'host-fetch :from "env" :as "fetch"
                       :params '(:string) :returns :string :async t)
...
(rontolisp:await (host-fetch url))
```

On a reactor this is deliberately a DEGENERATE future: JSPI makes the call block
the wasm stack, so the value is ready when the wrapper returns, and the existing
P1 representation (`TYPE_P1_FUTURE`, settled at creation, resolved by
`FUNC_P1_FUTURE_AWAIT`) already says exactly that. The option therefore buys no
concurrency and is not meant to -- it buys ONE SOURCE that reads the same on a
reactor, on a component and on the interpreter, and it gives the compiler a fact
to act on.

**Naming collision, deliberate**: `rontolisp:wasm-export` already takes `:async t`,
where it means the stackful async lift (`.kb/wasi-component.md`). Import-side
`:async t` means "the host may suspend; the result is a future". Same word,
opposite direction, and the WIT vocabulary spells both as `async func` -- decide
whether that reading is enough or the import option needs its own word BEFORE
implementing.

## What the compiler can then do

1. **Name the host obligation at build time**, next to the clock and entropy
   lines (`compiler/NoWasiLoadPathRefusals`): which exports can reach a
   suspending import (so the host knows which to wrap in `promising`), and --
   as an ERROR, not a line -- whether the LOAD PATH reaches one, since
   `_initialize` cannot suspend and the trap it produces names nobody.
2. **Generate the host glue.** The page-side import object for the WebGL demos is
   already generated from WIT and pinned so the two halves cannot drift
   (`examples/browser/webgl-common/gl-imports.js`, `GlImportObjectTest`). The
   Suspending wrapper, the `__ronto_alloc` string return and the `promising`
   entry are the same kind of boilerplate, written by hand in every Worker that
   calls out.
3. **Lower `:async t` per backend** rather than per host: on `--component` the
   same declaration is a real `canon lower ... async` subtask with a real pending
   future (`.kb/fetch-http.md` has that machinery for `%http-client:send`), and
   on the interpreter/JVM the stub can be a resolved future. The declaration
   stops being reactor-specific.

## Non-obvious risk

A future that is settled on one target and genuinely pending on another is a
correctness cliff if any code observes the difference (`futurep` timing, two
fetches started before either is awaited -- concurrent on a component, serial on
a reactor). Either the reactor documents "started == finished" as part of the
option, or `:async t` is refused where it cannot be honoured. Decide it in the
todo, not in a bug report.

Related: `.todo/335` (fetch itself, the first user of this) and `.todo/337`
(suspending makes the module re-enterable).

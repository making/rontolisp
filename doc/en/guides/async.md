# Asynchronous Programming (async / await / futures)

The `rontolisp` package provides a small asynchronous surface modeled on
JavaScript promises and `async`/`await`, expressed in Lisp. None of it is part
of Common Lisp; reference every operator with the `rontolisp:` qualifier (see
[Packages](../reference/packages.md)). The unit of the model is a **future**: a
value standing in for a computation that may not have finished yet. Calling an
[`rontolisp:async-defun`](../reference/special-forms/rontolisp-async-defun.md)
returns one, [`rontolisp:await`](../reference/special-forms/rontolisp-await.md)
resolves it, and a handful of combinators build on top.

| Operator | Purpose |
|----------|---------|
| [`rontolisp:async-defun`](../reference/special-forms/rontolisp-async-defun.md) | Define an asynchronous function (returns a future) |
| [`rontolisp:async-lambda`](../reference/special-forms/rontolisp-async-lambda.md) | The anonymous counterpart |
| [`rontolisp:async`](../reference/special-forms/rontolisp-async.md) | `(async (defun ...))` / `(async (lambda ...))` — a JavaScript-style spelling of the two above |
| [`rontolisp:await`](../reference/special-forms/rontolisp-await.md) | Suspend until a future settles and return its value |
| [`rontolisp:futurep`](../reference/functions/rontolisp-futurep.md) | `t` if a value is a future |
| [`rontolisp:wait-for`](../reference/functions/rontolisp-wait-for.md) | A future that settles to `nil` after N milliseconds (the async counterpart of `cl:sleep`) |
| [`rontolisp:then`](../reference/functions/rontolisp-then.md) / [`then*`](../reference/functions/rontolisp-then-star.md) | Attach a transform to a future *as a value* |
| [`rontolisp:catch`](../reference/functions/rontolisp-catch.md) | Attach an error fallback to a future as a value |
| [`rontolisp:finally`](../reference/functions/rontolisp-finally.md) | Run a cleanup thunk on both the success and error channels |
| [`rontolisp:make-stream`](../reference/functions/rontolisp-make-stream.md) / [`stream-read`](../reference/functions/rontolisp-stream-read.md) / [`stream-write`](../reference/functions/rontolisp-stream-write.md) / [`stream-close`](../reference/functions/rontolisp-stream-close.md) / [`read-all`](../reference/functions/rontolisp-read-all.md) | Asynchronous byte/string streams |

> **Backend support.** The whole surface works on the interpreter, the JVM
> backend and the WASM `--component` backend, but the machinery under it
> differs. On the **interpreter and JVM** an async body runs on a virtual
> thread — after its first suspension it runs in *real parallelism* with the
> caller. On **`--component`** the body compiles into a cooperative,
> single-threaded state machine over the WASI 0.3 component-model async ABI
> ([see below](#under-the-hood-wasi-preview-3-futures--streams)); such a
> component must run with `wasmtime -W exceptions=y`. **Preview 1** WASM has no
> asynchronous host I/O, so async bodies run to completion eagerly (a
> degenerate-but-observably-consistent synchronous mode), and `wait-for` /
> the guest stream operations are compile errors there. **`--no-gc`** rejects
> the whole async surface at compile time.

## Futures and eager start

`rontolisp:async-defun` defines a function whose *call* starts the body
immediately and hands back a future rather than a value. The body runs until
its first `await` of an unsettled future (or until it finishes) — "eager
start" — then the caller resumes:

```lisp
(rontolisp:async-defun add-later (a b)
  (+ a b))
(rontolisp:await (add-later 20 22))   ; => 42
```

The call itself is an opaque future — `rontolisp:futurep` recognizes it, and
it prints as `#<FUTURE>`:

```lisp
(rontolisp:futurep (add-later 1 2))   ; => t
```

The future settles with the value of the last body form, or with the error the
body signaled (re-signaled when the future is awaited — see
[Errors](#errors-across-the-await-barrier)). The anonymous counterpart is
[`rontolisp:async-lambda`](../reference/special-forms/rontolisp-async-lambda.md),
and `(rontolisp:async (defun ...))` / `(rontolisp:async (lambda ...))` is an
equivalent JavaScript-flavored spelling of the two.

## Awaiting

`rontolisp:await` suspends the current asynchronous function until a future
settles and returns its settled value. It is *generic*: a settled future never
suspends, nested futures flatten, and a value that is not a future passes
through unchanged — so `await` can be applied uniformly to a value that may or
may not be a future.

```lisp
(rontolisp:await 42)   ; => 42
```

`await` placement is **lexical**: it is legal only inside an
`async-defun`/`async-lambda` body, or at top level (which is implicitly
asynchronous). In any plain `defun`/`lambda` — even one nested inside an
asynchronous body — it is an error at definition time:

```console
> (defun bad () (rontolisp:await 1))
rontolisp:await is only allowed inside rontolisp:async-defun/async-lambda or at top level
```

### Errors across the await barrier

An error signaled by an async body does not escape at call time; it settles the
future and re-signals the condition at the `await`. Catch it with a
`handler-case` around the await — condition-type dispatch works across the
barrier:

```lisp
(rontolisp:async-defun failing () (error "boom"))
(handler-case (rontolisp:await (failing))
  (error (e) (declare (ignore e)) "caught"))   ; => "caught"
```

## Overlapping work

Because a call is already running when it returns its future, several
asynchronous operations overlap — start them all, then await each (in any
order). The clearest illustration is `rontolisp:wait-for`, which returns a
future settling after a delay: it is the asynchronous counterpart of `cl:sleep`
(which *blocks* the whole program and takes seconds). Timers run concurrently,
so two started together settle in delay order, not start order, and awaiting
both takes about the longer delay, not the sum:

```lisp
(rontolisp:async-defun delayed (ms tag)
  (rontolisp:await (rontolisp:wait-for ms))
  tag)
(let ((slow (delayed 200 "slow"))
      (fast (delayed 20 "fast")))              ; both timers now running
  (list (rontolisp:await fast) (rontolisp:await slow)))   ; => ("fast" "slow")
```

The same overlap is what makes several [`rontolisp:fetch`](http-fetch.md)
requests run in parallel: start them, then await the responses.

## Composing futures as values (then / then* / catch / finally)

`await` is the right tool when the future is right there in your async body. But
a future is also a first-class value that can cross a boundary — be returned,
stored, passed around — and the caller on the other side need not itself be an
`async-defun` just because its callee is one. The combinator quartet transforms
a future *as a value*, each returning a fresh future:

- [`rontolisp:then`](../reference/functions/rontolisp-then.md) attaches a
  success transform. On the input's successful settlement it invokes the
  function with the settled value and settles to the result; on an upstream
  error the callback is skipped and the condition propagates unchanged. If the
  function itself returns a future, `await` flattens it (no
  `future<future<T>>`):

```lisp
(rontolisp:async-defun some-future-producer () 21)
(defun caller ()                                     ; a PLAIN defun, not async
  (rontolisp:then (some-future-producer) (lambda (v) (* 2 v))))
(rontolisp:await (caller))   ; => 42
```

- [`rontolisp:then*`](../reference/functions/rontolisp-then-star.md) is
  variadic chain sugar — thread a value through several stages without the
  nesting a manual chain would need:

```lisp
(rontolisp:async-defun produce () 40)
(rontolisp:await (rontolisp:then* (produce) #'1+ #'1+))   ; => 42
```

- [`rontolisp:catch`](../reference/functions/rontolisp-catch.md) attaches an
  error fallback (JavaScript `.catch`); a successful value passes through
  unchanged:

```lisp
(rontolisp:async-defun boom () (error "nope"))
(rontolisp:await
  (rontolisp:catch (boom) (lambda (c) (declare (ignore c)) :fallback)))   ; => :fallback
```

- [`rontolisp:finally`](../reference/functions/rontolisp-finally.md) runs a
  zero-argument cleanup thunk on *both* the success and error channels; the
  original outcome carries through (like `unwind-protect`):

```lisp
(defvar *cleanup-log* nil)
(rontolisp:async-defun make-value () 5)
(let ((v (rontolisp:await
           (rontolisp:finally (make-value)
                              (lambda () (push :done *cleanup-log*))))))
  (list v (reverse *cleanup-log*)))   ; => (5 (:done))
```

A non-future first argument to any of the four is a `type-error` — there is no
JavaScript-style auto-coercion to a resolved promise. And note that
`rontolisp:catch` is *not* Common Lisp's
[`catch`](../reference/special-forms/catch.md)/[`throw`](../reference/special-forms/throw.md)
tag-based special form: they live in different packages and qualified names
never collide (see the
[catch reference page](../reference/functions/rontolisp-catch.md) for the
naming details).

## Asynchronous streams

Where a future settles once, a **stream** delivers a sequence of chunks over
time. A guest-created stream is one value owning both ends: producers append
with [`rontolisp:stream-write`](../reference/functions/rontolisp-stream-write.md)
and finish with
[`rontolisp:stream-close`](../reference/functions/rontolisp-stream-close.md);
consumers take chunks with
[`rontolisp:stream-read`](../reference/functions/rontolisp-stream-read.md) (each
read yields a future) or drain the string chunks in one await with
[`rontolisp:read-all`](../reference/functions/rontolisp-read-all.md):

```lisp
(let ((s (rontolisp:make-stream)))
  (rontolisp:stream-write s "hello ")
  (rontolisp:stream-write s "world")
  (rontolisp:stream-close s)
  (rontolisp:await (rontolisp:read-all s)))   ; => "hello world"
```

`stream-read` returns a future settling to the next chunk, or to `nil` once the
stream is closed and drained — chunks are never `nil`, so a `nil` result always
means end of stream. A read on an open, empty stream stays pending until a write
arrives; that pending read is the suspension an awaiting async function parks
on.

Guest-created streams (`make-stream` / `stream-write`) exist on the interpreter
and the JVM backend. On `--component` the stream *operations* work too, but the
streams themselves arrive from the host: a [`rontolisp:fetch`](http-fetch.md)
response `:body` and a [`rontolisp:http-handler`](http-handler.md) request
`:raw-body` (in its default `:stream` mode) are asynchronous streams on every
backend.

## Under the hood: WASI Preview 3 futures & streams

The `--component` backend is the one place where the async model maps onto a
platform primitive rather than onto host threads. A WASI 0.3 (Preview 3)
component builds on the component-model **async canonical ABI**, whose two
built-in parametric types are `future<T>` (a one-shot asynchronous result) and
`stream<T>` (a sequence of chunks). rontolisp's futures and asynchronous
streams lower directly onto them:

- An `async-defun` / `async-lambda` body (and a top level containing `await`)
  compiles into an **entry + resume state machine** over first-class
  component-model futures. An `await` of a value that is already settled just
  continues; an `await` of a pending host operation genuinely **suspends the
  task**, and the component's event loop resumes it when the awaited event
  arrives. Tasks are **cooperative and single-threaded** — two in-flight
  operations of one component instance interleave, but never preempt each
  other. This is the deliberate divergence from the interpreter/JVM's virtual
  threads, where a body runs in *real* parallelism after its first suspension
  and racing on shared global state is the program's own responsibility.
- `rontolisp:wait-for` lowers to the host timer,
  `wasi:clocks/monotonic-clock@0.3.0`'s `wait-for`, returned as a pending
  future the event loop settles — which is why timers genuinely overlap in a
  component too.
- A fetch response's `:body` / a served request's `:raw-body` is a
  component-model `stream<u8>` wrapped as
  a rontolisp stream; `stream-read` of a chunk the host still has in flight is a
  pending future, so a slow body read parks only its own task while another
  task's timer or fetch keeps running.

Because the async ABI uses the component-model exception mechanism, any async
component must run with `wasmtime -W exceptions=y` on top of `-W gc=y`. All of
this rides the base component-model async support enabled by default in
wasmtime 46+ — no experimental feature flags remain. See the
[WASI 0.3 Component guide](wasm-component.md) for the component runtime as a
whole.

**Preview 1** WASM has none of this — there is no asynchronous host I/O in a
Preview 1 core module — so an async body simply runs to completion the moment
it is called, and its future is born already settled. The observable behavior
matches the other backends whenever an `await` is adjacent to the call that
produced the future (the common shape); it diverges only in that an error
signals at the *call* rather than at the `await`, and `wait-for` / the guest
stream operations are rejected at compile time. **`--no-gc`** rejects the entire
async surface by name.

A **`--no-wasi` reactor** is a Preview 1 module, so those degenerate futures are
what it has — and yet it is the one Preview 1 build that does real asynchronous
host work, because the *host* does the waiting rather than the guest.
[`--host-fetch`](http-fetch.md#fetching-from-a-reactor---no-wasi---host-fetch)
routes `rontolisp:fetch` at one host import, and a JavaScript host implements it
with `WebAssembly.Suspending` (JSPI): the whole wasm stack parks until the
promise settles, so `(await (fetch ...))` reads exactly as it does everywhere
else, and by the time `fetch` returns its future is already settled. The price
is paid on the host side, not in the Lisp — every export must be entered through
`WebAssembly.promising` and calls must be serialised (a re-entered export
refuses with a trap), and nothing on the **load path** may fetch, because
`_initialize` is the one stack a suspending host cannot park.

## Where async shows up

The async surface is small on purpose; most programs meet it through one of the
I/O features built on it:

- [HTTP Requests (fetch)](http-fetch.md) — `fetch` returns a future; a request
  body is drained with `read-all`.
- [Serving HTTP (http-handler)](http-handler.md) — a handler that awaits (for
  example, one that fetches) must itself be an `async-defun`.
- [TCP Sockets](tcp-sockets.md) — a pending `tcp-accept` or socket read
  suspends only its own task inside a component.
- [Host-driven reactors](wasm-gc-module.md#no-wasi-reactor-mode) — a synchronous
  handler cannot `await`, so it returns the FUTURE an `async-defun` produced and
  the reactor transport resolves it at the boundary.

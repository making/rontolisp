# sleep

`(sleep seconds)`

Blocks for `seconds` — any non-negative real, so sub-second waits are written as `0.5` — and returns `nil`. A zero or negative duration returns immediately. Durations are rounded to whole milliseconds, the resolution every backend shares with [`get-internal-real-time`](get-internal-real-time.md).

The interpreter and the JVM park the thread. `--component` waits on the real host timer (`wasi:clocks`), forcing it through the module scheduler: the wait costs no CPU and other pending tasks still progress. **WASM Preview 1 is the one backend that busy-waits**, looping on the clock until the deadline — its imports include a clock but no timer to wait on, so burning the interval is the only way to let it pass. The wait is honest there, but it costs a core and blocks the instance for its duration. A `--no-wasi` module **signals** instead of waiting: it imports no timer, and its clock only moves when its host writes it, so no interval can elapse while the call is running (see the [clock and randomness guide](../../guides/clock-and-random.md)).

```lisp
(sleep 0) ; => NIL
```

```console
(let ((start (get-internal-real-time)))
  (sleep 0.5)
  (print (>= (- (get-internal-real-time) start) 500)))
```

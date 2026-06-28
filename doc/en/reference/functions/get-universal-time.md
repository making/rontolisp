# get-universal-time

`(get-universal-time)`

Returns the current time as the number of seconds since the Common Lisp epoch of 1900-01-01 GMT (Unix time plus 2208988800). The interpreter and JVM return an integer; the WASM backend reads the clock (the real host clock in Preview 1, `wasi:clocks@0.3.0` in `--component` mode) and returns a **float**, because its 31-bit integers cannot hold the magnitude. Because of this, use the value in comparisons or differences rather than printing the raw number.

```lisp
(get-universal-time)
```

Each call yields the wall-clock time at the moment of the call, so the result is non-deterministic; subtracting two readings gives an elapsed number of seconds.

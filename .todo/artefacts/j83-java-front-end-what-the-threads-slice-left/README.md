# j83: what was measured (2026-11-17)

Results are in `.kb/java-frontend.md`, "java.util.concurrent".

- `order/` -- the order a JDK 25 walks a `ConcurrentHashMap` in, read from outputs alone.
  `Bins`, `Chain`, `Len`, `Copy2` are probes run under the JDK (`java Bins.java`); `Diff`
  (sized constructors), `VDiff` and `VDiffC` print a walk after every step and are run under
  the JDK and under rontolisp, the two outputs compared.
- `stack-switching/switch.wat` -- `wasmtime run -W stack-switching=y,function-references=y,gc=y,exceptions=y [-C collector=..] switch.wat`:
  wasmtime 49.0.0 aborts at a collection while the continuation is suspended; the null
  collector runs it (exit 5).
- `reach/` -- `rontolisp Buffer.java --dump-ir > buffer.ir; python3 reach.py buffer.ir`:
  how many functions of a program can reach a point where a thread is suspended.

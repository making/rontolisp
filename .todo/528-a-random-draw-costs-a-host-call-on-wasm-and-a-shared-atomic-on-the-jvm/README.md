# Spike sources for `.todo/528` (closed 2026-08-26)

The item is closed -- `random` now draws from a generator inside the program on
every backend (`.kb/random.md`), so these files measure the generator rather
than a host call. The `--no-wasi` line below is no longer a separate
measurement: every wasm build runs that same SplitMix64 step, inlined at the
draw site, and only the seeding differs.


10^7 `(random 1000)` draws, and the identical loop with the draw replaced by a
constant. The per-draw cost is the difference divided by 10^7. Top-level
spelling, which is `.todo/517`'s acceptance shape.

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
D=.todo/528-a-random-draw-costs-a-host-call-on-wasm-and-a-shared-atomic-on-the-jvm

sbcl --script $D/draw.lisp
java -jar $JAR $D/draw.lisp -o /tmp/D.class --class-name D && java -cp /tmp D
java -jar $JAR $D/draw.lisp -o /tmp/d.wasm && wasmtime run -W gc /tmp/d.wasm
java -jar $JAR $D/draw.lisp -o /tmp/dc.wasm --component && \
  wasmtime run -W gc=y -W exceptions=y /tmp/dc.wasm

# the in-module SplitMix64 that --no-wasi already emits, for the same loop
java -jar $JAR $D/draw.lisp -o /tmp/dn.wasm --no-wasi && \
  wasmtime run -W gc --invoke _initialize /tmp/dn.wasm

# where the preview1 time actually goes
wasmtime run -W gc --profile=perfmap /tmp/d.wasm            # warm the cache first
perf record -g -- wasmtime run -W gc --profile=perfmap /tmp/d.wasm
perf report --stdio --no-children -g none | head -20
```

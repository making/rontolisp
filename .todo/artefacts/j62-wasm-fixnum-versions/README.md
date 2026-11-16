# An integer test compiled twice on wasm: the measurements (2026-11-16)

The numbers of `.kb/wasm-int-fusion.md`, "An integer test compiled twice",
`.kb/starlark-frontend.md`, "An int test compiled twice", `.kb/hash-tables.md`
("a chain's key is asked `ref.eq`") and `.kb/defstruct.md` ("one instance test for several
readers"). The Starlark programs and `time.sh` are
`.todo/artefacts/j54-starlark-faster-than-cpython/`.

Every candidate was measured BEFORE the compiler was changed, on the module of the day
edited as text; the function numbers and the layout address in the commands are that
module's (`-Drontolisp.wasm.debug-func-sizes=true` prints `size <tab> number <tab> name`
per function on stderr):

```bash
wasm-tools print --name-unnamed m.wasm > m.wat
python3 sim-ideal-heads.py m.wat m2.wat 257 258 5316
wasm-tools parse m2.wat -o m2.wasm && wasmtime compile m2.wasm -o m2.cwasm
./ab.sh 7 m.cwasm m2.cwasm          # alternating runs, the least user seconds of each
```

- `sim-ideal-heads.py` -- sixty hand-written instructions in front of each index helper:
  `listidx.star` 1.20 -> 0.79 s.
- `sim-ideal-heads-with-redundancy.py` -- the same with what the compiled helpers repeat
  put back (`V=I`, `V=N`, `V=C`, `V=INC`): the instance casts and channel clears 0.80, the
  subscript's tests 0.83, the cell's second cast 0.81, all three 0.93. The other 0.27 s
  of the compiled helpers is no single construct (`wasmtime objdump`: two hundred
  instructions where the hand-written path is sixty).
- `sim-cold-tail-calls.py` -- the split head (below) with every call on a path it never
  takes turned into a tail call of the general function: 1.22 -> 1.16 s, inside the noise
  of the run. The numbers in the script are that module's.
- The helpers split in the library (a head that holds the list case alone, the general
  function behind it): 1.23 -> 1.15 s. Made.
- `sim-inline-mod.py` -- `i64.const C; call _fx_mod` as `i64.rem_s` and the sign fix:
  `loop.star` 0.63 -> 0.35 s. Made.
- `sim-eq-before-equal.py` -- `ref.eq` ahead of `_equal` in the hash table's walks:
  `dicts.star` 1.23 -> 1.15 s. Made.
- `_t_sym` (a call per `t` a function answers, a lazily made global) as a `global.get`
  with the global set when the program starts, by hand on `sorting.star`'s text:
  2.99 -> 2.87 s. Not made: every entry of a module would have to set it first.
- `prof.py` -- self and inclusive samples per function of a `wasmtime run
  --profile=guest,p.json,1ms` profile, named by the `debug-func-sizes` listing. It charged
  52% of a loop of 10,000,000 `gethash` to `puthash`, which the loop never calls: read the
  machine code (`wasmtime objdump --filter 'function[N]' m.cwasm`) or edit the text.
- `gethash` alone, 10,000,000 times over a loop of 0.11 s (plain Common Lisp, `equal`
  table of 5,003 fixnum keys): 0.40 s a hit, 0.35 a miss, 0.41 under `eql`.

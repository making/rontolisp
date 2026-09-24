# 953: wasm compile time against one list's length -- the measurements

The numbers live in `.kb/optimize-dead-code-elimination.md` ("The single-use local") and
`.kb/quoted-data.md` ("A long list is built in runs"); this directory keeps what produced them.
All on linux-x64, wasmtime 49.0.0, 2026-09-24.

## Programs (`gen.py`)

`python3 gen.py bq|sym|num N out.lisp` writes a backquote template of N numbers, a quoted
list of N symbols, or of N numbers. Compile with `java -jar ...-exec.jar -o x.wasm out.lisp`
and time `wasmtime compile -W gc=y,function-references=y,exceptions=y x.wasm`.

## Hand-written modules (`wat.py`, `watrun.sh`)

`watrun.sh N shape[:run]...` builds N cons cells per shape and times `wasmtime compile`:

| shape | what the function does |
| --- | --- |
| `deep` | every car, then N `struct.new` (operand depth N) |
| `local` | from the tail through one local (depth 2) |
| `chunk:K` | runs of K cars on the stack, the tail through a local |
| `funcs:K` | one helper function per run of K, taking the tail |
| `drop` | N independent `struct.new; drop` |
| suffix `call` | each car is a call instead of `ref.i31` |

| N | deep | deepcall | localcall | chunkcall:16 | chunkcall:64 | drop | funcscall:64 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 5,000 | | | 0.97 | 0.91 | 0.96 | 1.4 | |
| 10,000 | 4.9 | 39.0 | 2.1 | 2.2 | | 4.5 | |
| 20,000 | | | 7.3 | 6.9 | 7.8-8.5 | 18.8 | 0.2 |

`drop` at 10,000 under `-C collector=null`: 1.2 s; `-O regalloc-algorithm=single-pass`: 1.35 s.

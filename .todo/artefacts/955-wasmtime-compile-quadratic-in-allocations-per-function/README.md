# 955: wasmtime compile time against GC allocations per function -- the measurements

The conclusions live in `.kb/quoted-data.md` ("A long list is built in runs") and
`.kb/wasm-landing-pad-refresh.md` ("Cost"); this directory keeps what produced them. All on
linux-x64 (64 cores), wasmtime 49.0.0, 2026-09-24. The hand-written list shapes of `.todo/953`
are in `../953-wasm-compile-quadratic-in-list-length/`.

## The rontolisp-free reducer (`min.py`, `minrun.sh`)

`minrun.sh KIND "FLAGS" N...` times `wasmtime compile` of one function doing N x
`struct.new $s; drop`, `$s` an empty struct (`empty`), one `i32` field or one `eqref` field.

| N | copying (default) | drc | null | copying, single-pass regalloc | copying, opt-level=0 |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 5,000 | 1.61 | 0.10 | 0.27 | 0.32 | 0.58 |
| 10,000 | 4.79 | 0.15 | 0.61 | 0.71 | 1.94 |
| 20,000 | 15.16 | 0.40 | 1.56 | 1.52 | 4.84 |

wasmtime 49's default collector is `copying` (a flagless run matches the `copying` column).
At 10,000 under `copying`, register allocation is 3,824 of 4,391 ms (`perfunc.sh`). The draft
upstream report is `upstream-report.md` -- NOT filed.

## Do real programs reach it? (`build_examples.py`, `allocs.py`, `perfunc.sh`)

Every GC-wasm leg of `examples/examples.yaml` (135 modules; 4 legs need `rove`/`cl-who`,
which were not installed), census of `struct.new`/`array.new*` per function (`allocs.py`):

| module (`--optimize`) | most allocations in one function | that function's Cranelift ms (serial) | module `wasmtime compile` wall |
| --- | ---: | ---: | ---: |
| hello-ningle Worker | 5,813 (`%asdf-registry%` datum) | 1,789 | 46 s |
| hello-ningle Worker | 4,692 (`%class-meta-table` datum) | 1,629 | |
| hello-tiny-routes Worker | 3,493 | 673 | 1.2 s |
| hello-clack Worker | 1,558 | 278 | 1.0 s |
| every non-HTTP example | <= 317 (`llm`) | | |

Only the ningle/tiny-routes/clack family passes 1,000, and in each of them the allocation-heavy
function is NOT the longest compile: tiny-routes' largest function (41,923 instructions) takes
687 ms beside the datum's 673, clack's 688 beside 278, and ningle's fast-http `parse-request` takes **46,559 ms**
(translate 15,689, regalloc 29,157) -- the landing-pad refresh, `.todo/957`. `bodysize.py`
and `fsize.py` size and profile single functions; `perfunc.sh` ordinals are defined-function
order (index = ordinal - 1 + imported functions).

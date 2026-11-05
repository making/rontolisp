# Size of a Proxy-Wasm filter, measured

Everything here is `--no-wasi --optimize=size` unless it says otherwise, but for `test_plugin`, which writes
to stdout and is built without `--no-wasi` (its `_start` and two WASI imports are about 20 bytes more). gzip is `gzip -9 -c < file`.
Measured 2026-11-05. Behaviour: each row marked "tested" ran through a 16-test plugin suite of a host
embedding a wasm interpreter (edits, refusals, traps, timeouts, memory limits, local responses,
body replacement).

## The filters as they stand

`examples/proxy-wasm/sdk-examples/`, tested:

| Filter | raw | gzip | code | data | `--optimize` raw / gzip |
| --- | ---: | ---: | ---: | ---: | ---: |
| `http_body` | 86,494 | 31,096 | 77,147 | 7,694 | 100,122 / 35,817 |
| `http_headers` | 151,176 | 53,102 | 118,187 | 31,132 | 185,857 / 62,634 |
| `test_plugin` | 100,147 | 36,108 | 88,839 | 9,143 | 119,577 / 42,514 |

The numbers to get under, on both columns:

| Filter | raw | gzip |
| --- | ---: | ---: |
| `http_body` | 92,163 | 25,968 |
| `http_headers` | 94,189 | 25,472 |
| `test_plugin` | 122,261 | 36,790 |

`wasm-opt -Oz --all-features` over the three: 75,799 / 134,869 / 87,338 raw. Context, not a target
(`.kb/size-measurement.md`).

## What the SDK costs (`variants.py`), all tested

| Levers | `http_body` raw / gzip | `http_headers` raw / gzip | `test_plugin` raw / gzip |
| --- | ---: | ---: | ---: |
| none | 86,494 / 31,096 | 151,176 / 53,102 | 100,147 / 36,108 |
| `logmacro` | 86,494 / 31,095 | 89,513 / 32,289 | 100,147 / 36,110 |
| `nocond` | 77,540 / 28,354 | 142,331 / 50,726 | 91,108 / 33,388 |
| `logmacro,nocond` | 77,540 / 28,355 | 80,658 / 29,677 | 91,108 / 33,389 |
| `logmacro,nocond,trap` | 60,671 / 23,671 | 63,347 / 24,749 | 72,413 / 27,705 |

- `logmacro`: `log-*` become macros, so a literal control string is compiled instead of interpreted.
  `%log`'s `(apply #'format nil control args)` is what brings in the run-time `format` interpreter,
  as soon as a filter calls any `log-*`: `test_plugin` calls none, so it does not move.
- `nocond`: the callback's error log no longer prints the condition, and `%check` signals with the
  operation's name only.
- `trap`: no `handler-case` in the callbacks, and a failed host call traps.

## What the compiler costs (`probes/`)

One small export each, nothing else:

| Probe | raw | gzip |
| --- | ---: | ---: |
| `p00-empty` | 714 | 514 |
| `p01-memory-import-string` | 963 | 608 |
| `p04-clos` (two classes, a generic, `make-instance`) | 2,604 | 1,520 |
| `p05-handler-case` | 2,721 | 1,512 |
| `p09-search` | 3,655 | 1,981 |
| `p10-princ-to-string` | 5,075 | 2,200 |
| `p07-format-a` (`~a` of an `:s32` parameter) | 5,405 | 2,315 |
| `p03-hash` | 5,888 | 2,651 |
| `q3-format-clos-a` (`~a` of a value of unknown type, in a method) | 7,977 | - |
| `p11-concat` | 8,382 | 3,643 |
| `q11-handler-funcall` (`handler-case` around a `funcall`) | 10,829 | - |
| `p06-error-format` (`error` with arguments, `~a` of the condition) | 20,129 | 7,798 |
| `p08-format-apply` (`apply #'format`) | 76,527 | 28,728 |

Taking `http_body` apart from the `logmacro,nocond,trap` row, cumulatively:

| Removed | raw | gzip |
| --- | ---: | ---: |
| (start) | 60,671 | 23,671 |
| its one `format` with `~a` | 45,811 | 18,932 |
| its one `search` | 43,627 | 17,987 |
| the `funcall` in `%read-buffer` | 43,510 | 17,940 |
| the two hash tables, as alists | 41,316 | 16,722 |
| the SDK defuns this filter never calls (`dropdead.py`) | 22,232 | - |

The last row depends on the ones before it. Deleting the same defuns from the start row saves 427
bytes, and from the unchanged SDK 474: something else still reaches the same helpers.

What keeps them: `_dispatch_1` holds `CONCATENATE`, `STRING`, `VECTOR`, `IDENTITY` and `%SEQ-STRING`
as arms in a filter that names none of them first-class, and `STRING` reaches `FUNC_PRINC_TO_STR`,
then every printer. `q7-dead-hash-ref` shows one way in: an arm stays for a `#'string` in a function
nothing calls. `funcsizes.py` prints the arms and a call path to any function.

## What `--no-gc` gives (`no-gc/`)

`no-gc/http_body.lisp` and `no-gc/http_headers.lisp` are the same two filters written by hand on
`rontolisp:memory`: bytes in linear memory, no objects, no built strings. They pass the suite's one
test of these two filters, and answer the same `curl`s as the wasm-GC ones in Envoy v1.40.2.

| Filter | raw | gzip |
| --- | ---: | ---: |
| `http_body` | 2,101 | 1,109 |
| `http_headers` | 2,556 | 1,372 |

`no-gc/host.mjs` drives `http_headers` from Node: `node host.mjs http_headers.wasm /hello`.

## Reproducing

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar
A=.todo/artefacts/i51-proxy-wasm-filter-size

# a filter's numbers, its section table, and where the bytes went
cd examples/proxy-wasm/sdk-examples
java -Drontolisp.wasm.debug-func-sizes=1 -jar ../../../$JAR http_body.lisp -o /tmp/b.wasm \
  --no-wasi --optimize=size 2>/tmp/b.dump
wasm-tools objdump /tmp/b.wasm
python3 ../../../$A/funcsizes.py /tmp/b.wasm /tmp/b.dump %PC-WALK CONCATENATE

# an SDK variant
python3 $A/variants.py examples/proxy-wasm /tmp/v logmacro,nocond,trap

# the end-to-end check, in a real Envoy (needs Docker)
examples/proxy-wasm/sdk-examples/check.sh
```

`variants.py` and `dropdead.py` patch by exact text, and fail when the SDK has moved on.

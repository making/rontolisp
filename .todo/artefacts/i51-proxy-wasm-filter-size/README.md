# Size of a Proxy-Wasm filter, measured

The SDK levers below are applied, so `variants.py` and `dropdead.py` no longer patch the SDK. Current numbers:
`.kb/proxy-wasm-sdk.md`. What follows is the state i52 and i53 started from.

Everything here is `--no-wasi --optimize=size` unless it says otherwise, but for `test_plugin`, which writes
to stdout and is built without `--no-wasi` (its `_start` and two WASI imports are about 20 bytes more). gzip is `gzip -9 -c < file`.
Measured 2026-11-05 on `6f8a7628f`, except where a table says otherwise. Behaviour: each row marked "tested" ran through a 16-test plugin suite of a host
embedding a wasm interpreter (edits, refusals, traps, timeouts, memory limits, local responses,
body replacement).

## The filters as they stand

`examples/proxy-wasm/sdk-examples/`, tested:

| Filter | raw | gzip | code | data | `--optimize` raw / gzip |
| --- | ---: | ---: | ---: | ---: | ---: |
| `http_body` | 89,595 | 31,888 | 80,136 | 7,799 | 104,755 / 36,842 |
| `http_headers` | 154,268 | 53,759 | 121,176 | 31,228 | 190,536 / 64,074 |
| `test_plugin` | 103,131 | 36,964 | 91,836 | 9,123 | 124,093 / 43,823 |

The numbers to get under, on both columns:

| Filter | raw | gzip |
| --- | ---: | ---: |
| `http_body` | 92,163 | 25,968 |
| `http_headers` | 94,189 | 25,472 |
| `test_plugin` | 122,261 | 36,790 |

`wasm-opt -Oz --all-features` over the three: 78,269 / 137,342 / 89,680 raw. Context, not a target
(`.kb/size-measurement.md`).

## What the SDK costs (`variants.py`), all tested

| Levers | `http_body` raw / gzip | `http_headers` raw / gzip | `test_plugin` raw / gzip |
| --- | ---: | ---: | ---: |
| none | 89,595 / 31,888 | 154,268 / 53,759 | 103,131 / 36,964 |
| `logmacro` | 89,595 / 31,887 | 92,591 / 33,001 | 103,151 / 36,967 |
| `nocond` | 79,350 / 28,981 | 144,071 / 51,209 | 92,970 / 33,898 |
| `logmacro,nocond` | 79,350 / 28,981 | 82,361 / 30,154 | 92,970 / 33,897 |
| `logmacro,nocond,trap` | 62,404 / 24,250 | 65,055 / 25,172 | 74,119 / 28,205 |

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
| `p05-handler-case` | 2,753 | 1,538 |
| `p09-search` | 3,655 | 1,981 |
| `p10-princ-to-string` | 5,075 | 2,200 |
| `p07-format-a` (`~a` of an `:s32` parameter) | 5,405 | 2,315 |
| `p03-hash` | 5,888 | 2,651 |
| `q3-format-clos-a` (`~a` of a value of unknown type, in a method) | 8,029 | 3,426 |
| `p11-concat` | 8,382 | 3,643 |
| `q11-handler-funcall` (`handler-case` around a `funcall`) | 10,923 | 4,653 |
| `p06-error-format` (`error` with arguments, `~a` of the condition) | 20,292 | 7,903 |
| `p08-format-apply` (`apply #'format`) | 76,527 | 28,728 |

Taking `http_body` apart from the `logmacro,nocond,trap` row, cumulatively. This table is from just before
`45f0b386a`, which printed instance slots through the walk and added about 3KB to every filter, so its
start row is lower than the one above. It ranks causes, and it was not repeated:

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

`examples/proxy-wasm/no-gc/` is the SDK these led to, at about the same sizes (`.kb/proxy-wasm-no-gc.md`).

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

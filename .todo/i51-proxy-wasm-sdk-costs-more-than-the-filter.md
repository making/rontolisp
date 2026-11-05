# i51. The Proxy-Wasm SDK costs a filter far more than the filter costs

Difficulty: Low

`examples/proxy-wasm/proxy-wasm.lisp` makes three choices that each bring in a large part of the runtime, whatever the
filter does. The numbers are in `.todo/artefacts/i51-proxy-wasm-filter-size/README.md`; this is the part that changes
only the SDK.

Target, at `--no-wasi --optimize=size`, on both raw and gzip:

| Filter | Now raw / gzip | Target raw / gzip |
| --- | ---: | ---: |
| `sdk-examples/http_body` | 89,595 / 31,888 | below 92,163 / 25,968 |
| `sdk-examples/http_headers` | 154,268 / 53,759 | below 94,189 / 25,472 |
| `sdk-examples/test_plugin` | 103,131 / 36,964 | below 122,261 / 36,790 |

## The three levers, each measured and each passing a plugin test suite

1. **`log-*` as macros** (`logmacro`). `%log` calls `(apply #'format nil control args)`, which brings in the run-time
   `format` interpreter: 91 functions, about 30KB of code and 24KB of data. A literal control string compiled at the
   call site takes `http_headers` to 92,591. One `log-*` call anywhere is what it takes: `test_plugin` calls none, and
   one such call cost it 62KB when measured. The cost: `#'proxy-wasm:log-info` stops
   working. Decide whether that matters, or keep the function and teach the compiler the call-site fold
   (a `define-compiler-macro` here, if the wasm path honours one).
2. **The error log** (`nocond`). `(format nil "proxy-wasm: ~a" condition)` plus `error` with arguments costs about 10KB on
   every filter. The cost: the log loses the reason. Try first a message built without printing a condition, for example
   the operation's name and the status through something smaller than `~a`, and measure it. Not measured yet.
3. **A trap instead of `handler-case`** (`trap`). A further 17-19KB. Today `*trap-on-error*` is a run-time variable, so
   the handler stays in the module either way. It has to become a choice made at compile time. The default stays
   as it is: a trap takes the VM down, so this is for a filter that prefers failing the plugin. `http-call` has to
   answer a refused call as a value, because a trap cannot be handled.

All three together reach 62,404 / 24,250, 65,055 / 25,172 and 74,119 / 28,205, which meets the target on every number.
Without the third, gzip stays above it on `http_body` and `http_headers`.

## Also look at

- 12 `%define-callback` exports each carry the whole `%with-callback` expansion, 450 bytes with the handler. One shared
  function taking a closure, or dropping the export of a callback nobody specialized, is not measured. A host may require
  some exports; the optional ones are `on_tick`, `on_http_call_response` and the trailer callbacks.
- The two hash tables cost about 2KB raw. An alist is linear in the live streams, which is few per VM.

## Done when

The table above holds, `examples/proxy-wasm/check.sh` and `examples/proxy-wasm/sdk-examples/check.sh` pass, and the
README states what each choice costs a filter in behaviour.

# The Proxy-Wasm SDK: what a filter pays for

`examples/proxy-wasm/proxy-wasm.lisp`. Everything here is `--no-wasi --optimize=size`, raw / gzip, measured
2026-11-05 (`test_plugin` is built without `--no-wasi`). What a filter's author chooses is in the example's README; this is
why the SDK is shaped as it is, and what was weighed and left. The compiler's half is `.todo` i52.

| Filter | Before | Handled (default) | `:proxy-wasm-trap` |
| --- | ---: | ---: | ---: |
| `http_body` | 90,066 / 32,037 | 74,421 / 27,352 | 58,957 / 22,877 |
| `http_headers` | 154,818 / 53,565 | 77,321 / 28,353 | 61,394 / 23,771 |
| `test_plugin` | 103,795 / 37,035 | 87,800 / 32,201 | 70,824 / 27,004 |

**The error mode is read while the filter compiles.** `%trap-or-handle` is a macro over the macro-time `*features*`, not
a `#+`, because a filter's own push reaches a macro but not the read of a file it requires
(`reader-features.md`, "A source's own feature push"). `--feature proxy-wasm-trap` reaches it too. The source spelling
keeps `*features*` in the module, 439 bytes against the flag, because `eval-when` lowers to `progn` on every situation
(`declarations-type-checks.md`).

**A condition is never printed.** The reason for a failed host call is logged by `%check`, before it signals. Measured on
`http_body`, ahead of the bucket change below:

| Error log | raw | gzip |
| --- | ---: | ---: |
| fixed text, `error` with arguments kept | 79,788 | 29,088 |
| `~a` of the condition, as before | 90,066 | 32,037 |
| `simple-condition-format-control` of an `(error "~a..." ...)` | 94,378 | 32,446 |
| the same of a computed `(error text)` | 165,734 | 55,230 |
| a `define-condition` with a slot, `typep` in the handler | 90,367 | 32,188 |
| the same with a `:reader` | 90,498 | 32,251 |
| `format nil` of the name and status, in a global the handler reads | 79,843 | 29,143 |

A computed `error` datum bails `conditionNarrowing` (`error-handling.md`); a typed signal carries the report renderer. The
`format` with `~a` in `%check` adds about 90 bytes: the printer is in the module already (i52).

**`http-call` answers NIL when refused, in both modes**, because a trap cannot be handled. Before, a refused call under the
default made `gateway.lisp` answer its callback's fallback, `:continue`: the request passed without its authorization.

**Contexts are in 64 buckets of an alist, not a hash table.** 4,036 / 1,567 smaller on `http_body`; a plain alist saves
another 782 / 301, and walks every live stream per callback in a VM that may hold thousands.

**Left as they are:**

- One function holding the handler, each export passing a closure (`%guarded`): 64,833 / 24,677 against 62,993 / 24,444 under
  trap, 78,602 / 29,143 against 79,878 / 29,132 under a handler, before the buckets. It wins raw only under a handler.
- Leaving out `proxy_on_tick`, `proxy_on_http_call_response` and the trailer callbacks saves 1,420 / 424 under trap and 3,388 /
  716 under a handler on `http_body`. The SDK expands before the filter's methods are read, so it cannot know which are
  specialized; asking the filter to say so is more API than the bytes are worth.

## Tests
No test in the suite reaches these filters past a compile (`examples/examples.yaml`). `examples/proxy-wasm/check.sh` and
`sdk-examples/check.sh` run them in Envoy under Docker: `error_log.lisp` the default mode, `test_plugin.lisp` the trap.

# The Proxy-Wasm SDK: what a filter pays for

`examples/proxy-wasm/proxy-wasm.lisp`. Everything here is `--no-wasi --optimize=size`, raw / gzip, measured
2026-11-05 (`test_plugin` is built without `--no-wasi`). What a filter's author chooses is in the example's README; this is
why the SDK is shaped as it is, and what was weighed and left. A `funcall` no longer brings the sequence family or the printer
(`optimize-dead-code-elimination.md`, "What a `funcall` brought into a small module"), and that work is in the "wasm-GC" column below;
the times are from before it.

## What an exchange runs

`sluice-client` interprets a plugin with Endive. One exchange, by opcode from an `ExecutionListener`; the harness and how to
install it are in `.todo/artefacts/i69-proxy-wasm-instructions/`. Instruction counts do not move with load. A plugin that does
nothing is 13 instructions. "Before" is `7d24bc53b`.

| Filter | Rust | wasm-GC before | wasm-GC | `--no-gc` before | `--no-gc` |
| --- | ---: | ---: | ---: | ---: | ---: |
| `http_body`, 5-byte body: instructions | 2,342 | 13,967 | 1,116 | 475 | 491 |
| Same: function calls | 28 | 441 | 89 | 25 | 25 |
| `http_headers`: instructions | 28,124 | 118,699 | 18,408 | 10,997 | 10,997 |
| Same: function calls | 465 | 3,039 | 853 | 248 | 248 |
| `http_body`, 64KiB body: instructions | 462,207 | 3,227,624 | 222,976 | 5,636,321 | 221,979 |
| Same: function calls | 37 | 553 | 141 | 196,630 | 37 |
| `http_body`: bytes | 92,163 | 59,634 / 23,071 | 4,872 / 2,563 | 2,064 / 1,152 | 2,166 / 1,246 |
| `http_headers`: bytes | 94,189 | 61,382 / 23,762 | 7,114 / 3,433 | 2,583 / 1,388 | same |
| `gateway`: bytes | | 125,898 / 45,243 | 79,802 / 30,367 | | |

Warm p50 per exchange, load 14-30, against 445-487us for that plugin that does nothing: `http_body` Rust 621-723us, wasm-GC before
1000-1468us and now 715-733us, `--no-gc` 512-639us; `http_headers` Rust 1532us, wasm-GC now 1332us, `--no-gc` 1527us, one round.
Plugin start 221-241ms against 303-347ms before and Rust's 309-345ms. At the default limits (16MB, a 1s call) `http_body` on
wasm-GC now holds 4MiB whole in 366ms, where it lost 1MiB on the 1s call. Half Rust's instructions do not take half its time:
what a GC module's call costs Endive beside its instructions was not separated.

## Where the 13,967 went

`http_body` at `7d24bc53b`, by what the function was for. **CLOS was not the cause: dispatch was 2.5%.**

| What | Instructions | Share |
| --- | ---: | ---: |
| `_str_build`: every keyword, symbol and string evaluated was built, byte by byte, 34 of them | 6,104 | 44% |
| The context table: 8 `%lookup` at 221, `%store` 365, `%forget` 524 | 2,657 | 19% |
| Fixnums through the generic helpers: `(= status 0)` 87, `(+ cells 4)` 95, `(logand id 63)` 45 | 2,483 | 18% |
| `search`'s eight `&key` scans, the search, the copy into a string | 985 | 7% |
| `%mark` and `%reset`, 57 per callback | 456 | 3% |
| Generic functions (18-42 each), their methods, `make-instance` | 356 | 2.5% |

Where the keywords were: `(eq action :pause)` and the `:continue` a method answers on each callback, an `ecase` building each
key up to the one that matched, `make-instance`'s and `search`'s keyword scans, and `%check`'s operation name, a string evaluated
for a failure that never came. Under the default error mode `handler-case` adds about 22 instructions per callback.

## The API: one form, not methods

Measured on one tree, by keeping the old API over everything else (`http_body` with `with-buffer`):

| The filter's form | `http_body` | Calls | Bytes | `http_headers` | Bytes |
| --- | ---: | ---: | ---: | ---: | ---: |
| `defmethod`, the old SDK | 6,227 | 393 | 57,883 / 22,590 | 27,491 | 60,595 / 23,638 |
| `defmethod`, this SDK's host functions | 3,443 | 220 | 41,931 / 17,697 | 22,941 | 42,808 / 17,911 |
| `define-filter` | 1,145 | 91 | 6,086 / 3,165 | 19,936 | 20,311 / 8,702 |

So `defmethod` went: `define-filter` is a third of the instructions and a seventh of the module, and a filter loses nothing it
can't write itself. What the 2,298 were: an object and a table entry per stream (a third of them dispatch and default methods
on callbacks nobody specialized), since the SDK expands before the filter's methods are read and so has to export for all of them.
The form knows at expansion which clauses exist, so a callback left out is `(defun ... nil)`, no context exists unless asked for,
and a mistyped clause fails the compile where a mistyped `defmethod` made a new generic function, as do a clause given twice, a wrong
parameter count and an `:on-failure` that is neither mode. It is also the `--no-gc` SDK's form.

**A context is its id unless `:root` or `:stream` says otherwise.** Then it is an object of any kind in `%context`'s table, which
is what state costs: `(:stream (id root) (make-instance 's))` 2,294 instructions and 10,249 bytes, a `defstruct` 2,314, a cons
2,286, so the object's kind is not the cost. The table is: create 675, delete 434. A hit on the last context is 17 instructions; a
miss, every callback under interleaved streams, is 201 more. `:on-done`, `:on-log` and `:on-delete` get a root's context too (`gateway`'s `typep`).
A context goes outside `:on-delete`'s handler: inside it, as on the old SDK, a clause that signaled kept its context for good.

**Host functions are macros under a function.** `(get-header :request ":path")` has the number at compile time (`%define-enumerated`, a
wrong keyword fails the compile) and a literal name reaches `compileLiteralImportCall`; `(get-header map name)` over a variable calls the
function. `send-local-response`, `http-call`, `set-buffer` and `log-*` are macros outright, because a defun with `&key` puts
`%ll-check-keys` in the program, whose `%prin1-piece` is a prin1 surface, which puts `%print-cased` in every `~a` (`pretty-printer.md`). A
template in a defun counts the same way: a `(handler-case ...)` backquoted in `%guarded` as a defun kept the condition machinery in
a filter that traps, 141KB, which is why `%guarded` is a macro. Only `defmacro` bodies go unread.

**The out-cells are three for good** (`*cell*`), not `%alloc` per call: each call reads them before the next writes them, and the host's
`proxy_on_memory_allocate` does not touch them.

**A body stays in linear memory.** `with-buffer` and `search-bytes` (`"rontolisp:memory"."search"`, `wasm-import.md`) are 3.4
instructions per byte; `get-buffer` and `search` are 30, and a `get-buffer-octets` loop 155 (`string-search-runtime.md`).

**The error mode is a clause.** It used to be a `*features*` read by a macro, which kept `*features*` in the module.

**A condition is never printed.** The reason for a failed host call is logged by `%failed`, before it signals. Measured on
`http_body` at `--optimize=size`, on the old SDK:

| Error log | raw | gzip |
| --- | ---: | ---: |
| fixed text, `error` with arguments kept | 79,788 | 29,088 |
| `~a` of the condition, as before | 90,066 | 32,037 |
| `simple-condition-format-control` of an `(error "~a..." ...)` | 94,378 | 32,446 |
| the same of a computed `(error text)` | 165,734 | 55,230 |
| a `define-condition` with a slot, `typep` in the handler | 90,367 | 32,188 |
| the same with a `:reader` | 90,498 | 32,251 |
| `format nil` of the name and status, in a global the handler reads | 79,843 | 29,143 |

A computed `error` datum bails `conditionNarrowing` (`error-handling.md`); a typed signal carries the report renderer. On this SDK
a filter that traps is 4,872 / 2,563, one with the default handler 5,172 / 2,662, and one that catches `error` and logs `~a` of it
26,625 / 10,368: holding a condition is what brings the reports (`error-handling.md`, "A module with nowhere to report").

**`http-call` answers NIL when refused, in both modes**, because a trap cannot be handled. Before, a refused call under the
default made `gateway.lisp` answer its callback's fallback, `:continue`: the request passed without its authorization.

**Contexts are in 64 buckets of an alist, not a hash table.** Measured again at this tree, a hash table is 442 / 213 more on
`http_body` with `:stream`, where the fold keeps `_hash` to its integer arm, 812 / 276 on `gateway`, and 4,070 / 1,709 on `test_plugin` and
4,482 / 1,666 on `error_log`, where `_equal` stays. It runs 390 fewer of that exchange's 2,287 instructions (the bucket's `aref`, below) and
walks nothing. The bytes decide for now: a filter's author sees them in every build. A plain alist saves another 782 / 301, and walks every
live stream per callback in a VM that may hold thousands. `define-filter` makes a table only for a filter that uses it: a `defvar`
nothing reads goes, and its `(%table)` is a call that stays.

## What the compiler gained for it

None of these reads the SDK. Fuel on the first four: `WasmLispCompilerIntegrationTest.aLiteralIsOneObjectAndAPieceOfAStringIsNoByteLoop`.

- A literal is one object (`wasm-gc-strings.md`, "A literal is one object"): 13,967 to 8,068, and a smaller module.
- `eql` of a literal keyword, symbol or fixnum needs no object or call (`eq-numbers.md`).
- `_string_concat` over `array.copy`, and a string's `~a` is the string (`wasm-gc-strings.md`).
- `_str_from_mem` builds the array directly. It copied to linear scratch and then to the array: 19 + 16 instructions per byte, now 14.
- The i31 head at `--optimize=size`, and in `_rat_cmp_bits` at every level (`wasm-int-fusion.md`): `http_headers` 28,126 to 22,200.
- `_u32_val`, one function for a `:u32` argument, i31 first: 13 instructions against 33, and smaller wrappers (`wasm-import.md`).
- A literal in a `:u32` or `:octets` call, and a `format nil` at a text parameter (`wasm-import.md`).
- A call passing no keyword scans nothing (`lambda-lists.md`).
- A literal control without `~s` reaches no `prin1` (`pretty-printer.md`): a third of `http_body`.

Beside them, programs that are no filter, base and tree: `hello_world` 484 / 387 and 481 / 398; `pi_approx` 1,503 / 910 and 1,463 / 903;
`zlib` 179,246 / 59,909 and 176,728 / 58,985; `webgl-cube` 17,815 / 7,317 and 17,788 / 7,324; `dom_reactor`, the one that grew,
2,592 / 1,474 and 2,809 / 1,598 (`_str_lit`, and heads a module of integers used to fold away).

## Left as they are

- Each export wrapper boxes every parameter, used or not: 18 boxes and about 216 of `http_body`'s instructions. A wrapper could ask whether the
  defun mentions the parameter, for 6us of a 400us exchange.
- `%mark` and `%reset`, 49 per callback given: a let of a raw i32 would need unboxed locals at `--optimize=size`.
- `proxy_on_memory_allocate`, 98 per value the host answers: wrapper to defun to wrapper.
- The table's `aref`: `_arr_check_rank` 31 + `_arr_get` 50, and 90 in `_charvec_p` per store, for a general vector.
- A public `format nil` result held in a variable is a character vector, about 110 instructions per character (`string-write-runtime.md`).
  At a host parameter it is not (`wasm-import.md`).
- Leaving out `proxy_on_tick`, `proxy_on_http_call_response` and the trailer callbacks now happens by itself.
- One function holding the handler, each export passing a closure: 64,833 / 24,677 against 62,993 / 24,444 under trap, on the old SDK,
  and it wins raw only under a handler.

## Tests
No test in the suite reaches these filters past a compile (`examples/examples.yaml`). `examples/proxy-wasm/check.sh` and
`sdk-examples/check.sh` run them in Envoy under Docker: `error_log.lisp` the default mode and a failing `:on-delete`, `test_plugin.lisp` the trap. Sluice's
`ProxyWasmFilterTest`, 25 tests, passes with `http_body`, `http_headers` and `test_plugin` of each SDK in the place of its Rust ones, and
its `lisp_plugin.lisp` on `define-filter`.

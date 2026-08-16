# 411. `unread-char` has no handle arm, and the stream-direction predicates do not dispatch

Difficulty: Medium

Left behind by `.todo/400`, which completed the Gray INPUT protocol dispatch.
Two gaps, both recorded in `.kb/gray-streams.md`'s Limits.

## 1. `unread-char` on a stream HANDLE signals

`(unread-char c s)` works on a Gray instance -- it reaches
`rontolisp:stream-unread-char`, whose default parks the character in the
protocol's one-slot pushback. On a stream HANDLE (a file, a string input
stream, a socket) it signals
`UNREAD-CHAR is supported only on a Gray input stream`, identically on all four
backends (`LispMacroExpander.UNREAD_CHAR_ONLY_GRAY_MESSAGE`, shared with
`Environment`'s interpreter definition).

The reason is that no backend keeps a pushback a handle-based read would drain:

- interpreter -- the stream table holds `BufferedReader`s; `%peek-char` is a
  `mark(2)`/`reset()` around the read, not a cell.
- JVM -- the same `mark`/`reset` shape in `JvmIoRuntimeBuilder.buildPeekChar`.
- WASM -- `_peek_char` DOES have one (`PEEK_FD_ADDR`/`PEEK_CP_ADDR`, drained by
  `_read_char`), so this backend is nearly free.

Closing it means one per-handle cell that `read-char`, `%peek-char`,
`read-line`, `read-sequence` and `read` all consult, in hand-written JVM
bytecode and WASM as well as in `Environment` -- and the four backends must
agree about what happens after a read that is NOT `read-char` (CL says
undefined; answer with a signal, not a silently wrong position).

Callers that want it: `cl-json`'s decoder, `local-time`'s parser, and chunga's
`unread-char*` (which is on dexador's path, though chunga's own stream is a
Gray one and already works).

cl-json's decoder makes this a hard blocker for jose (`.todo/419`): without
`unread-char` it cannot scan a number, `true`, `false`, `null` or a nested
aggregate, so `jose:decode` refuses every JWT carrying an integer `exp` / `iat`
/ `nbf` -- i.e. every real one. Handing the same decoder a Gray input stream
instead decodes all of those correctly, which measures the gap exactly.

## 2. `input-stream-p` / `output-stream-p` answer `nil` for a Gray instance

On every backend. Everything else in the input family dispatches now, so a
library asserting `(input-stream-p s)` on a Gray stream it was just handed is
the shape that still trips. The Gray answer needs a direction predicate per
base class -- two internal generics and four methods, spliced into every
program that uses the protocol -- which is why todo-400 left it out rather than
pay that in every Gray program for a query no consumer had asked for. If a
consumer appears, weigh that against a `typep` in a `SPLICE_ON_USE` helper
(what `%gray-stream-element-type-dispatch` does).

## Definition of done

`unread-char` round-trips on a file stream, a string input stream and a socket
on all four backends, with the same answer after each of `read-char` /
`peek-char` and the same signal after a `read-line`; `input-stream-p` /
`output-stream-p` answer `t` for the matching Gray base class. Pinned like the
rest of the family (`LispEvaluatorTest` / `JvmLispCompilerTest` /
`WasmLispCompilerIntegrationTest` + a `ci-spec.yaml` case), with
`.kb/gray-streams.md`'s Limits updated.

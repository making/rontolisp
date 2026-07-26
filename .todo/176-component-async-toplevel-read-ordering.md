# Component async top level: promoted socket reads in one call's arguments evaluate out of order

Found 2026-07-26 while tracing the cl-postgres component leg (todo 115). Both
findings are about the `--component` ASYNC TOP LEVEL only -- code inside plain
defuns (the entire cl-postgres driver) takes the synchronous `%io-*` dispatch
and is unaffected.

## 1. Multi-read argument order reverses

At the top level (an async context), each socket read promotes to
`(rontolisp:await ...)` (`WasmSocketsRewrite`). When SEVERAL promoted reads sit
in ONE call's arguments, the awaits execute in the WRONG order:

```lisp
;; wire bytes: 52 00 00 00 08 00 00 00 00  ('R', len 8, code 0)
(print (list :auth (cl-postgres::read-uint1 sock)
             (cl-postgres::read-uint4 sock)
             (cl-postgres::read-uint4 sock)))
;; component printed (:AUTH 0 134217728 1375731712) -- the THIRD argument's
;; reads consumed the first wire bytes (0x52000000), the first argument the
;; last byte. The same reads in separate top-level forms are ordered correctly.
```

Suspect: `WasmAwaitNormalizer`'s hoist of multiple awaits out of one strict
call's argument list binds them in reverse. Fix should come with a pinning
test of two `(read-byte)`s in one `(list ...)` at the top level against a
loopback socket.

## 2. `fd_write` after interleaved socket awaits crashes with "unknown handle index"

Later in the same trace (a top-level `do` loop alternating socket reads and
`print`s), a `print` died INSIDE the adapter:

```
!await_waitable / !future_read_cli / !fd_write ... unknown handle index 541999444
```

The stdout write future handle the adapter waits on was garbage after many
prints interleaved with promoted socket reads in one top-level state machine.
Possibly the same root cause as (1) (the state machine resuming with clobbered
locals), possibly an adapter handle-table issue. Reproduce with the todo-115
scratch trace (`pg-trace2.lisp` shape: loop of read-uint1/read-uint4 +
per-iteration prints against live postgres).

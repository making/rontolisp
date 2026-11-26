# rontolisp:*fetch-handler* — dynamically scoped interception of outgoing HTTP

There is no way to intercept `rontolisp:fetch` for testing, caching, or
middleware: unit-testing code that fetches requires a live server (the WASM
integration tests spin real containers for exactly this reason), and
cross-cutting concerns (auth headers, retries, request logging) have no
seam.

## Proposal

A new special variable `rontolisp:*fetch-handler*`, default `nil` = real
transport. When bound to a function, `fetch` calls

```lisp
(funcall handler request-plist next)
```

instead of the transport, where `request-plist` is the existing
`HttpPlistShape` request shape and `next` is a function performing the
underlying send (the real transport, or the handler that was bound before —
so handlers layer naturally by closing over the outer value). The handler
returns a response plist. Sugar macro:

```lisp
(rontolisp:with-fetch-handler ((req next) body-of-handler...)
  forms...)
; = (let ((rontolisp:*fetch-handler* (lambda (req next) ...))) forms...)
```

Uses: mock responses in tests (no network), a caching layer that delegates
via `next`, request/response logging, header injection. This is dependency
injection for host I/O using the dynamic-extent scoping the language
already has — shallow-binding specials on every backend except `--no-gc`
(where fetch is unsupported anyway, so the error story is consistent for
free).

## Mechanism

The interception point should be the Lisp surface so the logic is written
once:

- **`--component`**: fetch already IS Lisp (`http.lisp` `%fetch`) — a
  one-cell check of the special before `%fetch-send`.
- **Interpreter/JVM**: either consult the global cell from the Java fetch
  entry (`HttpSupport` / the JVM runtime builder) before transport, or —
  cleaner — split the user-visible `rontolisp:fetch` into a small spliced
  Lisp wrapper over a `%fetch-raw` builtin, so the handler logic is one
  shared file across backends (the `http.lisp` pattern applied to the
  non-component backends).

Async note: on the component backend fetch is awaited inside an async body;
the handler runs in that same task, and a handler that itself calls `next`
must therefore be legal in async context (it is plain Lisp — no new rule
needed, but add a test).

Related: `.todo/149` (default streams through specials) uses the same
substrate; neither depends on the other.

## Verification

- Test: a bound handler returns a canned response plist with no network on
  all backends that have fetch; a layering test (logging handler wrapping a
  mock handler via `next`); unbound = byte-identical behavior to today.
- Docs: fetch reference page (en+ja) gains the variable + macro with a mock
  example; note it in the http-handler guide's testing section.

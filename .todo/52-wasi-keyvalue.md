# wasi:keyvalue — host-backed persistent key-value store

Status: PLANNED (do AFTER `.todo/51-wasi-http-incoming-handler-spin.md`).
Created 2026-07-04.

## Goal

Expose a persistent key-value store to rontolisp over **`wasi:keyvalue`** on the
WASM component backend, pairing with the in-memory `examples/kv-server.lisp`
(which today only keeps state in a process-local hash table). Natural fit with
the existing hash-table API and the kv-server demo.

## Proposed surface

Opaque bucket handles (like the stream-table handles used by sockets/files):

```lisp
(let ((db (rontolisp:kv-open "cache")))       ; open/create a named bucket
  (rontolisp:kv-set db "greeting" "hello")     ; value: string (or bytes)
  (rontolisp:kv-get db "greeting")             ; => "hello", or nil if absent
  (rontolisp:kv-exists db "greeting")          ; => t / nil
  (rontolisp:kv-delete db "greeting")
  (close db))                                  ; buckets live in the handle space
```

## wasi:keyvalue shape (0.2.0-draft — pin the exact version once wired)

- `store.open(identifier: string) -> result<bucket, error>`
- `bucket.get(key: string) -> result<option<list<u8>>, error>`
- `bucket.set(key: string, value: list<u8>) -> result<_, error>`
- `bucket.delete(key: string) -> result<_, error>`
- `bucket.exists(key: string) -> result<bool, error>`
- (optional later: `list-keys`, `atomics` increment, `batch` get/set)

Values are `list<u8>`; represent as rontolisp strings (reuse the string<->bytes
marshalling the http/socket code already has). WASI 0.2 world — coexists with
fetch + wasi:http (0.2), NOT with the 0.3 sockets component (same mixing rule
`WasmExprCompiler` enforces for fetch+tcp).

## Backend strategy (decide early)

- **WASM component**: the real target — import `wasi:keyvalue`, bucket = an
  imported resource wrapped in a handle. Run under a host that provides a kv
  backend (`wasmtime` with `-S keyvalue`? check the flag/availability;
  Spin provides `spin-keyvalue`). VERIFY the wasmtime host support level first —
  keyvalue may only be hosted by Spin/other runtimes, not bare wasmtime. If bare
  wasmtime does not host it, target Spin (ties in with `.todo/51`).
- **Interpreter / JVM**: no host kv — back it with either (a) an in-memory
  `HashMap` per bucket name (simple, matches kv-server's current semantics but
  non-persistent), or (b) a small file-backed store under a temp/dir. Pick (a)
  first for parity, note the non-persistence; (b) if persistence is wanted
  cross-run. Follow the `SocketSupport`/`TlsPemSupport` pattern (shared support
  class, web-profile `@Substitute` that signals unsupported).

## Where it plugs in

- `.kb/hash-tables.md` — the value model / equality helpers to reuse.
- `.kb/wasi-component.md` — importing another WASI 0.2 interface into the
  component (the fetch http import is the closest existing precedent).
- `.kb/fetch-http.md` — string<->linear-memory + option/result marshalling to
  mirror.
- Registration checklist: LispNames + PackageRegistry externals +
  PackageIntrospection (updates the `list-functions` expected output in 9 spots
  — see how tls-listen-pem was threaded through) + Environment (interpreter) +
  Jvm/Wasm compiler cases + docs (en/ja reference pages + catalog + guide) +
  `.kb`.

## Verification

Four-backend + native E2E workflow. Demo: a `kv-server`-style example (or
extend it) that persists across connections/restarts via `kv-*`; header carries
the `spin up` / wasmtime run command. Docs mirrored en/ja.

Related: `.todo/51-wasi-http-incoming-handler-spin.md`. Both from the 2026-07
"interesting wasmtime components" discussion; #51 (wasi:http incoming) is the
higher-priority headline, this is the follow-on.

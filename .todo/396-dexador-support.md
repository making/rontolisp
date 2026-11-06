# 396. dexador support (parent)

Difficulty: Medium

Parent item for making [dexador](https://github.com/fukamachi/dexador) loadable
and usable on rontolisp. A 2026-08-16 spike drove the unpatched system against a
local echo server; all child gaps found then are DONE (397 unwind-protect
secondary values, 398 babel mappings, 401 asdf defsystem-depends-on/version,
402 CL leftovers, 403 export/resolver, 404 uiop:symbol-call, 399 cl+ssl shim
over `rontolisp:tls-upgrade`, 400 Gray input protocol).

## State

With the spike shims, dexador RUNS on interpreter, JVM and WASM `--component`:
get/post/headers/UTF-8/gzip/redirect/404 condition/cookie-jar all OK; `https://`
OK on those three. Done 2026-11-06 (`.todo/048`): the component resolves host names
(`-S allow-ip-name-lookup=y`); unpatched dexador answered `http://example.com/` and
`https://example.com/` there, and an unresolvable host signals `usocket:socket-error`.
The run flags for a component dexador program are now `-S tcp=y -S inherit-network=y
-S tls=y -S allow-ip-name-lookup=y`. Re-probe rows that predate
later fixes: `:want-stream t` (400 now done) and the secondary values (397 now
done).

No open children. Done 2026-11-06 (`.todo/405`): the Preview 1 leg. WASI
Preview 1 has no client socket API, so a Preview 1 module reaches sockets only
under the `--native` runner (`.kb/tcp-sockets.md`, "--native: the runner's
sockets"); that is the leg, and unpatched dexador's get/post (status, headers,
a UTF-8 form body, an alist body, a pooled second request) prints there what it
prints on the interpreter, the JVM and the component. A plain `.wasm` under
`wasmtime run` compiles and refuses at the first socket call, naming
`--native`. What it took: the client tls pair is a call-time error on Preview 1
(it was a compile error, so every dexador program was refused there); a native
`listen` asks the kernel, so a `:want-stream` body's drain empties the pooled
connection; `usocket:wait-for-input` has one shape on every backend. On the way:
since 2026-11-05 no dexador program compiled on the JVM (the eval'd-setf dispatch
over its 451 setf functions was one method past 64 KB); it is segmented now.
`https://` from an executable signals (no TLS there, `.kb/tcp-sockets.md`, "Not
supported"), as the definition of done allows. For the coverage still to add:
neither `ExamplesE2eTest` nor ci-spec has a `--native` leg -- `NativeTcpTest` is
where an executable runs a socket program.

Done 2026-11-06 (`.todo/415`): the component's read deadline is real, so
dexador's DEFAULT `:read-timeout 10` runs there unpatched -- two keep-alive
requests over one pooled connection (the `listen` drain between them),
`https://1.1.1.1/`, and a stalled origin under `:read-timeout 1` failing with
`read-byte: Read timed out` as on the interpreter. No `:read-timeout nil`
workaround is left to carry.

Operational note: trivial-mimes needs `/etc/mime.types` at LOAD time, so the
component needs `--dir /etc` (a native executable preopens `/` and reads it).
Decide at close: document it, or give trivial-mimes a built-in fallback table.

## Deferred alternative: a shim over `rontolisp:fetch`

Decision (2026-08-16): not now -- reach the UNPATCHED load first, then
re-evaluate. dexador is ordinary portable CL (every dep but cl+ssl loads
unpatched; cl+ssl is exactly the shim side of the `.todo/147` line, and 399 is
that shim). `fetch` delegates redirect/decompression/chunking/header policy to
three host clients; real dexador does it all in Lisp and was verified
byte-identical on three backends. What the shim route WOULD buy, for
re-evaluation: `https://` for free, and the no-socket targets (Cloudflare
Worker reactor, browser playground). If adopted, it must NOT claim the
`dexador` name -- opt-in system refusing to load beside the real one
(`ShimLibraries.CONFLICTS`, tiny-routes/lite precedent).

## Definition of done

`(ql:quickload "dexador")` loads the UNPATCHED upstream system, and a program
doing `(dex:get url)` / `(dex:post url :content ...)` -- reading status and
headers from the secondary values -- runs on all four backends over `http://`
and, on interpreter/JVM/`--component`, over `https://`. Then: an
`examples/net/` program, `ci-spec.yaml` coverage, a `doc/{en,ja}` page under
the library guides, and a `.kb/` file for whatever invariant the cl+ssl shim
ends up owning.

## Non-goal

winhttp (`#+windows`) and dexador's `:proxy`/SOCKS5 paths. Re-scope if a
consumer appears.

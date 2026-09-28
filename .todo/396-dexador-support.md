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
OK on those three (IP-literal URLs until `.todo/048`). Preview 1 does not run it
(405 below). Re-probe rows that predate later fixes: `:want-stream t` (400 now
done) and the secondary values (397 now done).

Open children:

1. `.todo/405` -- WASM Preview 1 has no non-blocking input probe, so `listen`
   is a compile error; a dexador program now COMPILES to Preview 1 and refuses
   at run time.
2. On the component, the `usocket:socket-option` deadline primitive SIGNALS
   (todo-114), so dexador's default `:read-timeout 10` fails at connect until
   `.todo/415`; pass `:read-timeout nil` meanwhile.

Operational note: trivial-mimes needs `/etc/mime.types` at LOAD time, so the
component needs `--dir /etc`. Decide at close: document it, or give
trivial-mimes a built-in fallback table.

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

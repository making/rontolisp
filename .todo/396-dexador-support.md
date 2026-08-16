# 396. dexador support (parent)

Difficulty: Medium

Parent item for making [dexador](https://github.com/fukamachi/dexador) --
the de-facto CL HTTP client -- loadable and usable on rontolisp. The children
are the gaps a spike found (2026-08-16); this file holds the picture, the
verified baseline and the ordering. Each child is independently useful and
none of them is dexador-only.

## What the spike did

`(ql:quickload "dexador")` fetched `dexador-20260101-git` (0.9.15) into the
quicklisp cache; the system was copied to a scratch tree, patched form by form
until it loaded, and driven against a local echo server. The patch list IS the
gap list below. Deps were probed one by one first: **fast-http, quri, fast-io,
babel, trivial-gray-streams, trivial-garbage, chunga, cl-ppcre, cl-cookie,
trivial-mimes, chipz, cl-base64, usocket, bordeaux-threads, alexandria all
load today, unpatched**. Only `cl+ssl` does not (it is CFFI-based).

## The baseline the spike reached

With the workarounds applied, dexador RUNS on **three of the four backends** --
interpreter, JVM (`-o X.class`), and WASM `--component`:

| case | result |
| --- | --- |
| `dex:get` | OK |
| `:headers` on the request | OK |
| UTF-8 body | OK |
| gzip (`Content-Encoding`) via chipz | OK |
| 302 redirect follow | OK |
| 404 -> `dex:http-request-failed` | OK (signals) |
| `dex:post` string / alist / JSON | OK |
| `:cookie-jar` + `Set-Cookie` | OK |
| `:want-stream t` | BROKEN -- `.todo/400` |
| `https://` | BROKEN -- `.todo/399` |
| 2nd..5th return values (status, headers, uri) | BROKEN -- `.todo/397` |

WASM Preview 1 does not compile at all (`.todo/405`).

Operational note for the component run: trivial-mimes reads `/etc/mime.types`
at LOAD time and signals `No MIME.TYPES file found anywhere!` without it, so
the component needs `--dir /etc` (`wasmtime run -W gc=y -W exceptions=y
-S inherit-network=y -S allow-ip-name-lookup=y --dir /etc`). Decide when
closing this parent whether that is documented or whether trivial-mimes gets a
built-in fallback table.

## The children

Blockers, in the order that unblocks the most:

1. `.todo/397` -- `unwind-protect` cleanup clobbers the protected form's
   secondary values, on ALL FOUR backends. This is the one that makes
   `(dex:get url)` answer only the body: dexador returns
   `(values body status headers uri stream)` through an `unwind-protect` whose
   cleanup ends in `(values)`. Independent of dexador, and the most serious
   finding of the spike.
2. `.todo/398` -- the babel/babel-encodings shim needs the decoding-MAPPING
   protocol (`unicode-char`, `*string-vector-mappings*`, `lookup-mapping`,
   `code-point-counter`, `decoder`, ...). Without it dexador does not even
   load: `src/decoding-stream.lisp` imports those names.
3. `.todo/401` -- ASDF: `:defsystem-depends-on` (dexador.asd's first line),
   `asdf:component-version` (dexador's User-Agent), and
   `asdf:system-relative-pathname` on the compile paths (trivial-mimes).
4. `.todo/402` -- the CL leftovers: `file-namestring`, the `nstring-*` case
   family (chunga), and the environment-enquiry family.
5. `.todo/404` -- `uiop:symbol-call` has no compiler case, so dexador's
   backend dispatch cannot be compiled.
6. `.todo/403` -- a run-time `(export 'pkg::name pkg)` does not publish a
   function under the `pkg:name` spelling. Not on dexador's own path (found
   while building the spike shims), but it breaks the common
   `defun`-then-`export` idiom.

Then the feature work:

7. `.todo/399` -- `cl+ssl` shim over a new TLS-over-an-existing-stream
   primitive. Without it `https://` -- i.e. most real dexador use -- is dead.
8. `.todo/400` -- the Gray INPUT protocol is defined but no built-in
   dispatches to it, so `:want-stream t` is unusable (and fails DIFFERENTLY on
   each backend, which is its own reason to fix it).
9. `.todo/405` -- WASM Preview 1 has no non-blocking input probe, so `listen`
   is a compile error and dexador cannot target it.

`usocket:socket-option` (dexador sets `:receive-timeout` with it) is a usocket
shim gap and is tracked in `.todo/114`, not here.

## Definition of done

`(ql:quickload "dexador")` loads the UNPATCHED upstream system, and a program
doing `(dex:get url)` / `(dex:post url :content ...)` -- reading the status and
the header table from the secondary values -- runs on all four backends over
`http://` and, on interpreter and JVM, over `https://`. Then: an
`examples/net/` program, `ci-spec.yaml` coverage, a `doc/{en,ja}` page under
the library guides, and a `.kb/` file for whatever invariant the cl+ssl shim
ends up owning.

## Non-goal

winhttp (`#+windows`), and dexador's `:proxy`/SOCKS5 paths. Neither was
exercised; re-scope if a consumer appears.

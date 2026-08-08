# tiny-routes: make the routing library loadable and runnable on Clack

Difficulty: Medium

[tiny-routes](https://github.com/jeko2000/tiny-routes) 0.1.1 (dist
`tiny-routes-20241012-git`) is a small Clack-targeting routing library: request /
response plists, `define-get` / `define-post` / `define-routes`, a middleware
combinator set (`wrap-request-body`, `wrap-query-parameters`, the response
wrappers) and a `:id`-style path-template matcher over cl-ppcre. It is the piece
missing between `clack:clackup` (already ours, `.kb/clack.md`) and an actual
application with routes -- today `(ql:quickload "tiny-routes")` dies on the third
source file.

A spike (2026-08-08) established that the library is **three small gaps away from
working on all four backends**. The spike loaded the real upstream sources
verbatim except for three hand-patches, and everything else -- `uiop:define-package`
with `:use-reexport` and `:local-nicknames`, `deftype` + `satisfies`,
`check-type`, `typecase` over `null`/`cons`/`function`/`vector`/`pathname`,
`(typep x '(integer 1))`, `destructuring-bind` with `&key &allow-other-keys`,
`nth-value`, `decode-universal-time`, `~2,'0d` / `~@[` / `~:[` format directives,
`read-sequence`/`write-sequence` over the Clack `:raw-body` stream, and the whole
cl-ppcre matcher path -- already works untouched.

## The three gaps (each has its own item)

1. **`.todo/292`** -- the LOOP anaphoric `it` is unbound in any package other than
   `CL-USER`. `tiny:routes`, the function every application goes through, is
   `(loop for handler in handlers when (funcall handler request) return it)`
   inside `(in-package :tiny-routes)`.
2. **`.todo/293`** -- `uiop:if-let` and `uiop:with-deprecation` are missing.
   `tiny-routes.lisp` imports `if-let`; `response.lisp` wraps its six deprecated
   constructors in `with-deprecation`, which is the LOAD-time failure.

Nothing else was needed. With the three patched in the spike, this ran on the
interpreter, the JVM, WASM Preview 1 and the WASM `--component`:

```lisp
(ql:quickload "clack")
(ql:quickload "tiny-routes")

(defpackage :demo (:use :cl :tiny-routes))
(in-package :demo)

(define-routes *app*
  (define-get "/hello" () (ok "hello world"))
  (define-get "/users/:id" (req) (ok (format nil "user ~A" (path-parameter req :id))))
  (define-post "/echo" (req) (ok (format nil "echo:~A" (request-body req))))
  (define-any "*" () (not-found "nope")))

(clack:clackup (pipe *app* (wrap-request-body) (wrap-query-parameters))
               :server :rontolisp :port 5599 :use-thread nil)
```

(`:use-thread t` on the interpreter and the JVM if the script must go on running;
the component build ignores it -- no `:thread-support` there.)

```console
$ curl -s localhost:5599/hello                    # interpreter, JVM
hello world
$ curl -s localhost:5599/users/42
user 42
$ curl -s -XPOST -d abc localhost:5599/echo
echo:abc
$ curl -s -o /dev/null -w '%{http_code}\n' localhost:5599/zzz
404

$ rontolisp app.lisp -o serve-comp.wasm --component     # same four requests
$ wasmtime serve -W gc=y -W exceptions=y -S cli=y -S tcp=y -S inherit-network=y \
    --addr 127.0.0.1:5600 serve-comp.wasm
```

Preview 1 has no incoming TCP, so there `clackup` signals at call time exactly as
`.kb/clack.md` documents; the ROUTING half (calling the handler with a
hand-built request plist) runs there, and the spike pinned that separately.

## The cookie subsystem is free

`tiny-routes-middleware-cookie` (a second `.asd` in the same dist, depending on
`cl-cookie`) needed **no** patches at all in the spike: `(ql:quickload
"cl-cookie")` already succeeds, `alexandria:mappend` comes with it,
`ppcre:do-register-groups` and `:local-nicknames` are ours, and
`parse-cookie-header` / `write-set-cookie-header` / `wrap-request-cookies` /
`wrap-response-cookies` all answered correctly. Cover it in the same pass rather
than leaving a second item behind.

## The test system is NOT in scope

`tiny-routes/test` depends on fiveam, which stops before its own sources:

```console
$ echo '(ql:quickload "fiveam")' > f.lisp && rontolisp f.lisp
ASDF:DEFSYSTEM net.didierverna.asdf-flv: unsupported option :LONG-NAME
  (supported: :name :description :long-description :version :author
   :maintainer :license :depends-on :serial :components :pathname
   :rontolisp-features)
```

That is a defsystem-metadata gap in a fiveam DEPENDENCY, and how much of fiveam
follows is unmeasured. Do not fold it in -- verify with our own E2E test instead,
the way every other library entry is verified.

## Work

- Land `.todo/292` and `.todo/293` first; they are the only source-level blockers.
- Verify `(ql:quickload "tiny-routes")` unpatched, then the application above, on
  all four backends (Preview 1 = routing only, per above). The compile paths must
  be driven through `ql:quickload` in the program, not through `load` of the dist
  files -- that is the path `LoadInliner` splices.
- Add `TinyRoutesE2eTest` over `AsdfLibraryE2eSupport`, covering: the route
  macros and `define-routes` dispatch, `path-parameter` binding, the method
  matcher, the regex path template, `wrap-request-body` over the Clack
  `:raw-body` stream, `wrap-query-parameters`, the response wrappers, and the
  cookie middleware.
- `examples/asdf/tiny-routes-demo.lisp` + its row in `examples/asdf/README.md`.
  That README's contract is "runs identically on ALL FOUR backends", so the demo
  must be routing over hand-built request plists -- no `clackup`. The serving leg
  belongs with `ClackE2eTest` / the Clack guide, which already carry the
  three-backend caveat.
- A row in the `doc/{en,ja}/guides/asdf-systems.md` "What can I actually load?"
  table (both languages, same file set) naming the version, the backends, and the
  Preview-1 caveat.
- Consider a `doc/{en,ja}/guides/clack.md` pointer -- the guide teaches the
  application protocol and the handler backends but never shows how to get from
  one handler function to a set of routes, which is the first thing a reader
  wants after `clackup`.

## Done when

- `(ql:quickload "tiny-routes")` and `(ql:quickload "tiny-routes-middleware-cookie")`
  load the UNPATCHED upstream sources on all four backends.
- The four requests above answer identically on the interpreter, the JVM and the
  `--component` build, pinned by `TinyRoutesE2eTest`.
- The example, the README row and both guide tables are in.

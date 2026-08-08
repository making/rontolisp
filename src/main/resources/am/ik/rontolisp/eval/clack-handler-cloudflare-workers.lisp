;; clack.handler.cloudflare-workers: the Clack handler backend that is a
;; HOST-DRIVEN REACTOR on EVERY backend -- run stores the application and
;; returns, and the host (a Cloudflare Worker's src/index.js, a browser page, a
;; node or JVM embedding, or a check script on the interpreter) calls DISPATCH
;; with a JSON request string and gets a JSON response string back. Satisfies
;; the built-in ASDF system "clack-handler-cloudflare-workers" (and its dotted
;; alias "clack.handler.cloudflare-workers", the spelling lack's
;; find-package-or-load derives from the package name). Like
;; clack-handler-rontolisp the package is NOT seeded in PackageRegistry, so
;; this shim carries its own defpackage (the leaf-module pattern).
;;
;; It is driven by CLACKUP like every other handler backend:
;;
;;   (ql:quickload "clack-handler-cloudflare-workers")
;;   (clack:clackup #'app :server :cloudflare-workers :use-thread nil)
;;
;; and APP is an ordinary Clack application -- the environment plist in, the
;; (status headers body) list out -- unchanged from the one that runs on
;; hunchentoot, on woo, under `wasmtime serve` or on the JVM.
;;
;; Since :server :rontolisp became reactor-aware (its run takes this same
;; reactor shape under #+rontolisp-reactor, i.e. --no-wasi / --no-gc), a
;; Worker no longer NEEDS this designator -- one :rontolisp source covers
;; every host. What this backend still says is "host-driven EVERYWHERE": on
;; the interpreter and the JVM :rontolisp binds a real socket, while this
;; backend stores the app there too, which is what lets a Worker be developed
;; and driven through `dispatch` on the interpreter, where the edit/run loop
;; costs nothing. It also keeps the ecosystem-conventional per-host name a
;; Clack user looks for.
;;
;; ALL of the machinery is the shared rontolisp::%http-reactor-* transport
;; (http-reactor.lisp): the one application store, the JSON envelope (its
;; shape is documented THERE), the %http-make-env / %http-normalize-response
;; ride, the handler-case that answers 500 rather than trapping, and -- on the
;; WASM backends -- the (rontolisp::%http-reactor ...) marker the compiler
;; (eval/HttpReactorInliner) answers with the synthesized handle-request
;; wasm-export. handle and dispatch below are thin public names over it, so
;; this backend and :rontolisp's reactor leg cannot drift, and a program that
;; mixes the two designators still stores ONE application.
;;
;; :use-thread nil is not decoration on the interpreter and the JVM: both have
;; the :thread-support feature, so clackup defaults :use-thread to T and runs
;; RUN -- i.e. the app store -- on another thread, which races the very next
;; form. On the WASM backends the default is already nil (single-threaded by
;; construction, no :thread-support), so a Worker source may omit it.

(defpackage :clack.handler.cloudflare-workers
  (:use :cl)
  (:export :handle :dispatch :run :stop))

(defun clack.handler.cloudflare-workers:handle (app request-json)
  "Run the Clack application APP against the JSON request REQUEST-JSON and
answer the JSON response. The envelope is documented in http-reactor.lisp."
  (rontolisp::%http-reactor-handle app request-json))

(defun clack.handler.cloudflare-workers:dispatch (request-json)
  "Run the application CLACKUP stored against the JSON request REQUEST-JSON and
answer the JSON response. The host's entry point: on the WASM backends the
synthesized wasm-export calls this, on every other backend the host calls it
directly."
  (rontolisp::%http-reactor-dispatch request-json))

;; clackup's protocol. A reactor owns no socket, so run starts nothing and stop
;; has nothing to stop; what run does own is the app store and -- on the WASM
;; backends only -- the compile-time marker the export is synthesized from. The
;; marker names the SHARED dispatcher, the same one :rontolisp's reactor leg
;; names, so whichever shim's marker the compiler reads first the module is
;; identical.
(defun clack.handler.cloudflare-workers:run (app &rest ignored)
  (declare (ignore ignored))
  (rontolisp::%http-reactor-register app)
  #+rontolisp-wasm
  (rontolisp::%http-reactor 'rontolisp::%http-reactor-dispatch
                            "handle-request")
  nil)

(defun clack.handler.cloudflare-workers:stop (server)
  (declare (ignore server))
  nil)

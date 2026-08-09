;; clack.handler.rontolisp: the rontolisp handler backend for Clack, satisfying
;; the built-in ASDF system "clack-handler-rontolisp" (and its dotted alias
;; "clack.handler.rontolisp" -- the system name lack's find-package-or-load
;; derives from the package name). (clack:clackup app :server :rontolisp)
;; resolves here: clack's find-handler probes (find-package
;; "CLACK.HANDLER.RONTOLISP"), loads the system on a miss, then applies the
;; interned RUN. The package is therefore NOT seeded in PackageRegistry -- a
;; pre-seeded package would short-circuit the load and leave run undefined --
;; so this shim carries the defpackage itself (the leaf-module pattern).
;;
;; :rontolisp means "serve on THIS target's native inbound transport", and the
;; transport is chosen at COMPILE time by the reader features -- which is what
;; lets ONE clackup source run unchanged on every host:
;;
;; - Interpreter / JVM (#-rontolisp-wasm): run starts a STOPPABLE server via
;;   the internal rontolisp::%http-server-* seam and then BLOCKS on the join
;;   (the clack-handler-hunchentoot shape): with clackup's default
;;   :use-thread t the acceptor thread stays alive until clack:stop
;;   destroy-threads it, at which point the interrupted join returns and the
;;   unwind-protect stops that one server.
;; - WASI WASM (#+rontolisp-wasm, without the reactor feature): run stores the
;;   app and delegates to the rontolisp:http-handler directive, which requires
;;   a LITERAL quoted defun name -- hence the one named %app indirection.
;;   Under --component the host owns the socket (wasmtime serve; HttpLibrary
;;   widens its directive detection into this defun), so run returns at once,
;;   :use-thread must stay nil (the backends are single-threaded -- clackup's
;;   default IS nil there, no :thread-support feature) and stop is
;;   meaningless; on Preview 1 the directive is a call-time error by design
;;   (no incoming TCP).
;; - Reactor WASM (#+rontolisp-reactor: --no-wasi, or --no-gc which implies
;;   it): the host CALLS the module instead of handing it a socket, so run
;;   stores the app in the shared reactor store (http-reactor.lisp) and leaves
;;   the (rontolisp::%http-reactor ...) marker that eval/HttpReactorInliner
;;   answers with the synthesized handle-request wasm-export -- the same
;;   store, dispatcher and JSON envelope the explicit clack-handler-reactor
;;   backend uses, so the two cannot drift.
;;
;; There is NO bridge here for the socket legs, and that is the point: since
;; the rontolisp:http-handler cutover, rontolisp's own server protocol IS
;; Clack's (the environment plist in, the (status headers [body]) response
;; out, built and normalized once in http-server.lisp for every backend), so
;; the Clack application is handed to the server AS the handler and no
;; per-request data conversion happens at all.
;;
;; :raw-body :buffered is the one thing the shim asks for: rontolisp's native
;; default hands a handler the request body as an ASYNCHRONOUS stream, which is
;; what a rontolisp program wants (nothing is buffered, the component streams
;; it lazily), while Clack's :raw-body is a SYNCHRONOUS stream a middleware
;; reads with read-line / read-byte / file-position. See .kb/http-server.md.
;; The reactor leg gets the same buffered body from the envelope's
;; already-read "body" string (http-reactor.lisp).
;;
;; Compatibility notes:
;; - ONE clack server per process: the compiled backends dispatch every request
;;   through one handler slot, so a second concurrent clackup replaces the
;;   first one's app.
;; - :remote-addr / :remote-port carry the real peer on the interpreter and the
;;   JVM and are nil on the WASI component (wasi:http@0.3.0 exposes no peer
;;   address at all) and on a reactor (unless the host sends "remote-addr").
;; - A response body may be a list of strings, nil, an (unsigned-byte 8) vector
;;   or a rontolisp stream; a BARE STRING and a pathname are refused, and of the
;;   function-response protocol only the DELAYED form is supported. All of that
;;   is http-server.lisp's contract now, identical on every backend.

(defpackage :clack.handler.rontolisp (:use :cl) (:export :run :stop))

(defvar clack.handler.rontolisp::*app* nil)

;; The WASI wasm leg only: rontolisp:http-handler takes a literal quoted name.
#+(and rontolisp-wasm (not rontolisp-reactor))
(defun clack.handler.rontolisp::%app (env)
  (funcall clack.handler.rontolisp::*app* env))

#-rontolisp-wasm
(defun clack.handler.rontolisp:run
    (app &key (port 5000) (address "127.0.0.1") debug &allow-other-keys)
  (declare (ignore debug))
  (setf clack.handler.rontolisp::*app* app)
  (let ((server
         (rontolisp::%http-server-start app port address :raw-body :buffered)))
    (unwind-protect (progn
                      (rontolisp::%http-server-join server)
                      server)
      (rontolisp::%http-server-stop server))))

#-rontolisp-wasm
(defun clack.handler.rontolisp:stop (server)
  (rontolisp::%http-server-stop server)
  t)

#+(and rontolisp-wasm (not rontolisp-reactor))
(defun clack.handler.rontolisp:run
    (app &key (port 5000) (address "127.0.0.1") debug &allow-other-keys)
  (declare (ignore debug address))
  (setf clack.handler.rontolisp::*app* app)
  (rontolisp:http-handler 'clack.handler.rontolisp::%app port
                          :raw-body :buffered))

;; The reactor leg: nothing to bind, nothing to block on. The marker is
;; compile-time data (HttpReactorInliner lowers it to nil and appends the
;; handle-request export over the shared dispatcher), so run just stores the
;; app and returns.
#+rontolisp-reactor
(defun clack.handler.rontolisp:run
    (app &key (port 5000) (address "127.0.0.1") debug &allow-other-keys)
  (declare (ignore port address debug))
  (rontolisp::%http-reactor-register app)
  (rontolisp::%http-reactor 'rontolisp::%http-reactor-dispatch
                            "handle-request"))

#+rontolisp-wasm
(defun clack.handler.rontolisp:stop (server)
  (declare (ignore server))
  nil)

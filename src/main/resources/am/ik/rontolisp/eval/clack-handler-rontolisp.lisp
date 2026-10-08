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
;; lets ONE clackup source run unchanged on every host. The transport legs
;; (a stoppable socket server on the interpreter and the JVM, the Servlet
;; container under -o app.war, the rontolisp:http-handler directive under
;; --component, the host-driven reactor under --no-wasi / --no-gc) are NOT
;; here: they are rontolisp::%http-serve (http-serve.lisp), written once for
;; this shim and the Clojure front end's ring.adapter.rontolisp, so the two
;; adapters cannot drift. run blocks on the socket leg (the
;; clack-handler-hunchentoot shape: with clackup's default :use-thread t the
;; acceptor thread stays alive until clack:stop destroy-threads it, at which
;; point the interrupted join returns and the unwind stops that one server)
;; and returns at once everywhere else.
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
;; The reactor leg asks the shared transport for it by REGISTERING the mode
;; with the app, which is what makes the reactor's own default (rontolisp's
;; asynchronous stream, the http-handler directive's default) reachable at all
;; (http-reactor.lisp).
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

(defun clack.handler.rontolisp:run
    (app &key (port 5000) (address "127.0.0.1") debug &allow-other-keys)
  (declare (ignore debug))
  (rontolisp::%http-serve app port address t))

(defun clack.handler.rontolisp:stop (server)
  (rontolisp::%http-serve-stop server))

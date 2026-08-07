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
;; There is NO bridge here any more, and that is the point: since the
;; rontolisp:http-handler cutover, rontolisp's own server protocol IS Clack's
;; (the environment plist in, the (status headers [body]) response out, built
;; and normalized once in http-server.lisp for every backend), so the Clack
;; application is handed to the server AS the handler and no per-request data
;; conversion happens at all.
;;
;; :raw-body :buffered is the one thing the shim asks for: rontolisp's native
;; default hands a handler the request body as an ASYNCHRONOUS stream, which is
;; what a rontolisp program wants (nothing is buffered, the component streams
;; it lazily), while Clack's :raw-body is a SYNCHRONOUS stream a middleware
;; reads with read-line / read-byte / file-position. See .kb/http-server.md.
;;
;; - On the interpreter and the JVM backend run starts a STOPPABLE server via
;;   the internal rontolisp::%http-server-* seam and then BLOCKS on the join
;;   (the clack-handler-hunchentoot shape): with clackup's default
;;   :use-thread t the acceptor thread stays alive until clack:stop
;;   destroy-threads it, at which point the interrupted join returns and the
;;   unwind-protect stops that one server.
;; - On the WASM backends (#+rontolisp-wasm) it stores the app and delegates to
;;   the rontolisp:http-handler directive, which requires a LITERAL quoted
;;   defun name -- hence the one named %app indirection there. Under
;;   --component the host owns the socket (wasmtime serve; HttpLibrary widens
;;   its directive detection into this defun), so run returns at once,
;;   :use-thread must stay nil (the backends are single-threaded -- clackup's
;;   default IS nil there, no :thread-support feature) and stop is meaningless;
;;   on Preview 1 the directive is a call-time error by design (no incoming
;;   TCP).
;;
;; Compatibility notes:
;; - ONE clack server per process: the compiled backends dispatch every request
;;   through one handler slot, so a second concurrent clackup replaces the
;;   first one's app.
;; - :remote-addr / :remote-port carry the real peer on the interpreter and the
;;   JVM and are nil on the WASI component (wasi:http@0.3.0 exposes no peer
;;   address at all).
;; - A response body may be a list of strings, nil, an (unsigned-byte 8) vector
;;   or a rontolisp stream; a BARE STRING and a pathname are refused, and of the
;;   function-response protocol only the DELAYED form is supported. All of that
;;   is http-server.lisp's contract now, identical on every backend.

(defpackage :clack.handler.rontolisp (:use :cl) (:export :run :stop))

(defvar clack.handler.rontolisp::*app* nil)

;; The wasm leg only: rontolisp:http-handler takes a literal quoted name.
#+rontolisp-wasm
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

#+rontolisp-wasm
(defun clack.handler.rontolisp:run
    (app &key (port 5000) (address "127.0.0.1") debug &allow-other-keys)
  (declare (ignore debug address))
  (setf clack.handler.rontolisp::*app* app)
  (rontolisp:http-handler 'clack.handler.rontolisp::%app port
                          :raw-body :buffered))

#+rontolisp-wasm
(defun clack.handler.rontolisp:stop (server)
  (declare (ignore server))
  nil)

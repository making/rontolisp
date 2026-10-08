;;;; http-serve.lisp -- "serve this application on THIS target's native inbound
;;;; transport", written once for every adapter that offers it: the
;;;; clack-handler-rontolisp shim's run/stop (clack:clackup :server :rontolisp)
;;;; and the Clojure front end's ring.adapter.rontolisp/run-server both call
;;;; %http-serve, so the transport legs below exist in exactly one copy.
;;;;
;;;; APP is a Clack application: the environment plist in, the
;;;; (status headers [body]) response out (http-server.lisp). Every leg asks for
;;;; :raw-body :buffered -- the SYNCHRONOUS bivalent body stream a Clack
;;;; middleware and a Ring handler both read with read-line / read-byte /
;;;; slurp, never the asynchronous stream rontolisp's own default hands over.
;;;;
;;;; This file is read with the TARGET's reader features (HttpServeLibrary), so
;;;; each target compiles only its own leg:
;;;;
;;;; - Interpreter / JVM (#-(or rontolisp-wasm rontolisp-servlet)): a STOPPABLE
;;;;   server over the internal rontolisp::%http-server-* seam. With JOIN the
;;;;   call blocks on the join (clackup's run, a Ring :join? true) and stops the
;;;;   server in an unwind -- clack:stop destroy-threads the acceptor, the
;;;;   interrupted join returns normally and the cleanup runs; without JOIN it
;;;;   answers the handle at once and %http-serve-stop stops it.
;;;; - Servlet war (#+rontolisp-servlet): the container owns the port and the
;;;;   war's top level must RETURN, so the seam registers the application and
;;;;   answers a dead handle (in war mode %http-server-start binds nothing).
;;;; - WASI WASM (#+rontolisp-wasm without the reactor feature): the
;;;;   rontolisp:http-handler directive, which needs a LITERAL quoted defun
;;;;   name -- hence the one stored application behind %http-serve-app.
;;;;   HttpLibrary finds the directive nested in this defun and exports
;;;;   %http-serve-app as the wasi:http handler; under --component the host
;;;;   owns the socket and the call returns at once, on Preview 1 the directive
;;;;   is its call-time "requires --component" error.
;;;; - Reactor WASM (#+rontolisp-reactor: --no-wasi, --no-gc): the host CALLS
;;;;   the module, so the application goes to the shared reactor store and the
;;;;   %http-reactor marker makes HttpReactorInliner synthesize the
;;;;   handle-request export (http-reactor.lisp).
;;;;
;;;; PORT and ADDRESS matter only to the socket leg ("" or nil binds every
;;;; interface); JOIN only there too, since no other leg has anything to wait
;;;; for. ONE served application per process: the compiled backends dispatch
;;;; every request through one handler slot.

#+(and rontolisp-wasm (not rontolisp-reactor))
(defvar rontolisp::%http-serve-current nil)

;; The literal name the directive takes on the WASI leg.
#+(and rontolisp-wasm (not rontolisp-reactor))
(defun rontolisp::%http-serve-app (env)
  (funcall rontolisp::%http-serve-current env))

#-(or rontolisp-wasm rontolisp-servlet)
(defun rontolisp::%http-serve (app port address join)
  (let ((server
         (rontolisp::%http-server-start app port address :raw-body :buffered)))
    (if join
        (unwind-protect (progn
                          (rontolisp::%http-server-join server)
                          server)
          (rontolisp::%http-server-stop server))
        server)))

#-(or rontolisp-wasm rontolisp-servlet)
(defun rontolisp::%http-serve-stop (server)
  (rontolisp::%http-server-stop server)
  t)

#+rontolisp-servlet
(defun rontolisp::%http-serve (app port address join)
  (declare (ignore port address join))
  (rontolisp::%http-server-start app 0 nil :raw-body :buffered))

#+(and rontolisp-wasm (not rontolisp-reactor))
(defun rontolisp::%http-serve (app port address join)
  (declare (ignore address join))
  (setq rontolisp::%http-serve-current app)
  (rontolisp:http-handler 'rontolisp::%http-serve-app port :raw-body :buffered))

;; The marker is compile-time data: HttpReactorInliner lowers it to nil and
;; appends the handle-request export over the shared dispatcher.
#+rontolisp-reactor
(defun rontolisp::%http-serve (app port address join)
  (declare (ignore port address join))
  (rontolisp::%http-reactor-register app :buffered)
  (rontolisp::%http-reactor 'rontolisp::%http-reactor-dispatch
                            "handle-request"))

;; Undeploying the war, or the host, is what stops the other legs.
#+(or rontolisp-wasm rontolisp-servlet)
(defun rontolisp::%http-serve-stop (server)
  (declare (ignore server))
  nil)

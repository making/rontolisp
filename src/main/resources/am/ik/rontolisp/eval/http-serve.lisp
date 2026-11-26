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
;;;; - WASI WASM (#+rontolisp-wasm, neither a reactor nor a --native module): the
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
;;;; - A --native executable (#+rontolisp-native): the runner around the
;;;;   Preview 1 module owns the socket and HTTP/1.1 -- it accepts and reads
;;;;   each request on threads of its own (rontolisp-native/runner/src/http/
;;;;   serve.rs) -- and the module takes the requests one at a time through
;;;;   the rlhttp imports below, answering each through the reactor transport
;;;;   (%http-reactor-answer: the JSON envelope, the body pulled in and handed
;;;;   out through the imports) and a failure as the socket legs do
;;;;   (%http-failed: a plain 500, the report on standard error). The serving loop is
;;;;   the module's own, so the call never returns, whatever JOIN says: the
;;;;   module has no second thread to go on with.
;;;;
;;;; PORT and ADDRESS matter only to the socket legs ("" or nil binds every
;;;; interface); JOIN only to the interpreter's and the JVM's, since no other
;;;; leg can go on while it serves. ONE served application per process: the
;;;; compiled backends dispatch every request through one handler slot.
;;;;
;;;; An adapter whose program goes on after it started a server, and ends when
;;;; the server has stopped (the Java subset's com.sun.net.httpserver), calls
;;;; the same legs in two steps: %http-serve-begin binds and answers the server
;;;; at once on EVERY leg, and %http-serve-run, called where the program has
;;;; nothing else to do, answers once the server has stopped. On the socket
;;;; legs run is the join; a --native module, which has one thread, serves in
;;;; it -- the runner has been accepting since begin and holds what arrived --
;;;; until %http-serve-stop was called; where the host calls the module run
;;;; answers at once. %http-serve-port is the port a socket leg or the runner
;;;; bound, nil where the leg cannot tell.
;;;;
;;;; An adapter with a scheduler of its own (the Java subset's threads on one
;;;; stack) serves in steps instead of run: %http-serve-step SERVER LIMIT
;;;; serves one request of a --native module's, waiting LIMIT milliseconds at
;;;; most for it to arrive (nil: for ever) and answering whether it served one;
;;;; where the host calls the module it answers :host at once -- there is
;;;; nothing to take, the adapter returns to its host.

#+(and rontolisp-wasm (not rontolisp-reactor) (not rontolisp-native))
(defvar rontolisp::%http-serve-current nil)

;; The literal name the directive takes on the WASI leg.
#+(and rontolisp-wasm (not rontolisp-reactor) (not rontolisp-native))
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
(defun rontolisp::%http-serve-stop (server &optional drain)
  ;; DRAIN: the caller has answered every request it took, and the exchanges
  ;; still open are responses being written, which end before the stop
  (rontolisp::%http-server-stop server drain)
  t)

;; The application of the two-step serve answers each exchange itself, from any
;; thread and at any time, as a JDK's HttpExchange is answered: its delayed function
;; is handed the exchange's reply (%http-server-take) in place of a responder, and the
;; transport writes nothing of what it returns -- the reply's end writes the response,
;; on the thread that ends it. So the thread the transport called on goes on at once.
#-(or rontolisp-wasm rontolisp-servlet)
(defun rontolisp::%http-serve-begin (app port address)
  (rontolisp::%http-server-start (lambda (env)
                                   (let ((answer (funcall app env)))
                                     (if (functionp answer)
                                         (progn
                                           (funcall answer
                                            (rontolisp::%http-server-take))
                                           (list 200 nil))
                                         answer))) port address
                                 :raw-body :buffered))

#-(or rontolisp-wasm rontolisp-servlet)
(defun rontolisp::%http-serve-run (server)
  (rontolisp::%http-server-join server)
  nil)

#-(or rontolisp-wasm rontolisp-servlet)
(defun rontolisp::%http-serve-port (server)
  (rontolisp::%http-server-port server))

;; Where the host owns the port -- the war's container, wasmtime serve, the host
;; of a reactor -- begin is the serve that returns, and there is nothing to wait
;; for or to tell.
#+(or rontolisp-servlet (and rontolisp-wasm (not rontolisp-native)))
(defun rontolisp::%http-serve-begin (app port address)
  (rontolisp::%http-serve app port address nil)
  t)

#+(or rontolisp-servlet (and rontolisp-wasm (not rontolisp-native)))
(defun rontolisp::%http-serve-run (server)
  (declare (ignore server))
  nil)

#+(or rontolisp-servlet (and rontolisp-wasm (not rontolisp-native)))
(defun rontolisp::%http-serve-port (server)
  (declare (ignore server))
  nil)

#+(or rontolisp-servlet (and rontolisp-wasm (not rontolisp-native)))
(defun rontolisp::%http-serve-step (server limit)
  (declare (ignore server limit))
  :host)

#+rontolisp-servlet
(defun rontolisp::%http-serve (app port address join)
  (declare (ignore port address join))
  (rontolisp::%http-server-start app 0 nil :raw-body :buffered))

#+(and rontolisp-wasm (not rontolisp-reactor) (not rontolisp-native))
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

;; The runner's server (rlhttp, beside fetch's imports): serve binds and starts
;; accepting, or answers -1 and leaves the reason for serveError; nextRequest
;; blocks until a request has arrived whole and answers its exchange, which the
;; runner releases when the collector finds the handle dead (answering 500 if
;; nothing was answered); awaitRequest waits so many milliseconds at most and
;; answers 1 when a request has arrived, which the next nextRequest answers;
;; port is the port bound; respond sends the response the head and the body
;; handed over before it make up.
#+rontolisp-native
(rontolisp:wasm-import 'rontolisp::%http-serve-listen
                       :from "rlhttp"
                       :as "serve"
                       :params '(:string :s32)
                       :returns :s32)
#+rontolisp-native
(rontolisp:wasm-import 'rontolisp::%http-serve-error
                       :from "rlhttp"
                       :as "serveError"
                       :params '()
                       :returns :string)
#+rontolisp-native
(rontolisp:wasm-import 'rontolisp::%http-serve-next
                       :from "rlhttp"
                       :as "nextRequest"
                       :params '(:s32)
                       :returns :extern)
#+rontolisp-native
(rontolisp:wasm-import 'rontolisp::%http-serve-await
                       :from "rlhttp"
                       :as "awaitRequest"
                       :params '(:s32 :s32)
                       :returns :s32)
#+rontolisp-native
(rontolisp:wasm-import 'rontolisp::%http-serve-bound
                       :from "rlhttp"
                       :as "port"
                       :params '(:s32)
                       :returns :s32)
#+rontolisp-native
(rontolisp:wasm-import 'rontolisp::%http-serve-head
                       :from "rlhttp"
                       :as "requestHead"
                       :params '(:extern)
                       :returns :string)
#+rontolisp-native
(rontolisp:wasm-import 'rontolisp::%http-serve-read-body
                       :from "rlhttp"
                       :as "readRequestBody"
                       :params '(:extern)
                       :returns :bytes)
#+rontolisp-native
(rontolisp:wasm-import 'rontolisp::%http-serve-write-body
                       :from "rlhttp"
                       :as "writeResponseBody"
                       :params '(:extern :bytes)
                       :returns :void)
#+rontolisp-native
(rontolisp:wasm-import 'rontolisp::%http-serve-respond
                       :from "rlhttp"
                       :as "respond"
                       :params '(:extern :string)
                       :returns :void)

;; One request: the reactor transport over the exchange, its body pulled through
;; the reactor's one receive buffer (as the :raw-body BUFFERED asks for) and handed
;; out chunk by chunk as octets. A failure's head carries its body in band, which
;; replaces whatever chunks crossed before it. An application whose delayed response
;; answers a future goes on answering after its function returned: the exchange is
;; answered once that future settles, from the stack of whoever settled it, and the
;; module takes other requests meanwhile -- the runner holds the exchange until then.
#+rontolisp-native
(defun rontolisp::%http-serve-exchange (app exchange buffered)
  (let* ((source
          (lambda ()
            (let ((buf (rontolisp::%http-reactor-buffer 65536)))
              (rontolisp::%http-reactor-chunk buf
               (rontolisp::%http-serve-read-body exchange buf)))))
         (sink
          (lambda (chunk)
            (rontolisp::%http-serve-write-body exchange
             (rontolisp::%http-reactor-octets chunk))))
         (head
          (handler-case (let* ((req
                                (rontolisp:json-parse
                                 (rontolisp::%http-serve-head exchange)))
                               (answer
                                (rontolisp::%http-reactor-force
                                 (funcall app
                                  (rontolisp::%http-make-env
                                   (rontolisp::%http-reactor-request-tuple req
                                    source buffered))))))
                          (if (functionp answer)
                              (let* ((state
                                      (rontolisp::%http-delayed-begin answer
                                                                      nil))
                                     (pending (aref state 4)))
                                (if (and (rontolisp:futurep pending)
                                         (not
                                          (rontolisp::%future-settled-p
                                           pending)))
                                    (progn
                                      (rontolisp::%http-serve-later exchange
                                                                    state
                                                                    pending
                                                                    sink)
                                      nil)
                                    (progn
                                      (rontolisp::%http-reactor-force pending)
                                      (rontolisp::%http-reactor-head
                                       (rontolisp::%http-delayed-end state)
                                       sink))))
                              (rontolisp::%http-reactor-head
                               (rontolisp::%http-normalize-response answer)
                               sink)))
            (error (e) (rontolisp::%http-serve-failure e)))))
    (if head (rontolisp::%http-serve-respond exchange head))))

;; The head of an application that failed before its response went out: the socket
;; legs' 500, its body in band.
#+rontolisp-native
(defun rontolisp::%http-serve-failure (condition)
  (let ((failed (rontolisp::%http-failed condition)))
    (rontolisp::%http-reactor-envelope (car failed) (car (cdr failed))
                                       (car (cdr (cdr failed))) nil)))

;; The answer of an exchange whose delayed response answered the future PENDING: once
;; it settles, the response the function's responder kept, or its failure.
#+rontolisp-native
(rontolisp:async-defun rontolisp::%http-serve-later
    (exchange state pending sink)
  (let* ((failure nil)
         (settled
          (handler-case (rontolisp:await pending)
            (error (e)
              (setq failure e)
              nil))))
    (declare (ignorable settled))
    (rontolisp::%http-serve-respond exchange
                                    (if failure
                                        (rontolisp::%http-serve-failure failure)
                                        (handler-case (rontolisp::%http-reactor-head
                                                       (rontolisp::%http-delayed-end
                                                        state) sink)
                                          (error (e)
                                            (rontolisp::%http-serve-failure
                                             e)))))))

;; RAW-BODY is the adapters' :buffered unless the rontolisp:http-handler directive
;; asked for its own default, :stream (HttpReactorInliner.lowerHttpHandlerForRunner
;; lowers the directive to this call).
#+rontolisp-native
(defun rontolisp::%http-serve
    (app port address join &optional (raw-body :buffered))
  (declare (ignore join))
  (let ((server (rontolisp::%http-serve-listen (or address "") port))
        (buffered (eq raw-body :buffered)))
    (when (< server 0)
      (error
       (concatenate 'string "http server: " (rontolisp::%http-serve-error))))
    (loop
      (rontolisp::%http-serve-exchange app (rontolisp::%http-serve-next server)
                                       buffered))))

;; The two-step serve of a --native module: the server is #(listener application
;; stopped). The runner accepts from the listen on and queues each request that
;; arrived whole, so nothing is lost between begin and run.
#+rontolisp-native
(defun rontolisp::%http-serve-begin (app port address)
  (let ((server (rontolisp::%http-serve-listen (or address "") port)))
    (when (< server 0)
      (error
       (concatenate 'string "http server: " (rontolisp::%http-serve-error))))
    (vector server app nil)))

#+rontolisp-native
(defun rontolisp::%http-serve-run (server)
  (loop
    (if (aref server 2) (return nil))
    (rontolisp::%http-serve-exchange (aref server 1)
     (rontolisp::%http-serve-next (aref server 0)) t)))

#+rontolisp-native
(defun rontolisp::%http-serve-step (server limit)
  (if (or (null limit)
          (= 1 (rontolisp::%http-serve-await (aref server 0) limit)))
      (progn
        (rontolisp::%http-serve-exchange (aref server 1)
         (rontolisp::%http-serve-next (aref server 0)) t)
        t)
      nil))

#+rontolisp-native
(defun rontolisp::%http-serve-port (server)
  (rontolisp::%http-serve-bound (aref server 0)))

;; The runner's number of the server: a wait of the runner's for sockets
;; (runner-sockets.lisp's %sock-wait) can end at its next request too.
#+rontolisp-native
(defun rontolisp::%http-serve-number (server) (aref server 0))

;; Where the host waits for a reactor (--host-timer) the calls it has in hand wait
;; until something answers them, so a stop that does not drain cuts them, as a socket
;; leg's closes the exchanges in hand: through the function an adapter keeping such
;; calls sets here (java.lisp's).
#+rontolisp-host-timer (defvar rontolisp::%http-serve-cutter nil)

;; Undeploying the war, or the host, is what stops the other legs; a --native
;; server stops with its process, or -- one begun in two steps -- where its run
;; has answered the request in hand.
#+(or rontolisp-wasm rontolisp-servlet)
(defun rontolisp::%http-serve-stop (server &optional drain)
  #+rontolisp-native (declare (ignore drain))
  #-(or rontolisp-native rontolisp-host-timer) (declare (ignore server drain))
  #+rontolisp-host-timer (declare (ignore server))
  #+rontolisp-native (if (vectorp server) (setf (aref server 2) t))
  #+rontolisp-host-timer
  (if (and (not drain) rontolisp::%http-serve-cutter)
      (funcall rontolisp::%http-serve-cutter))
  nil)

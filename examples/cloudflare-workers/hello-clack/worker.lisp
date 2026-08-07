;;; worker.lisp -- a Clack application on Cloudflare Workers, whole.
;;;
;;; Three forms: load clack and the handler backend, define the application,
;;; clackup. There is no Worker-specific code in it at all -- `app` is an
;;; ordinary Clack application (the environment plist in, the (status headers
;;; body) list out), so the same function runs on hunchentoot, on woo, under
;;; `wasmtime serve` and on the JVM, unchanged.
;;;
;;; What makes it a Worker is the :server designator. :cloudflare-workers is a
;;; built-in handler backend for a host that CALLS you instead of handing you a
;;; socket, so its `run` binds nothing -- it stores the application, and the
;;; compiler synthesizes the exported entry point src/index.js calls
;;; (handle-request: a JSON request string in, a JSON response string out).
;;; Nothing here declares that export, because rontolisp:wasm-export needs a
;;; literal name at compile time and a clackup call has none to give.
;;;
;;; The two keywords are what this host is, not boilerplate:
;;;   :use-thread nil             -- already the default on WASM; on the
;;;                                  interpreter and the JVM it stops clackup
;;;                                  from storing the application on a thread
;;;                                  the next form would race.
;;;   :use-default-middlewares nil -- lack's backtrace middleware prints to
;;;                                  *error-output*, which a reactor does not
;;;                                  have.
;;;
;;; ../hello is the other end of the same spectrum: three exported functions,
;;; no clack, 563 bytes. This one is 1.6 MB because clack and lack are in it --
;;; see the README.

(ql:quickload '("clack" "clack-handler-cloudflare-workers"))

(defun app (env)
  (list 200 '(:content-type "text/plain; charset=utf-8")
        (list
         (format nil "Hello from Clack on Cloudflare Workers!~%~a ~a~%"
                 (getf env :request-method) (getf env :path-info)))))

(clack:clackup #'app
               :server :cloudflare-workers
               :use-thread nil
               :use-default-middlewares nil)

;;; worker.lisp -- the Cloudflare half: everything app.lisp deliberately is not.
;;;
;;; app.lisp is the Clack application, verbatim from
;;; ../../net/httpbin-clack.lisp; its last form there is
;;;
;;;   (clack:clackup #'app :server :rontolisp :port 8080 :use-thread nil)
;;;
;;; and THIS FILE is what replaces that one form. A Worker hands over a request
;;; JavaScript has already parsed rather than a socket, so there is no server to
;;; run: the program exports ONE function,
;;;
;;;   handle-request : JSON request string -> JSON response string
;;;
;;; and src/index.js -- which does have a real `Request` -- calls it.
;;;
;;; The bridge between the two is not written here. clack-handler-cloudflare-workers is
;;; a built-in handler backend, the sibling of the clack-handler-rontolisp one
;;; that clackup uses when it DOES own a socket, and its `handle` is the whole
;;; adapter: it builds the Clack environment from the JSON envelope, runs the
;;; application, normalizes the Clack response back, and answers 500 rather than
;;; trapping if the application signals. It converts nothing itself -- rontolisp's
;;; server protocol IS Clack's, so it rides the same backend-free
;;; rontolisp::%http-make-env / %http-normalize-response entry points every other
;;; transport meets in. The envelope both sides speak is documented there.
;;;
;;; Nothing here does I/O, which is what lets build.sh compile it with --no-wasi:
;;; the module imports NOTHING, so the Worker instantiates it with an empty
;;; import object and needs no WASI shim. The flip side is that adding a `print`
;;; or a `random` here traps at run time -- see the README.

(ql:quickload "clack-handler-cloudflare-workers")

(load "app.lisp")

(rontolisp:wasm-export 'handle-request :params '(:string) :returns :string)

(defun handle-request (request-json)
  (clack.handler.cloudflare-workers:handle #'app request-json))

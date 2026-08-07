;;; worker.lisp -- the Cloudflare half: everything app.lisp deliberately is not.
;;;
;;; app.lisp is the Clack application, verbatim from
;;; ../../net/httpbin-clack.lisp; its last form there is
;;;
;;;   (clack:clackup #'app :server :rontolisp :port 8080 :use-thread nil)
;;;
;;; and THIS FILE is what replaces that one form -- with another clackup call.
;;; That symmetry is the whole point: a Worker is not a different KIND of
;;; program, it is the same application behind a different handler backend, and
;;; the source says so in the ordinary Clack way.
;;;
;;; A Worker hands over a request JavaScript has already parsed rather than a
;;; socket, so :cloudflare-workers is a handler backend with nothing to bind.
;;; Its `run` stores the application and returns; what the host calls instead of
;;; connecting is one exported function,
;;;
;;;   handle-request : JSON request string -> JSON response string
;;;
;;; which src/index.js -- which does have a real `Request` -- calls. Nothing
;;; here declares that export: rontolisp:wasm-export needs a literal name at
;;; compile time, so the compiler synthesizes the export (and the bridge to the
;;; handler backend's `dispatch`) from a marker the backend's `run` carries.
;;; See doc/en/guides/clack.md.
;;;
;;; The bridge itself is not written here either. clack-handler-cloudflare-workers is
;;; a built-in handler backend, the sibling of the clack-handler-rontolisp one
;;; that clackup uses when it DOES own a socket, and its `handle` is the whole
;;; adapter: it builds the Clack environment from the JSON envelope, runs the
;;; application, normalizes the Clack response back, and answers 500 rather than
;;; trapping if the application signals. It converts nothing itself -- rontolisp's
;;; server protocol IS Clack's, so it rides the same backend-free
;;; rontolisp::%http-make-env / %http-normalize-response entry points every other
;;; transport meets in. The envelope both sides speak is documented there.
;;;
;;; The two keywords are not incantation, they are what this host is:
;;;
;;; - :use-thread nil is for the OTHER backends. On WASM it is already the
;;;   default (single-threaded by construction), but the interpreter and the JVM
;;;   have threads, so clackup would otherwise store the application on one --
;;;   and demo.lisp, which drives this file without Cloudflare, would race it.
;;; - :use-default-middlewares nil drops lack's `backtrace` middleware, whose
;;;   whole job is to print a report to *error-output* -- which a reactor does
;;;   not have. It also prints on an error the application CATCHES, and on the
;;;   compiled backends `(symbol-value '*error-output*)` is itself unbound
;;;   today, so leaving it in replaces a handled error with a failure. The
;;;   handler backend already answers 500 for anything the application signals.
;;;
;;; Nothing here does I/O, which is what lets build.sh compile it with --no-wasi:
;;; the module imports NOTHING, so the Worker instantiates it with an empty
;;; import object and needs no WASI shim. clackup's own start-up banner is the
;;; one exception, and it is not a trap: under --no-wasi stdout is a SINK, so
;;; the bytes are simply discarded (a `print` you add here goes the same way --
;;; see the README).

(ql:quickload "clack-handler-cloudflare-workers")

(load "app.lisp")

(clack:clackup #'app
               :server :cloudflare-workers
               :use-thread nil
               :use-default-middlewares nil)

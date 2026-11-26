;; The swank package: a SHIM satisfying the built-in ASDF system "swank".
;; Upstream swank is SLIME's server half, and it cannot be loaded here: slime's
;; system definition is a PROGRAM, not the defsystem-as-data subset the .asd
;; front-end reads, and its server is built on its host's own debugger, threads and
;; streams. rontolisp's SWANK server is the command line's (.kb/swank.md): the one
;; `rontolisp --swank` serves, which create-server starts on the program's own
;; evaluator through the interpreter's rontolisp::%swank-* hook.
;;
;; clack.asd hard-depends on "swank": without the shim, (ql:quickload "clack")
;; downloads the slime tarball and then dies parsing it.
;; Written in canonical shape; the package is seeded in PackageRegistry.
;;
;; Compatibility notes:
;; - create-server takes SWANK's keys and answers the port. It prints SWANK's
;;   ";; Swank started at port: P." on *error-output*; :dont-close t serves every
;;   connection on a thread of its own and returns at once, nil (SWANK's default)
;;   waits for the first connection and then closes the port. :style and :backlog
;;   are accepted and ignored: the style is always :spawn.
;; - A compiled program SIGNALS from create-server rather than no-op'ing: it has
;;   no interpreter to serve, and a silent no-op would leave the caller (clackup's
;;   :swank-port) waiting on a port that never opens. stop-server is nil there,
;;   the honest counterpart: nothing was ever started.
;; - stop-server closes the port, the connections made going on; a port no server
;;   listens on warns, as SWANK does.

#+rontolisp-interpreter
(defun swank:create-server (&key (port 4005) dont-close interface style backlog)
  (declare (ignore style backlog))
  (let ((port (rontolisp::%swank-listen port interface)))
    (format *error-output* "~&;; Swank started at port: ~D.~%" port)
    (force-output *error-output*)
    (rontolisp::%swank-serve port dont-close)
    port))

#+rontolisp-interpreter
(defun swank:stop-server (port)
  (unless (rontolisp::%swank-stop port)
    (warn "No server for ~s: ~s" :port port))
  nil)

#-rontolisp-interpreter
(defun swank:create-server (&rest args)
  (error
   "swank:create-server is not supported in a compiled program: only the interpreter serves SWANK (run the program with rontolisp, or rontolisp --swank)"))

#-rontolisp-interpreter (defun swank:stop-server (&rest args) nil)

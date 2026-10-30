;; The swank package: a STUB satisfying the built-in ASDF system "swank".
;; Upstream swank is SLIME's server half, and it cannot be loaded here: slime's
;; system definition is a PROGRAM, not the defsystem-as-data subset the .asd
;; front-end reads, and its server is built on its host's own debugger, threads and
;; streams. rontolisp's SWANK server is the command line's: `rontolisp --swank`
;; serves the interpreter's REPL to SLIME (.kb/swank.md). A program cannot start it.
;;
;; The stub exists because clack.asd hard-depends on "swank": without it,
;; (ql:quickload "clack") downloads the slime tarball and then dies parsing it.
;; Written in canonical shape; the package is seeded in PackageRegistry.
;;
;; Compatibility notes:
;; - create-server SIGNALS rather than no-op'ing. clack reaches it only when
;;   clackup is given :swank-port, i.e. when the caller explicitly asked for a
;;   remote REPL, and a silent no-op there would leave them waiting on a port
;;   that never opens.
;; - stop-server is a nil no-op, which is the honest counterpart: clack's stop
;;   calls it whenever a swank port was recorded, and nothing was ever started.

(defun swank:create-server (&rest args)
  (error
   "swank:create-server is not supported: a program cannot serve its REPL; rontolisp --swank serves one to SLIME"))

(defun swank:stop-server (&rest args) nil)

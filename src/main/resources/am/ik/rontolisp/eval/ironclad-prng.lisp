;; A leaf-module shim replacing ironclad's src/prng/prng.lisp.
;;
;; The slice needs exactly one name from prng/: `random-data', which is the
;; body of `make-random-salt' -- the DEFAULT of the `:salt' keyword of
;; `pbkdf2-hash-password'. cl-postgres (like every SCRAM client) always passes
;; the server-supplied salt, so that default is never taken; the compile paths
;; are eager, though, and refuse to compile a call to an undefined function, so
;; a definition has to exist.
;;
;; It signals rather than returning bytes, deliberately. The real prng/ is a
;; Fortuna CSPRNG seeded from /dev/urandom (plus os-prng.lisp's per-platform
;; entropy source), and rontolisp's `random' is not a cryptographic generator
;; on any backend -- silently substituting it would hand out predictable salts
;; under a name whose whole contract is unpredictability. A caller that wants a
;; random salt must generate it itself. Widening the slice to a real CSPRNG
;; (which needs a cross-backend entropy source: wasi:random on the component
;; path) is the honest alternative, and the reason this file records the
;; tradeoff instead of hiding it.
;;
;; Written in canonical shape (qualified names, no in-package): the leaf-module
;; splice bypasses the package-resolution bracketing a loaded file gets.

(defun ironclad:random-data (num-bytes &optional prng)
  (declare (ignore num-bytes prng))
  (error "ironclad: this build has no pseudo-random-number generator; pass an explicit salt"))

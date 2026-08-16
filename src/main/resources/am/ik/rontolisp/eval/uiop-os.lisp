;;;; uiop/os -- host identity, feature expressions and the working directory.
;;;;
;;;; Everything a caller can ask about the host here is answered from ONE
;;;; source, upstream's own: featurep over *features*, which is an ordinary
;;;; special variable holding the running backend's feature list on every
;;;; backend. The definitions below are read per backend (UiopLibrary.process
;;;; takes the feature set), so architecture / implementation-identifier answer
;;;; for the backend that is running rather than for the one that compiled.
;;;; See .kb/uiop.md.
;;;;
;;;; Three decisions this file makes, with their reasons:
;;;;
;;;; * os-unix-p is t OUTRIGHT rather than (featurep :unix). Every backend
;;;;   presents the POSIX-shaped file and namestring model (.kb/pathnames.md),
;;;;   so the answer is right -- but *features* deliberately does not carry
;;;;   :unix, because that would flip the #+unix reader branch of every library
;;;;   the frontend reads (cl-postgres' unix-domain socket path, among others),
;;;;   which is a far wider claim than this one predicate.
;;;; * The environment is READ from the host and WRITTEN to an override map.
;;;;   No backend can rewrite its own process environment -- the JVM cannot at
;;;;   all, WASI's is read-only -- so (setf (uiop:getenv name) value) records
;;;;   the value in a per-program map that getenv consults first. That is what
;;;;   rove's with-local-envs (run's :env option) needs, and it behaves
;;;;   identically on all four backends. A nil value is an unset, upstream's
;;;;   own semantics for (setf (getenv x) nil).
;;;; * chdir signals, and so does getcwd where the host has no working
;;;;   directory. Java cannot move the process working directory at all
;;;;   (user.dir is read at startup and does not follow the OS cwd), and a WASI
;;;;   program has preopened directories and no current one -- so chdir would
;;;;   have to lie on every backend. getcwd answers %host-getcwd where there is
;;;;   one (the JVM's user.dir on the interpreter and the JVM) and signals
;;;;   where the primitive answers nil (both WASM backends), which keeps the
;;;;   divergence in a VALUE rather than in a second code path.

;;; Feature expressions. Upstream's definition, including the &optional feature
;;; set (which lets a caller test a set other than the running one) and its
;;; default, the live *features* list -- so (let ((*features* ...)) (featurep
;;; :x)) works, upstream's own invitation. Upstream spells that parameter
;;; *features* and lets the binding be dynamic; here it carries this file's
;;; %-prefix like every other parameter and is threaded through the recursion,
;;; which answers the same for every input.
(defun uiop/os:featurep (%fp-x &optional (%fp-features *features*))
  (cond ((atom %fp-x) (and (member %fp-x %fp-features) t))
        ((eq :not (car %fp-x))
         (not (uiop/os:featurep (car (cdr %fp-x)) %fp-features)))
        ((eq :or (car %fp-x))
         (and (some (lambda (%fp-e) (uiop/os:featurep %fp-e %fp-features))
                    (cdr %fp-x)) t))
        ((eq :and (car %fp-x))
         (and (every (lambda (%fp-e) (uiop/os:featurep %fp-e %fp-features))
                     (cdr %fp-x)) t))
        (t (uiop/utility:parameter-error
            "~S: malformed feature specification ~S" 'uiop/os:featurep %fp-x))))

;;; The OS predicates. Upstream's derivations, except os-unix-p (see the header).
(defun uiop/os:os-macosx-p ()
  (uiop/os:featurep
   '(:or :darwin (:and :allegro :macosx) (:and :clisp :macos))))

(defun uiop/os:os-unix-p () t)

(defun uiop/os:os-windows-p ()
  (and (not (uiop/os:os-unix-p))
       (uiop/os:featurep '(:or :win32 :windows :mswindows :mingw32 :mingw64))))

(defun uiop/os:os-genera-p () (uiop/os:featurep :genera))

;; Upstream loops over the OS predicates, pushes the winner onto *features* and
;; returns it. Only one predicate can win here (os-unix-p is t outright), so
;; the loop is the constant -- and the push is upstream's own, which a caller
;; can read back with (featurep :os-unix).
(defun uiop/os:detect-os ()
  (pushnew :os-unix *features*)
  :os-unix)

;;; Identity.
(defun uiop/os:implementation-type () :rontolisp)

;; Upstream caches (implementation-type) in the variable; there is one
;; implementation here, so the value is written directly -- a defvar whose
;; initform calls a function defined above it is a load-order question the
;; constant does not have.
(defvar uiop/os:*implementation-type* :rontolisp)

(defun uiop/os:operating-system () :unix)

;; The ABI the compiled artifact targets, which is what upstream's architecture
;; is used for (segregating fasl caches): the JVM class file on the interpreter
;; and the JVM backend -- CPU-independent, so the CPU is not the answer -- and
;; wasm32 on both WASM backends.
(defun uiop/os:architecture ()
  (if (uiop/os:featurep :rontolisp-wasm) :wasm32 :jvm))

(defun uiop/os:lisp-version-string () (getf (rontolisp:version) :version))

(defun uiop/os:implementation-identifier ()
  (substitute-if #\_ (lambda (%ii-c) (find %ii-c " /:;&^\\|?<>(){}[]$#`'\""))
                 (string-downcase
                  (uiop/utility:strcat (string (uiop/os:implementation-type))
                                       "-" (uiop/os:lisp-version-string) "-"
                                       (string (uiop/os:operating-system)) "-"
                                       (string (uiop/os:architecture))))))

;;; Environment variables. getenv reads the override map first and the host
;;; second; (setf (uiop:getenv x) v) writes the override map (see the header).
(defun uiop/os:getenv (%ge-name)
  (let ((%ge-hit (%getenv-override %ge-name)))
    (if %ge-hit (cdr %ge-hit) (%host-getenv %ge-name))))

(defun (setf uiop/os:getenv) (%se-value %se-name)
  (%getenv-override-set %se-name %se-value))

(defun uiop/os:getenvp (%gp-x)
  (let ((%gp-g (uiop/os:getenv %gp-x)))
    (and (not (uiop/utility:emptyp %gp-g)) %gp-g)))

;;; Other system information.
;; nil, which is exactly what upstream's hostname answers on an implementation
;; none of its #+ clauses names: no backend has a host-identity primitive
;; (rontolisp has no machine-instance, WASI exposes no hostname at all), and a
;; fabricated "localhost" would be an answer rather than the absence of one.
;; machine-instance EXISTS now and answers nil for that same reason, so wiring
;; hostname to it would only move the constant; re-evaluation trigger: the day a
;; backend gains a host-identity primitive, both become one line.
(defun uiop/os:hostname () nil)

;;; The working directory.
(defun uiop/os:getcwd ()
  (let ((%cwd (%host-getcwd)))
    (if %cwd
        (uiop/pathname:ensure-directory-pathname %cwd)
        (uiop/utility:not-implemented-error "UIOP/OS:GETCWD"
                                            "this backend has no working directory (a WASI program has preopened directories and no current one)"))))

(defun uiop/os:chdir (%cd-x)
  (declare (ignore %cd-x))
  (uiop/utility:not-implemented-error "UIOP/OS:CHDIR"
                                      "no backend can move its own working directory (the JVM reads user.dir once at startup; WASI has no chdir)"))

;;; Windows shortcut support. The two octet readers are portable stream work
;;; and are real; the two .lnk parsers navigate the file with file-position,
;;; which is nil for a file stream on every backend (the deliberately lite
;;; stream repositioning of .kb/read-load-streams.md), so they name that
;;; primitive instead of silently misparsing. Re-evaluation trigger: the day
;;; file-position works on a binary file stream, both are upstream's bodies.
(defun uiop/os:read-null-terminated-string (%rnt-s)
  (with-output-to-string (%rnt-out)
    (do ((%rnt-code (read-byte %rnt-s) (read-byte %rnt-s)))
        ((zerop %rnt-code) nil)
      (write-char (code-char %rnt-code) %rnt-out))))

(defun uiop/os:read-little-endian (%rle-s &optional (%rle-bytes 4))
  (let ((%rle-sum 0))
    (dotimes (%rle-i %rle-bytes %rle-sum)
      (setq %rle-sum (+ %rle-sum (ash (read-byte %rle-s) (* 8 %rle-i)))))))

(defun uiop/os:parse-file-location-info (%pfli-s)
  (declare (ignore %pfli-s))
  (uiop/utility:not-implemented-error "UIOP/OS:PARSE-FILE-LOCATION-INFO"
   "it seeks with file-position, which a file stream does not support here"))

(defun uiop/os:parse-windows-shortcut (%pws-pathname)
  (declare (ignore %pws-pathname))
  (uiop/utility:not-implemented-error "UIOP/OS:PARSE-WINDOWS-SHORTCUT"
   "it seeks with file-position, which a file stream does not support here"))

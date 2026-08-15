;;;; uiop/pathname -- the pathname algebra. Canonical shape; see .kb/uiop.md.

;; The safer defaults-aware merge, portable across ASDF-loaded libraries. A
;; rontolisp pathname carries a flat namestring, so upstream's component-wise
;; care collapses onto cl:merge-pathnames, whose rontolisp definition already
;; implements the same rule (an absolute specified wins, a relative one is
;; appended to the defaults' directory, an absent component is taken from the
;; defaults). Lisp source rather than a Java built-in so all four backends have
;; it: as an interpreter-only primitive, a call with non-literal arguments was
;; "The function UIOP:MERGE-PATHNAMES* is undefined" on the JVM and on both WASM
;; backends.
(defun uiop/pathname:merge-pathnames* (%mps-specified &optional %mps-defaults)
  (merge-pathnames %mps-specified %mps-defaults))

;; A pathname's namestring is flat here, so "the pathname in directory form" is
;; "the namestring with a trailing slash": merge-pathnames against one of these
;; appends, which is exactly what smart-buffer's *temporary-directory* is built
;; by.
(defun uiop/pathname:ensure-directory-pathname (%edp-path)
  (let ((%edp-s (namestring %edp-path)))
    (pathname
     (if (or (string= %edp-s "")
             (char= (char %edp-s (- (length %edp-s) 1)) #\/))
         %edp-s
         (concatenate 'string %edp-s "/")))))

;; A rontolisp namestring is the host spelling, so "absolute" is the leading
;; separator -- there is no device or host component to weigh. Answers the
;; PATHNAME rather than T, like upstream (a generalized boolean).
(defun uiop/pathname:absolute-pathname-p (%apn-path)
  (let ((%apn-s (and %apn-path (namestring %apn-path))))
    (when (and (> (length %apn-s) 0) (char= (char %apn-s 0) #\/))
      (pathname %apn-s))))

;; upstream: an absolute path passes through, a relative one is merged against
;; DEFAULTS (a pathname designator, or a function answering one), and a relative
;; path with no absolute default is an ERROR. The error arm is deliberately not
;; taken here: rontolisp absolutizes NOWHERE -- truename carries the argument
;; namestring, *load-truename* is the path as resolved against the loading file,
;; and with no chdir a relative namestring denotes the same file for the whole
;; run (uiop::get-pathname-defaults is "" for exactly that reason). What callers
;; do with the value is use it as the IDENTITY of a file -- rove keys its
;; file-to-suite map by it and looks the key up again by
;; asdf:component-pathname -- and the path as resolved is that identity, so it
;; is answered as itself. ON-ERROR is accepted and unused: nothing fails.
(defun uiop/pathname:ensure-absolute-pathname
    (%eap-path &optional %eap-defaults %eap-on-error)
  (declare (ignore %eap-on-error))
  (let ((%eap-base
         (if (functionp %eap-defaults) (funcall %eap-defaults) %eap-defaults)))
    (cond ((null %eap-path) nil)
          ((uiop/pathname:absolute-pathname-p %eap-path))
          ((and %eap-base (uiop/pathname:absolute-pathname-p %eap-base))
           (uiop/pathname:merge-pathnames* %eap-path %eap-base))
          (t (pathname (namestring %eap-path))))))

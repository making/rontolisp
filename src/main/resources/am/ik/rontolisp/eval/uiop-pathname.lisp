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

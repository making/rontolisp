;;;; uiop/lisp-build -- compiling a file. Canonical shape; see .kb/uiop.md.

;; The pathname type a compiled file carries. rontolisp has no compile-file --
;; the compile backends compile a whole program, and a library file is spliced
;; into it -- so there is no compiled-file type, and nil says exactly that. Its
;; callers ask "is this path a fasl?" (rove's resolve-file), and against nil a
;; source path answers no.
(defun uiop/lisp-build:compile-file-type (&rest %cft-keys)
  (declare (ignore %cft-keys))
  nil)

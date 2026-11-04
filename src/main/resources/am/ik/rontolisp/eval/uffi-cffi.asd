;;;; A replacement for upstream uffi's uffi.asd (eval.AsdOverrides).
;;;;
;;;; The real file guards its whole defsystem with
;;;; #+(or allegro lispworks cmu openmcl digitool cormanlisp sbcl scl), so here it
;;;; defines nothing. UFFI's own sources bind each implementation's FFI by hand;
;;;; cffi-uffi-compat is the same API, package nickname included, over cffi. This is
;;;; the file cffi itself ships for that purpose (uffi-compat/uffi.asd).

(defsystem uffi :depends-on (cffi-uffi-compat))

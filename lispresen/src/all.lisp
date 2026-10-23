;;;; all.lisp -- every lispresen source, in load order.
;;;;
;;;; On rontolisp, top-level literal loads: the compilers splice the files in at compile
;;;; time, so a program loading this file compiles to one self-contained artifact, and a
;;;; relative path resolves against this file. Standard Common Lisp resolves it against
;;;; the working directory instead, so elsewhere the paths are merged with
;;;; *LOAD-TRUENAME*; the compile path does not splice that spelling yet.

#+rontolisp (load "package.lisp")
#+rontolisp (load "reader.lisp")
#+rontolisp (load "budoux-ja.lisp")
#+rontolisp (load "phrase.lisp")
#+rontolisp (load "metrics.lisp")
#+rontolisp (load "template.lisp")
#+rontolisp (load "model.lisp")
#+rontolisp (load "embed.lisp")
#+rontolisp (load "qr.lisp")
#+rontolisp (load "diagram.lisp")
#+rontolisp (load "layout.lisp")
#+rontolisp (load "lint.lisp")
#+rontolisp (load "theme.lisp")
#+rontolisp (load "player.lisp")
#+rontolisp (load "highlight.lisp")
#+rontolisp (load "morph.lisp")
#+rontolisp (load "html.lisp")
#+rontolisp (load "png.lisp")
#+rontolisp (load "truetype.lisp")
#+rontolisp (load "raster.lisp")
#+rontolisp (load "page-image.lisp")
#+rontolisp (load "lispresen.lisp")

;;; One compilation unit, so a call to a function defined in a later file is not
;;; reported as undefined.
#-rontolisp
(with-compilation-unit ()
  (dolist (name
           '("package" "reader" "budoux-ja" "phrase" "metrics" "template"
             "model" "embed" "qr" "diagram" "layout" "lint" "theme" "player"
             "highlight" "morph" "html" "png" "truetype" "raster"
             "page-image" "lispresen"))
    (load
     (merge-pathnames (concatenate 'string name ".lisp") *load-truename*))))

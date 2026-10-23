;;;; all.lisp -- every lispresen source, in load order.
;;;;
;;;; Each path is merged onto *LOAD-TRUENAME*, since standard Common Lisp resolves a
;;;; relative load against the working directory. rontolisp's compilers splice the files
;;;; in at compile time all the same, so a program loading this file compiles to one
;;;; self-contained artifact.

(load (merge-pathnames "package.lisp" *load-truename*))
(load (merge-pathnames "reader.lisp" *load-truename*))
(load (merge-pathnames "budoux-ja.lisp" *load-truename*))
(load (merge-pathnames "phrase.lisp" *load-truename*))
(load (merge-pathnames "metrics.lisp" *load-truename*))
(load (merge-pathnames "math.lisp" *load-truename*))
(load (merge-pathnames "template.lisp" *load-truename*))
(load (merge-pathnames "model.lisp" *load-truename*))
(load (merge-pathnames "embed.lisp" *load-truename*))
(load (merge-pathnames "qr.lisp" *load-truename*))
(load (merge-pathnames "diagram.lisp" *load-truename*))
(load (merge-pathnames "layout.lisp" *load-truename*))
(load (merge-pathnames "lint.lisp" *load-truename*))
(load (merge-pathnames "theme.lisp" *load-truename*))
(load (merge-pathnames "player.lisp" *load-truename*))
(load (merge-pathnames "highlight.lisp" *load-truename*))
(load (merge-pathnames "morph.lisp" *load-truename*))
(load (merge-pathnames "html.lisp" *load-truename*))
(load (merge-pathnames "png.lisp" *load-truename*))
(load (merge-pathnames "truetype.lisp" *load-truename*))
(load (merge-pathnames "raster.lisp" *load-truename*))
(load (merge-pathnames "page-image.lisp" *load-truename*))
(load (merge-pathnames "lispresen.lisp" *load-truename*))

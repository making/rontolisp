;;;; lispresen.lisp -- RENDER, the whole pipeline as one pure function.

(in-package :lispresen)

(defun render (source &key theme)
  "The HTML of the deck SOURCE, and its diagnostics ordered by line. THEME, a theme name,
overrides the deck's :theme."
  (let ((*diagnostics* nil) (*line* nil) (*slide* nil) (*slide-title* nil))
    (let* ((deck (parse-deck (read-forms source)))
           (theme (find-theme (or theme (deck-theme deck)))))
      (dolist (slide (deck-slides deck))
        (let ((*line* (slide-line slide))
              (*slide* (slide-number slide))
              (*slide-title* (slide-title slide)))
          (fit-slide deck slide)
          (lint-slide deck slide)))
      (values (with-output-to-string (out) (write-deck deck theme out))
              (stable-sort (reverse *diagnostics*) #'<
                           :key (lambda (d) (or (diagnostic-line d) 0)))))))

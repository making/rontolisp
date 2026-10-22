;;;; lispresen.lisp -- RENDER, the whole pipeline as one pure function.

(in-package :lispresen)

(defun runtime-option (x)
  "The runtime a command line names: none, hosted, or a URL."
  (cond ((string-equal x "none") :none)
        ((string-equal x "hosted") :hosted)
        (t x)))

(defun render (source &key theme runtime embed)
  "The HTML of the deck SOURCE, and its diagnostics ordered by line. THEME, a theme name,
overrides the deck's :theme; RUNTIME (none, hosted or a URL) its :runtime. EMBED, when
given, is a function from an image path to the file's bytes (nil when it cannot be read),
and every local image is inlined as a data: URI."
  (let ((*diagnostics* nil) (*line* nil) (*slide* nil) (*slide-title* nil))
    (let* ((deck (parse-deck (read-forms source)))
           (theme
            (let ((*line* (deck-line deck)))
              (find-theme (or theme (deck-theme deck))))))
      (when runtime (setf (deck-runtime deck) (runtime-option runtime)))
      (let ((*line* (deck-line deck))) (lint-theme theme))
      (dolist (slide (deck-slides deck))
        (let ((*line* (slide-line slide))
              (*slide* (slide-number slide))
              (*slide-title* (slide-title slide)))
          (when embed (embed-images slide embed))
          (fit-slide deck slide)
          (lint-slide deck slide)))
      (values (with-output-to-string (out) (write-deck deck theme out))
              (stable-sort (reverse *diagnostics*) #'<
                           :key (lambda (d) (or (diagnostic-line d) 0)))))))

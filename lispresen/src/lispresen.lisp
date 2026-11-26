;;;; lispresen.lisp -- RENDER and RENDER-PPTX, the whole pipeline as pure functions.

(in-package :lispresen)

(defun runtime-option (x)
  "The runtime a command line names: none, hosted, or a URL."
  (cond ((string-equal x "none") :none)
        ((string-equal x "hosted") :hosted)
        (t x)))

(defun diagnostics-in-line-order ()
  (stable-sort (reverse *diagnostics*) #'<
               :key (lambda (d) (or (diagnostic-line d) 0))))

(defun build-deck (source &key theme runtime read-file embed page-image record-geometry)
  "Parse, fit and lint SOURCE, leaving the model in the caller's diagnostic and file
bindings. Returns the deck, its theme and, under PAGE-IMAGE, the page image as
(PATH OCTETS). RECORD-GEOMETRY stores each slide's final geometry in its FIT."
  (let ((*read-file* read-file)
        (*record-geometry* record-geometry)
        (logo-src nil)
        (image nil))
    (let* ((deck (parse-deck (read-forms source)))
           (theme
            (let ((*line* (deck-line deck)))
              (find-theme (or theme (deck-theme deck))))))
      (when runtime (setf (deck-runtime deck) (runtime-option runtime)))
      (let ((*line* (deck-line deck)))
        (lint-theme theme)
        (when (deck-logo deck) (setf logo-src (figure-src (deck-logo deck))))
        (when (and embed (deck-logo deck))
          (embed-figure (deck-logo deck))))
      (dolist (slide (deck-slides deck))
        (let ((*line* (slide-line slide))
              (*slide* (slide-number slide))
              (*slide-title* (slide-title slide)))
          (when embed (embed-images slide))
          (fit-slide deck slide)
          (lint-slide deck slide)))
      (let ((*line* (deck-line deck))) (lint-footer deck))
      (when (and page-image (drawn-page-image-p deck) (deck-page-image-font deck))
        (let* ((*line* (deck-line deck))
               (octets (draw-page-image deck theme logo-src)))
          (when octets (setf image (list (deck-page-image deck) octets)))))
      (values deck theme image))))

(defun render (source &key theme runtime read-file embed page-image)
  "The HTML of the deck SOURCE, its diagnostics ordered by line, and under PAGE-IMAGE the
page image lispresen draws as (PATH OCTETS), PATH the deck's :page-image relative to the
HTML, or nil. THEME, a theme name, overrides the deck's :theme; RUNTIME (none, hosted or
a URL) its :runtime. READ-FILE is a function from a path the deck names to the file's
bytes (nil when it cannot be read); code files and the page image's font and logo are
read with it, and under EMBED every local image is inlined as a data: URI."
  (let ((*diagnostics* nil) (*line* nil) (*slide* nil) (*slide-title* nil))
    (multiple-value-bind (deck theme image)
        (build-deck source :theme theme :runtime runtime :read-file read-file
                           :embed embed :page-image page-image)
      (values (with-output-to-string (out) (write-deck deck theme out))
              (diagnostics-in-line-order) image))))

(defun render-pptx (source &key theme runtime read-file)
  "The PPTX of the deck SOURCE as octets, and its diagnostics ordered by line. The
layout is lispresen's; colors and fonts are theme references, so a theme applied later
in PowerPoint or Google Slides restyles the file. READ-FILE reads the images the file
embeds. THEME and RUNTIME mean what they mean in RENDER."
  (let ((*diagnostics* nil) (*line* nil) (*slide* nil) (*slide-title* nil)
        (*read-file* read-file))
    (multiple-value-bind (deck theme)
        (build-deck source :theme theme :runtime runtime :read-file read-file
                           :record-geometry t)
      (declare (ignore theme))
      (values (pptx-octets deck) (diagnostics-in-line-order)))))

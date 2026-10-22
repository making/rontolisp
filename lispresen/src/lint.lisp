;;;; lint.lisp -- readability checks on a laid-out slide and on the theme.
;;;;
;;;; Malformed forms are reported while parsing (model.lisp) and overflow while fitting
;;;; (layout.lisp); what is left here are the checks that need the chosen sizes.

(in-package :lispresen)

(defun text-block-p (b)
  (or (para-p b) (bullets-p b) (figure-p b) (quotation-p b)))

(defun lint-list (b s width depth)
  (when (> (length (bullets-items b)) 6)
    (report :warning (format nil
                      "a list with ~d items is hard to follow; keep it to 6"
                      (length (bullets-items b)))))
  (when (= depth 3)
    (report :warning "lists nested deeper than 2 levels are hard to follow"))
  (let ((indent (list-indent s)))
    (dolist (it (bullets-items b))
      (let ((lines (run-lines (item-run it) s (- width indent))))
        (when (> lines 3)
          (report :warning (format nil "an item takes ~d lines; keep each to 3"
                                   lines))))
      (when (item-sublist it)
        (lint-list (item-sublist it) s (- width indent) (1+ depth))))))

(defun lint-blocks (blocks fit width)
  (dolist (b blocks)
    (cond ((bullets-p b) (lint-list b (fit-body-size fit) width 1))
          ((column-row-p b)
           (loop for col in (column-row-columns b)
                 for w in (column-row-widths b)
                 do (lint-blocks (column-blocks col) fit w)))
          ((and (code-block-p b) (> (length (code-block-lines b)) 15))
           (report :warning (format nil
                                    "a code block of ~d lines is hard to read; keep it to 15"
                                    (length (code-block-lines b)))))
          ((and (table-block-p b) (< (table-block-size b) *table-floor*))
           (report
            :warning (format nil
                             "table text at ~dpx (floor ~dpx); reduce its rows or columns"
                             (table-block-size b) *table-floor*)))
          ((and (diagram-p b) (> (drawing-width (diagram-drawing b)) width))
           (report :error (format nil
                                  "the ~a is ~dpx wide at its smallest size, wider than the ~dpx it has; ~a"
                                  (if (eq (diagram-kind b) :flow)
                                      "flow"
                                      "cells drawing")
                                  (drawing-width (diagram-drawing b)) width
                                  (if (eq (diagram-kind b) :flow)
                                      "shorten its widest label"
                                      "draw a shorter list"))))
          ((and (diagram-p b) (< (diagram-size b) *diagram-floor*))
           (report :warning (format nil
                                    "diagram labels at ~dpx (floor ~dpx); draw fewer boxes or a shorter list"
                                    (diagram-size b) *diagram-floor*))))))

(defun lint-slide (deck slide)
  (let ((fit (slide-fit slide)))
    (when (slide-kicker-run slide)
      (cond ((null (fit-title-size fit))
             (report
              :warning "a kicker stands above a title; this slide has none"))
            ((> (run-lines (slide-kicker-run slide) *kicker-size*
                           (content-width deck)) 1)
             (report
              :warning
              "the kicker does not fit on one line and is cut; shorten it"))))
    (when (and (fit-title-size fit) (< (fit-title-size fit) *title-floor*))
      (report :warning (format nil
                        "title text at ~dpx (floor ~dpx); shorten the title"
                        (fit-title-size fit) *title-floor*)))
    (when (fit-body-size fit)
      (let ((blocks (region-blocks (slide-blocks slide))))
        (when (and (some #'text-block-p blocks)
                   (< (fit-body-size fit) *body-floor*))
          (report
           :warning (format nil
                            "body text at ~dpx (floor ~dpx); consider splitting the slide"
                            (fit-body-size fit) *body-floor*)))
        (when (and (some #'code-block-p blocks)
                   (< (fit-code-size fit) *code-floor*))
          (report :warning (format nil
                                   "code at ~dpx (floor ~dpx); shorten the lines or the block"
                                   (fit-code-size fit) *code-floor*))))
      (lint-blocks (slide-blocks slide) fit (content-width deck)))
    ;; The callouts of a slide share one size.
    (let ((c (car (slide-callouts slide))))
      (when (and c (< (callout-size c) *say-floor*))
        (report
         :warning (format nil
                          "callout text at ~dpx (floor ~dpx); shorten the callouts or make room"
                          (callout-size c) *say-floor*))))
    (dolist (c (slide-callouts slide))
      (let* ((s (callout-size c))
             (lines
              (run-lines (callout-run c) s
               (- (callout-width c) (* 2 (+ (say-pad-x s) *say-border*))))))
        (when (> lines 2)
          (report :warning (format nil
                                   "a callout takes ~d lines; keep each to 2"
                                   lines)))))))

;;; --- theme contrast ------------------------------------------------------------

;;; Text color, background, what is set in it. Every text color carries body-size text
;;; somewhere (the accent colors links and accented text, the muted color captions and
;;; leads), so each needs the WCAG AA ratio for normal text.
(defparameter *contrast-pairs*
  '(("fg" "bg" "body text") ("muted" "bg" "captions") ("accent" "bg" "links")
    ("fg" "head-bg" "table headers and callouts") ("fg" "mark" "marked text")
    ("code-fg" "code-bg" "code") ("code-muted" "code-bg" "code comments")
    ("hl-str" "code-bg" "code strings") ("hl-kw" "code-bg" "code keywords")
    ("hl-def" "code-bg" "code forms") ("hl-const" "code-bg" "code constants")))

(defun color-channels (s)
  "The 0-255 red, green and blue of #rgb or #rrggbb."
  (if (= (length s) 4)
      (loop for i from 1 to 3 collect (* 17 (digit-char-p (char s i) 16)))
      (loop for i from 1 to 5 by 2
            collect (parse-integer s :start i :end (+ i 2) :radix 16))))

(defun relative-luminance (s)
  "WCAG relative luminance of the color S. The one float computation of lispresen; it
feeds a comparison and a rounded number, so float printing never reaches the output."
  (let ((lin
         (mapcar (lambda (v)
                   (let ((c (/ v 255.0d0)))
                     (if (<= c 0.04045d0)
                         (/ c 12.92d0)
                         (expt (/ (+ c 0.055d0) 1.055d0) 2.4d0))))
                 (color-channels s))))
    (+ (* 0.2126d0 (first lin)) (* 0.7152d0 (second lin))
       (* 0.0722d0 (third lin)))))

(defun contrast-ratio (a b)
  (let ((la (relative-luminance a)) (lb (relative-luminance b)))
    (/ (+ (max la lb) 0.05d0) (+ (min la lb) 0.05d0))))

(defun lint-theme (theme)
  (dolist (pair *contrast-pairs*)
    (destructuring-bind (fg bg what) pair
      (let ((tenths
             (floor
              (* 10
                 (contrast-ratio (theme-color theme fg)
                                 (theme-color theme bg))))))
        (when (< tenths 45)
          (report :warning (format nil
                                   "theme: ~a (--~a on --~a) has contrast ~d.~d:1, below 4.5:1"
                                   what fg bg (floor tenths 10)
                                   (mod tenths 10))))))))

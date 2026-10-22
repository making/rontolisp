;;;; lint.lisp -- readability checks on a laid-out slide.
;;;;
;;;; Malformed forms are reported while parsing (model.lisp) and overflow while fitting
;;;; (layout.lisp); what is left here are the checks that need the chosen sizes.

(in-package :lispresen)

(defun text-block-p (b) (or (para-p b) (bullets-p b) (figure-p b)))

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

(defun lint-slide (deck slide)
  (let ((fit (slide-fit slide)) (width (content-width deck)))
    (when (and (fit-title-size fit) (< (fit-title-size fit) *title-floor*))
      (report :warning (format nil
                        "title text at ~dpx (floor ~dpx); shorten the title"
                        (fit-title-size fit) *title-floor*)))
    (when (fit-body-size fit)
      (let ((blocks (slide-blocks slide)))
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
                                   (fit-code-size fit) *code-floor*)))
        (dolist (b blocks)
          (cond ((bullets-p b) (lint-list b (fit-body-size fit) width 1))
                ((and (code-block-p b) (> (length (code-block-lines b)) 15))
                 (report
                  :warning (format nil
                                   "a code block of ~d lines is hard to read; keep it to 15"
                                   (length (code-block-lines b)))))
                ((and (table-block-p b) (< (table-block-size b) *table-floor*))
                 (report
                  :warning (format nil
                                   "table text at ~dpx (floor ~dpx); reduce its rows or columns"
                                   (table-block-size b) *table-floor*)))))))))

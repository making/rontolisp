;;;; starter.lisp -- the deck lispresen new writes: starter/ja.lisp or starter/en.lisp,
;;;; read into lispresen when it is built, dated by the caller.

(in-package :lispresen)

(defparameter *starter-decks*
  (list (cons :ja (build-text "../starter/ja.lisp"))
        (cons :en (build-text "../starter/en.lisp"))))

(defun starter-deck (date language)
  "The starter deck in LANGUAGE (:ja or :en) with :date DATE, a YYYY-MM-DD string."
  (let* ((text (cdr (assoc language *starter-decks*)))
         (at (search "YYYY-MM-DD" text)))
    (concatenate 'string (subseq text 0 at) date (subseq text (+ at 10)))))

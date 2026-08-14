;;;; uiop/utility -- the portable helper package.
;;;;
;;;; Canonical shape (home-package-qualified public names, bare cl names), so it
;;;; needs no package resolution. Only the names rontolisp really implements live
;;;; here; every other uiop/utility export gets a not-implemented-error stub
;;;; synthesized from the inventory (UiopLibrary). See .kb/uiop.md.

;;; The two conditions that give "we cannot do that here" a name. They are the
;;; first thing any other uiop item needs, so they are real from the start --
;;; every stub in every sub-package signals not-implemented-error, and no uiop
;;; name is allowed to reach a caller as "undefined function".
;;;
;;; Upstream reports the implementation it is running on by calling
;;; uiop:implementation-type; there is one implementation here, so the report
;;; names rontolisp directly rather than routing through a lookup that could only
;;; ever answer one thing.
(define-condition uiop/utility:not-implemented-error (error)
  ((functionality :initarg :functionality
                  :initform nil
                  :reader uiop/utility::%not-implemented-functionality)
   (format-control :initarg :format-control
                   :initform nil
                   :reader uiop/utility::%not-implemented-format-control)
   (format-arguments :initarg :format-arguments
                     :initform nil
                     :reader uiop/utility::%not-implemented-format-arguments))
  (:report
   (lambda (%nie-c %nie-s)
     (write-string "Not (currently) implemented on rontolisp: " %nie-s)
     (write-string
      (princ-to-string (uiop/utility::%not-implemented-functionality %nie-c))
      %nie-s)
     (let ((%nie-fc (uiop/utility::%not-implemented-format-control %nie-c)))
       (when %nie-fc
         (write-string " " %nie-s)
         (write-string (apply #'format nil %nie-fc
                              (uiop/utility::%not-implemented-format-arguments
                               %nie-c)) %nie-s))))))

(defun uiop/utility:not-implemented-error (%nie-functionality &optional
                                           %nie-format-control &rest
                                           %nie-format-arguments)
  (error 'uiop/utility:not-implemented-error
         :functionality %nie-functionality
         :format-control %nie-format-control
         :format-arguments %nie-format-arguments))

(define-condition uiop/utility:parameter-error (error)
  ((functionality :initarg :functionality
                  :initform nil
                  :reader uiop/utility::%parameter-error-functionality)
   (format-control :initarg :format-control
                   :initform nil
                   :reader uiop/utility::%parameter-error-format-control)
   (format-arguments :initarg :format-arguments
                     :initform nil
                     :reader uiop/utility::%parameter-error-format-arguments))
  (:report
   (lambda (%pe-c %pe-s)
     (write-string (apply #'format nil
                    (uiop/utility::%parameter-error-format-control %pe-c)
                    (uiop/utility::%parameter-error-functionality %pe-c)
                    (uiop/utility::%parameter-error-format-arguments %pe-c))
                   %pe-s))))

;; The functionality is the SECOND argument, as upstream: the format-control
;; takes it as its first format argument (a caller that does not want it there
;; skips it with ~*).
(defun uiop/utility:parameter-error
    (%pe-format-control %pe-functionality &rest %pe-format-arguments)
  (error 'uiop/utility:parameter-error
         :functionality %pe-functionality
         :format-control %pe-format-control
         :format-arguments %pe-format-arguments))

;;; Sequence / character utilities, bodies VERBATIM from upstream utility.lisp:
;;; pure Lisp one-liners over primitives every backend has. quri's render-uri
;;; calls all three to decide whether to insert a path slash.
(defun uiop/utility:emptyp (x)
  (or (null x) (and (vectorp x) (zerop (length x)))))

(defun uiop/utility:first-char (s)
  (and (stringp s) (plusp (length s)) (char s 0)))

(defun uiop/utility:last-char (s)
  (and (stringp s) (plusp (length s)) (char s (1- (length s)))))

;; Upstream's semantics (split on ANY character of the separator sequence,
;; scanning right to left so :max keeps the UNsplit head: ("a.b.c" :max 2 ->
;; ("a.b" "c")), empty string -> ("")), rewritten without upstream's
;; flet-return-from-outer-block shape: a `return` inside `do` would exit do's own
;; nil block, so the loop carries an explicit done flag instead. sxql's
;; sql-symbol tokenizer calls it on every dotted column name.
(defun uiop/utility:split-string (string &key max (separator '(#\Space #\Tab)))
  (let ((end (length string)))
    (if (zerop end)
        (list "")
        (let ((parts nil) (words 0) (done nil))
          (do ()
              (done)
            (if (and max (>= words (1- max)))
                (setq done t)
                (let ((start
                       (position-if (lambda (c) (find c separator)) string
                                    :end end
                                    :from-end t)))
                  (if (null start)
                      (setq done t)
                      (progn
                        (setq parts (cons (subseq string (1+ start) end) parts))
                        (setq words (1+ words))
                        (setq end start))))))
          (cons (subseq string 0 end) parts)))))

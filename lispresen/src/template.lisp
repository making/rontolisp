;;;; template.lisp -- deck templates: (define name (params...) body...).
;;;;
;;;; A use (name arg...) anywhere in a later top-level form is replaced by the body with
;;;; each parameter replaced by its argument; a &rest parameter is spliced into the list
;;;; it appears in, and a body of several forms is spliced into the list holding the use.
;;;; This is substitution on read data, not Lisp macros: the deck is never evaluated.
;;;; Names are matched by SYMBOL-NAME, as tags are. Expansion is bounded in depth and in
;;;; size, so a deck from an untrusted source cannot make it run away.

(in-package :lispresen)

(defparameter *element-names*
  '("DECK" "SECTION" "SLIDE" "DEFINE" "P" "UL" "OL" "LI" "CODE" "IMAGE" "QUOTE"
    "COLUMN" "TABLE" "CELL" "NOTES" "THEME" "LEAD" "B" "I" "MARK" "ACCENT" "TT"
    "LINK" "SAY")
  "The tags lispresen gives a meaning to; a template or parameter cannot take one.")

(defparameter *template-depth* 32 "How deeply template uses may nest.")
(defparameter *expansion-limit*
  200000
  "How many list elements the templates of one deck may produce.")

;;; NAME and PARAMS are upper-case names; REST is the &rest parameter's name or nil.
(defstruct template name params rest body)

(defvar *templates* nil "The templates of the deck being parsed.")
(defvar *expansion-budget* 0 "List elements templates may still produce.")
(defvar *expansion-failed*
  nil
  "True once the top-level form being expanded hit a limit; it is then dropped.")

(defun name-symbol-p (x)
  "True when X can name a template or a parameter."
  (and x (symbolp x) (not (keywordp x))
       (not (member (symbol-name x) '("T" "NIL") :test #'string=))))

(defun find-template (form)
  (and (consp form) (name-symbol-p (car form))
       (find (symbol-name (car form)) *templates*
             :key #'template-name
             :test #'string=)))

(defun parse-params (params name)
  "The parameter names and the &rest name of PARAMS, or :error after reporting."
  (let ((names nil) (rest nil) (ok (listp params)))
    (loop while (and ok (consp params))
          do
            (let ((p (pop params)))
              (cond ((and (symbolp p) (string= (symbol-name p) "&REST"))
                     (setf ok
                           (and (consp params) (null (cdr params))
                                (name-symbol-p (car params))))
                     (when ok (setf rest (symbol-name (pop params)))))
                    ((name-symbol-p p) (push (symbol-name p) names))
                    (t (setf ok nil)))))
    (let* ((all (if rest (cons rest names) names))
           (element
            (find-if (lambda (p) (member p *element-names* :test #'string=))
                     all)))
      (cond ((not ok)
             (report :error (format nil
                                    "the parameters of ~a must be symbols, the last optionally after &rest"
                                    name))
             :error)
            ((/= (length all) (length (remove-duplicates all :test #'string=)))
             (report :error (format nil "template ~a repeats a parameter" name))
             :error)
            (element
             (report :error (format nil "parameter ~a of ~a is an element name"
                                    (string-downcase element) name))
             :error)
            (t (values (nreverse names) rest))))))

(defun define-template (form)
  "Records the template (define name (params...) body...), or reports why it is not one."
  (let ((name (cadr form)))
    (if (not (and (name-symbol-p name) (consp (cddr form))))
        (report :error "(define ...) takes a name, a parameter list and a body")
        (let ((label (string-downcase (symbol-name name))))
          (cond ((member (symbol-name name) *element-names* :test #'string=)
                 (report :error (format nil
                                 "~a is an element and cannot be a template"
                                 label)))
                ((find-template (list name))
                 (report
                  :error (format nil "template ~a is already defined" label)))
                ((null (cdddr form))
                 (report :error (format nil "template ~a has no body" label)))
                (t (multiple-value-bind (params rest)
                       (parse-params (caddr form) label)
                     (unless (eq params :error)
                       (push (make-template :name (symbol-name name)
                                            :params params
                                            :rest rest
                                            :body (cdddr form))
                             *templates*)))))))))

(defun substitute-params (forms bindings rest rest-args)
  "FORMS with each parameter replaced by its binding in BINDINGS and REST spliced."
  (let ((out nil))
    (dolist (x forms)
      (if (and rest (name-symbol-p x) (string= (symbol-name x) rest))
          (dolist (a rest-args) (push a out))
          (push (substitute-param x bindings rest rest-args) out)))
    (nreverse out)))

(defun substitute-param (x bindings rest rest-args)
  (cond ((consp x) (substitute-params x bindings rest rest-args))
        ((name-symbol-p x)
         (let ((entry (assoc (symbol-name x) bindings :test #'string=)))
           (if entry (cdr entry) x)))
        (t x)))

(defun expand-use (template form depth)
  "The forms the use FORM of TEMPLATE expands to."
  (let ((name (string-downcase (template-name template)))
        (args (cdr form))
        (n (length (template-params template))))
    (cond ((>= depth *template-depth*)
           (setf *expansion-failed* t)
           (report
            :error (format nil
                           "template ~a nests more than ~d uses deep; does it use itself?"
                           name *template-depth*))
           nil)
          ((if (template-rest template)
               (< (length args) n)
               (/= (length args) n))
           (report :error (format nil
                                  "template ~a takes ~:[~;at least ~]~d argument~:p, given ~d"
                                  name (template-rest template) n
                                  (length args)))
           nil)
          (t (let ((body
                    (substitute-params (template-body template)
                     (mapcar #'cons (template-params template) args)
                     (template-rest template) (nthcdr n args))))
               (expand-forms body (1+ depth)))))))

(defun expand-forms (forms depth)
  "FORMS, a list, with every template use in it replaced by its expansion."
  (let ((out nil))
    (dolist (x forms) (dolist (f (expand x depth)) (push f out)))
    (nreverse out)))

(defun expand (form depth)
  "The forms FORM stands for: itself with its template uses expanded, or the expansion
of FORM when it is a use."
  (when (or *expansion-failed* (<= (decf *expansion-budget*) 0))
    (when (and (not *expansion-failed*) (zerop *expansion-budget*))
      (report :error (format nil "templates expand to more than ~d forms"
                             *expansion-limit*)))
    (setf *expansion-failed* t)
    (return-from expand nil))
  (let ((template (find-template form)))
    (cond (template (expand-use template form depth))
          ((equal (form-tag form) "DEFINE")
           (report :error "(define ...) belongs at the top level")
           nil)
          ((consp form) (list (expand-forms form depth)))
          (t (list form)))))

(defun expand-top-level (form)
  "The top-level forms FORM stands for after template expansion; none when a limit was
hit."
  (let* ((*expansion-failed* nil) (forms (expand form 0)))
    (if *expansion-failed* nil forms)))

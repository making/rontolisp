;;;; theme.lisp -- the built-in color themes and theme overrides.
;;;;
;;;; A theme is (NAME value...) with one value per *THEME-VARIABLES* entry. A deck's
;;;; :theme is a built-in name or (theme [:base name] [:code name] [:variable "#rrggbb"]...),
;;;; which starts from the base (light by default), takes the code colors of the :code
;;;; theme, and replaces the named colors. A built-in theme may add stylesheet rules of its
;;;; own (*THEME-STYLES*); an override is named after its base and keeps them.

(in-package :lispresen)

(defparameter *theme-variables*
  '("bg" "fg" "muted" "accent" "code-bg" "code-fg" "code-muted" "head-bg" "rule"
    "mark" "hl-str" "hl-kw" "hl-def" "hl-const"))

(defparameter *code-variables*
  '("code-bg" "code-fg" "code-muted" "hl-str" "hl-kw" "hl-def" "hl-const")
  "The colors of code, which (theme :code name) takes from another theme.")

(defparameter *themes*
  '(("LIGHT" "#ffffff" "#1f2328" "#59636e" "#0b5cad" "#f3f4f6" "#1f2328"
     "#59636e" "#e3ebf6" "#d0d7de" "#fff0a0" "#0a3069" "#0550ae" "#b3261e"
     "#7a3fc4")
    ("DARK" "#15181d" "#e6e8eb" "#9aa3ad" "#6cb6ff" "#22272e" "#e6e8eb"
     "#9aa3ad" "#253041" "#3d444d" "#5c4a00" "#96d0ff" "#6cb6ff" "#f47067"
     "#dcbdfb")
    ("HIGH-CONTRAST" "#000000" "#ffffff" "#d0d0d0" "#ffd400" "#1a1a1a" "#ffffff"
     "#d0d0d0" "#333333" "#8a8a8a" "#6b5800" "#a5ff90" "#7fdbff" "#ff9090"
     "#e8b8ff")
    ("MIDNIGHT" "#0f1533" "#eef0fb" "#a4abcc" "#f2c14e" "#080c24" "#eef0fb"
     "#8f97bf" "#1d2550" "#343d6e" "#6b4f00" "#ffd58a" "#8db7ff" "#ff8a6e"
     "#c9adff")))

;;; Midnight sets the title, section and statement slides between two thin arcs, the
;;; edges of 1000 x 2400 px ellipses whose center is 540 px in from the canvas edge, so
;;; an arc runs 40 px from the edge at mid-height and 88 px at the top and bottom: always
;;; inside the 112 px side margin, clear of text, the QR code and the page number. Its
;;; display text takes proportional kana (palt) and the kicker the regular weight, both
;;; narrower than the widths the fit solver measured, so nothing it placed moves out.
(defparameter *midnight-style*
  ".layout-title::before,.layout-section::before,.layout-statement::before,.layout-title::after,.layout-section::after,.layout-statement::after{content:'';position:absolute;top:-660px;width:1000px;height:2400px;border:0 solid var(--accent);border-radius:50%}
.layout-title::before,.layout-section::before,.layout-statement::before{left:40px;border-left-width:3px}
.layout-title::after,.layout-section::after,.layout-statement::after{right:40px;border-right-width:3px}
.centered{z-index:1}
.title,.deck-title,.section-title,.statement{font-feature-settings:'palt';letter-spacing:-.01em}
.section-title{color:var(--fg)}
.kicker{font-weight:400;font-feature-settings:'palt'}
li::marker{content:'\b7  ';color:var(--accent);font-weight:700}
p code,li code,th code,td code{background:var(--head-bg);color:var(--fg)}
pre{border-radius:2px;box-shadow:inset 0 0 0 1px var(--rule)}
.out,.codebox textarea,.say{border-radius:2px}
thead th{background:none;color:var(--accent);box-shadow:inset 0 -1px var(--accent)}
.quote{border-left-width:3px;padding-left:calc(var(--indent) - 3px)}
.page{color:var(--accent)}
")

(defparameter *theme-styles*
  (list (cons "MIDNIGHT" *midnight-style*))
  "The stylesheet rules of a built-in theme, by name, written after the fixed ones.")

(defun theme-name (x)
  (cond ((stringp x) (string-upcase x)) (t (symbol-value-name x))))

(defun builtin-theme (x)
  (let ((name (theme-name x)))
    (or (assoc name *themes* :test #'equal)
        (progn
          (report :warning (format nil "unknown theme ~a; using light"
                                   (string-downcase (or name ""))))
          (car *themes*)))))

(defun color-p (s)
  "True for #rgb or #rrggbb."
  (and (stringp s) (member (length s) '(4 7)) (char= (char s 0) #\#)
       (every (lambda (c) (digit-char-p c 16)) (subseq s 1))))

(defun theme-override (form)
  "The theme (theme [:base name] [:code name] [:variable color]...) describes."
  (multiple-value-bind (opts rest) (split-options (cdr form)
                                                  (list* "BASE" "CODE"
                                                         (mapcar #'string-upcase
                                                          *theme-variables*))
                                                  "theme")
    (when rest (report :error "(theme ...) takes options only"))
    (let* ((base (builtin-theme (opt opts "BASE" :light)))
           (values (copy-list (cdr base))))
      (when (opt opts "CODE")
        (let ((code (builtin-theme (opt opts "CODE"))))
          (loop for name in *theme-variables*
                for i from 0
                do
                  (when (member name *code-variables* :test #'string=)
                    (setf (nth i values) (theme-color code name))))))
      (loop for name in *theme-variables*
            for i from 0
            do
              (let ((v (opt opts (string-upcase name))))
                (cond ((null v))
                      ((color-p v) (setf (nth i values) (string-downcase v)))
                      (t (report :error (format nil
                                                ":~a of theme must be a color like #1f2328"
                                                name))))))
      (cons (car base) values))))

(defun find-theme (x)
  "The theme X names (a keyword or a string) or describes (a (theme ...) form), the
light theme when X is nil."
  (cond ((null x) (car *themes*))
        ((equal (form-tag x) "THEME") (theme-override x))
        ((or (stringp x) (symbolp x)) (builtin-theme x))
        (t
         (report :error ":theme must be a theme name or (theme ...)")
         (car *themes*))))

(defun theme-color (theme name)
  (nth (position name *theme-variables* :test #'string=) (cdr theme)))

(defun write-theme-css (theme out)
  (write-string ":root{" out)
  (loop for name in *theme-variables*
        for value in (cdr theme)
        do (format out "--~a:~a;" name value))
  (write-string "}" out))

(defun write-theme-style (theme out)
  (let ((style (cdr (assoc (car theme) *theme-styles* :test #'equal))))
    (when style (write-string style out))))

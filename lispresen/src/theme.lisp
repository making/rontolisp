;;;; theme.lisp -- the built-in color themes and theme overrides.
;;;;
;;;; A theme is (NAME value...) with one value per *THEME-VARIABLES* entry. A deck's
;;;; :theme is a built-in name or (theme [:base name] [:variable "#rrggbb"]...), which
;;;; starts from the base (light by default) and replaces the named colors.

(in-package :lispresen)

(defparameter *theme-variables*
  '("bg" "fg" "muted" "accent" "code-bg" "head-bg" "rule" "mark" "hl-str"
    "hl-kw" "hl-def" "hl-const"))

(defparameter *themes*
  '(("LIGHT" "#ffffff" "#1f2328" "#59636e" "#0b5cad" "#f3f4f6" "#e3ebf6"
     "#d0d7de" "#fff0a0" "#0a3069" "#0550ae" "#b3261e" "#7a3fc4")
    ("DARK" "#15181d" "#e6e8eb" "#9aa3ad" "#6cb6ff" "#22272e" "#253041"
     "#3d444d" "#5c4a00" "#96d0ff" "#6cb6ff" "#f47067" "#dcbdfb")
    ("HIGH-CONTRAST" "#000000" "#ffffff" "#d0d0d0" "#ffd400" "#1a1a1a" "#333333"
     "#8a8a8a" "#6b5800" "#a5ff90" "#7fdbff" "#ff9090" "#e8b8ff")))

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
  "The theme (theme [:base name] [:variable color]...) describes."
  (multiple-value-bind (opts rest) (split-options (cdr form)
                                                  (cons "BASE"
                                                        (mapcar #'string-upcase
                                                         *theme-variables*))
                                                  "theme")
    (when rest (report :error "(theme ...) takes options only"))
    (let ((values (copy-list (cdr (builtin-theme (opt opts "BASE" :light))))))
      (loop for name in *theme-variables*
            for i from 0
            do
              (let ((v (opt opts (string-upcase name))))
                (cond ((null v))
                      ((color-p v) (setf (nth i values) (string-downcase v)))
                      (t (report :error (format nil
                                                ":~a of theme must be a color like #1f2328"
                                                name))))))
      (cons "CUSTOM" values))))

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

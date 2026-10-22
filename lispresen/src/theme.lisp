;;;; theme.lisp -- the built-in color themes.

(in-package :lispresen)

;;; Name, then the values of --bg --fg --muted --accent --code-bg --head-bg --rule --mark.
(defparameter *themes*
  '(("LIGHT" "#ffffff" "#1f2328" "#59636e" "#0b5cad" "#f3f4f6" "#e3ebf6"
     "#d0d7de" "#fff0a0")
    ("DARK" "#15181d" "#e6e8eb" "#9aa3ad" "#6cb6ff" "#22272e" "#253041"
     "#3d444d" "#5c4a00")
    ("HIGH-CONTRAST" "#000000" "#ffffff" "#d0d0d0" "#ffd400" "#1a1a1a" "#333333"
     "#8a8a8a" "#6b5800")))

(defparameter *theme-variables*
  '("bg" "fg" "muted" "accent" "code-bg" "head-bg" "rule" "mark"))

(defun theme-name (x)
  (cond ((stringp x) (string-upcase x)) (t (symbol-value-name x))))

(defun find-theme (x)
  "The theme X names (a keyword or a string), the light theme when X is nil."
  (let ((name (if x (theme-name x) "LIGHT")))
    (or (assoc name *themes* :test #'equal)
        (progn
          (report :warning (format nil "unknown theme ~a; using light"
                                   (string-downcase (or name ""))))
          (car *themes*)))))

(defun write-theme-css (theme out)
  (write-string ":root{" out)
  (loop for name in *theme-variables*
        for value in (cdr theme)
        do (format out "--~a:~a;" name value))
  (write-string "}" out))

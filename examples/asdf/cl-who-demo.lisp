;;;; cl-who via asdf:load-system
;;;; Loads the REAL cl-who v1.1.5 (Edi Weitz's unmodified upstream sources,
;;;; BSD) and renders (X)HTML through with-html-output-to-string. cl-who's
;;;; markup macros run a chain of ordinary defuns (and a generic function) AT
;;;; MACRO-EXPANSION TIME, so the whole template is expanded before codegen and
;;;; the produced HTML string is a compile-time constant on the compile paths.
;;;; str / esc / fmt splice evaluated / escaped / formatted content in, and
;;;; (setf (html-mode) ...) switches between the default :xml self-closing tags
;;;; and :html5 void tags. Runs identically on all four backends; see README.md
;;;; in this directory for the run commands (the library directory is passed
;;;; with --system-path).
;;;;
;;;; Run (library vendored in this repository):
;;;;   rontolisp examples/asdf/cl-who-demo.lisp --system-path src/test/resources/cl-who

(asdf:load-system :cl-who)

;; A full document: nested tags, an attribute, and text nodes. Attributes
;; render with single quotes; :xml (the default) self-closes empty tags.
(princ
 (cl-who:with-html-output-to-string (s)
   (:html (:head (:title "Hi")) (:body (:p "Hello" (:a :href "/x" "link"))))))
(terpri)

;; str evaluates a Lisp form and inserts its princ output; esc HTML-escapes
;; the special characters; fmt is an inline (format nil ...).
(princ
 (cl-who:with-html-output-to-string (s)
   (:div (:span (cl-who:str (+ 1 2))) (:span (cl-who:esc "<a&b>"))
         (:span (cl-who:fmt "~a-~a" 3 4)))))
(terpri)

;; Default :xml mode -- empty elements self-close as "<br />".
(princ (cl-who:with-html-output-to-string (s) (:br)))
(terpri)

;; Switch to HTML5 -- void elements render as bare "<br>".
(setf (cl-who:html-mode) :html5)
(princ (cl-who:with-html-output-to-string (s) (:br)))
(terpri)

;; Back to :xml; esc emits a numeric character reference for non-ASCII.
(setf (cl-who:html-mode) :xml)
(princ
 (cl-who:with-html-output-to-string (s)
   (:p (cl-who:esc (string (code-char 233))))))
(terpri)

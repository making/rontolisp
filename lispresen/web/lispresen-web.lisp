;;;; lispresen-web.lisp -- lispresen as a browser reactor.
;;;;
;;;;   rontolisp lispresen/web/lispresen-web.lisp -o lispresen.wasm --no-wasi --optimize
;;;;
;;;; Exports render (:string -> :string). A reactor export returns one value, so the
;;;; result is the HTML, then *SEPARATOR*, then one diagnostic per line as the CLI prints
;;;; them with "deck" as the file name. index.html is the editor page that calls it.

(load "../src/all.lisp")

(defparameter *separator* (format nil "~%<!-- lispresen diagnostics -->~%"))

(defun render-web (source)
  (multiple-value-bind (html diagnostics) (lispresen:render source)
    (with-output-to-string (out)
      (write-string html out)
      (write-string *separator* out)
      (dolist (d diagnostics)
        (write-line (lispresen:format-diagnostic "deck" d) out)))))

(rontolisp:wasm-export 'render-web
                       :as "render"
                       :params '(:string)
                       :returns :string)

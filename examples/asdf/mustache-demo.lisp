;;;; cl-mustache via asdf:load-system
;;;; Loads the REAL cl-mustache 0.12.3 (Kan-Ru Chen's unmodified upstream
;;;; sources, MIT/Expat) and renders Mustache templates. render* answers a
;;;; string, render writes to mustache:*output-stream* or to a stream argument,
;;;; and compile-template returns a closure you can call many times. The
;;;; template is a STRING (its body) or a PATHNAME (read from a file --
;;;; greeting.mustache in this directory, this demo's only runtime file I/O,
;;;; so the WASM runs need `wasmtime run --dir .`). A context is an alist, a
;;;; hash table, or a mustache:make-context carrying :partials. The library is
;;;; spec 1.1.2 compliant (194-case suite; the 36 it does not pass fail
;;;; identically on SBCL), and it renders the same on all four backends; see
;;;; README.md in this directory for the run commands (the library directory
;;;; is passed with --system-path).
;;;;
;;;; Run (library vendored in this repository):
;;;;   rontolisp examples/asdf/mustache-demo.lisp --system-path src/test/resources/cl-mustache

(asdf:load-system :cl-mustache)

;; Interpolation from an alist context. {{name}} escapes HTML,
;; {{{name}}} (and {{&name}}) inserts it raw.
(princ
 (mustache:render* "Hello, {{name}}! {{tag}} vs {{{tag}}}"
                   '((:name . "World") (:tag . "<b>"))))
(terpri)

;; A section over a list of alists repeats its body once per element; an
;; inverted section renders only when the key is absent or false.
(princ
 (mustache:render*
  "{{#items}}- {{name}} x{{qty}}
{{/items}}{{^items}}(nothing){{/items}}"
  '((:items . (((:name . "pen") (:qty . 2)) ((:name . "ink") (:qty . 3)))))))

;; A hash table works as a context too -- keys are looked up upcased.
(princ
 (mustache:render* "{{greeting}}, {{name}}!"
                   (let ((ctx (make-hash-table :test #'equal)))
                     (setf (gethash "GREETING" ctx) "Hi")
                     (setf (gethash "NAME" ctx) "mustache")
                     ctx)))
(terpri)

;; The template itself can live in a file: render and compile-template
;; dispatch on it -- a PATHNAME reads the file, a STRING is the template body
;; itself, so the namestring "greeting.mustache" verbatim would render the
;; filename, not the file. The read is this demo's only runtime file I/O; the
;; WASM builds need `wasmtime run --dir .` so the path resolves against the
;; preopened directory. The same file, the two context kinds:
(mustache:render (pathname "examples/asdf/greeting.mustache")
                 '((:name . "rontolisp")
                   (:items .
                           (((:name . "interpreter") (:qty . 1))
                            ((:name . "jvm") (:qty . 2))
                            ((:name . "wasm") (:qty . 3))))))
(mustache:render (pathname "examples/asdf/greeting.mustache")
                 (let ((ctx (make-hash-table :test #'equal)))
                   (setf (gethash "NAME" ctx) "mustache")
                   (setf (gethash "ITEMS" ctx) nil)
                   ctx))

;; make-context carries partials: {{>name}} splices one in, and {{>*name}}
;; picks the partial whose name the data supplies (a "dynamic name").
(princ
 (mustache:render* "{{>greet}} / [{{>*which}}]"
                   (mustache:make-context
                    :data '((:name . "Ronto") (:which . "b"))
                    :partials '(("greet" . "Hello, {{name}}") ("a" . "A")
                                ("b" . "B")))))
(terpri)

;; A function in the context is a lambda section: it receives the raw section
;; text and its result is rendered as a template in turn.
(princ
 (mustache:render* "{{#shout}}hello {{name}}{{/shout}}"
                   (list (cons :name "world")
                         (cons :shout (lambda (text) (string-upcase text))))))
(terpri)

;; compile-template parses once and returns a renderer; mustache:define binds
;; that renderer to a name.
(let ((row (mustache:compile-template "| {{a}} | {{b}} |")))
  (princ (with-output-to-string (out) (funcall row '((:a . 1) (:b . 2)) out)))
  (terpri)
  (princ (with-output-to-string (out) (funcall row '((:a . 3) (:b . 4)) out)))
  (terpri))

(mustache:define banner "== {{title}} ==")
(princ
 (with-output-to-string (mustache:*output-stream*)
   (banner '((:title . "done")))))
(terpri)

;; A partial that cannot be found signals mustache:partial-cant-be-found with a
;; use-value restart, so a handler can substitute a template instead.
(princ
 (handler-bind ((mustache:partial-cant-be-found
                 (lambda (c)
                   (declare (ignore c))
                   (use-value "{{fallback}}"))))
   (mustache:render* "<{{>missing}}>"
                     (mustache:make-context :data '((:fallback . "substituted"))
                                            :partials nil))))
(terpri)

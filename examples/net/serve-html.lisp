;;;; Serves ONE HTML page over HTTP: every request answers with the page as
;;;; text/html. The page is embedded at READ time -- the #. form in
;;;; page-html.lisp is evaluated while the source is read (on the compile host
;;;; for the JVM/WASM paths), so the program carries the page as a literal and
;;;; needs NO runtime capabilities: no filesystem, no environment, no
;;;; arguments. The slurp lives in page-html.lisp rather than here because a
;;;; #. reads its own file's load context and the ENTRY file has none: (load)
;;;; splices page-html.lisp with its context bracketed, so the default
;;;; page.html resolves beside the sources wherever the compile runs. Point
;;;; the HTML_FILE path at the file to serve and recompile/restart; the
;;;; override is working-directory-relative, the default is source-relative.
;;;;
;;;; The handler is the Clack shape (env plist in, (status headers body) out)
;;;; served by rontolisp:http-handler -- no socket code of our own, request
;;;; parsing and framing are the transport's.
;;;;
;;;; Run (interpreter):        java -jar $JAR examples/net/serve-html.lisp
;;;; Run (JVM):                java -jar $JAR examples/net/serve-html.lisp -o ServeHtml.class && java -cp . ServeHtml
;;;; Run (WASM component):     java -jar $JAR examples/net/serve-html.lisp -o serve-html.wasm --component && wasmtime serve serve-html.wasm
;;;; Talk to it with:          curl http://127.0.0.1:8080/   (or a browser)

;; The page, as a literal: (load) splices page-html.lisp inline on the compile
;; paths (defining *html* there, no runtime file read) and loads it at run
;; time on the interpreter.
(load "page-html.lisp")

(defun handle (env)
  (declare (ignore env))
  (list 200 '(:content-type "text/html; charset=utf-8") (list *html*)))

;; Blocks and serves on :8080 (interpreter / JVM); under --component the port
;; is ignored (wasmtime owns the socket) and the handler is served through the
;; wasi:http interface -- the component imports nothing beyond it.
(rontolisp:http-handler 'handle 8080)

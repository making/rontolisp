;;;; Serves ONE HTML page over HTTP: every request answers with the page as
;;;; text/html. The page is embedded at READ time -- the #. form is evaluated
;;;; while the source is read (on the compile host for the JVM/WASM paths), so
;;;; the program carries the page as a literal and needs NO runtime
;;;; capabilities: no filesystem, no environment, no arguments. Point the #.
;;;; path at the file to serve and recompile/restart; the path is relative to
;;;; the working directory.
;;;;
;;;; The handler is the Clack shape (env plist in, (status headers body) out)
;;;; served by rontolisp:http-handler -- no socket code of our own, request
;;;; parsing and framing are the transport's.
;;;;
;;;; Run (interpreter):        java -jar $JAR examples/net/serve-html.lisp
;;;; Run (JVM):                java -jar $JAR examples/net/serve-html.lisp -o ServeHtml.class && java -cp . ServeHtml
;;;; Run (WASM component):     java -jar $JAR examples/net/serve-html.lisp -o serve-html.wasm --component && wasmtime serve serve-html.wasm
;;;; Talk to it with:          curl http://127.0.0.1:8080/   (or a browser)

(defparameter *html*
  ;; Read at READ time: on the compile path the file is slurped on the build
  ;; host, so the page travels inside the artifact as a literal. Pick the file
  ;; with HTML_FILE at BUILD time (default "page.html"); the artifact keeps
  ;; NO runtime filesystem/environment capability.
  #.(uiop:read-file-string (or (uiop:getenv "HTML_FILE") "page.html")))

(defun handle (env)
  (declare (ignore env))
  (list 200 '(:content-type "text/html; charset=utf-8")
        (list *html*)))

;; Blocks and serves on :8080 (interpreter / JVM); under --component the port
;; is ignored (wasmtime owns the socket) and the handler is served through the
;; wasi:http interface -- the component imports nothing beyond it.
(rontolisp:http-handler 'handle 8080)

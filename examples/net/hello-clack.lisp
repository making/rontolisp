;; The smallest Clack application, on every host rontolisp compiles for.
;;
;;   rontolisp examples/net/hello-clack.lisp                            # :8080
;;   PORT=3000 rontolisp examples/net/hello-clack.lisp                  # :3000
;;   rontolisp examples/net/hello-clack.lisp -o App.class && java -cp . App
;;   rontolisp examples/net/hello-clack.lisp -o app.jar && java -jar app.jar
;;   rontolisp examples/net/hello-clack.lisp -o app.war                 # Servlet 6 container
;;   rontolisp examples/net/hello-clack.lisp -o app.wasm --component && \
;;     wasmtime serve -S cli=y app.wasm
;;   rontolisp examples/net/hello-clack.lisp -o worker.wasm --no-wasi   # Cloudflare Worker
;;
;;   curl http://127.0.0.1:8080/hello

(ql:quickload "clack")

(defun app (env)
  (list 200 '(:content-type "text/plain; charset=utf-8")
        (list
         (format nil "Hello from Clack on rontolisp!~%~a ~a~%"
                 (getf env :request-method) (getf env :path-info)))))

;; Works on the interpreter and the JDK HTTP server only.
(defun server-port ()
  (let ((value (uiop:getenvp "PORT"))) (if value (parse-integer value) 8080)))

(clack:clackup #'app :server :rontolisp :port (server-port) :use-thread nil)

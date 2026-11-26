;; Access logging the lack way: `lack:builder` composes middleware around a
;; Clack application, and `:accesslog` -- lack's own middleware, quickloaded
;; unpatched -- writes one Apache combined-format line per request to standard
;; output. The application itself is untouched; the log line reports the status
;; and the content length the application returned, which is why /missing is
;; logged as a 404 without the application knowing a logger exists.
;;
;;   rontolisp examples/net/hello-clack-accesslog.lisp                    # :8080
;;   PORT=3000 rontolisp examples/net/hello-clack-accesslog.lisp          # :3000
;;   rontolisp examples/net/hello-clack-accesslog.lisp -o App.class && java -cp . App
;;   rontolisp examples/net/hello-clack-accesslog.lisp -o app.jar && java -jar app.jar
;;   rontolisp examples/net/hello-clack-accesslog.lisp -o app.war         # Servlet 6 container
;;   rontolisp examples/net/hello-clack-accesslog.lisp -o app.wasm --component && \
;;     wasmtime serve -S cli=y app.wasm
;;
;;   curl http://127.0.0.1:8080/
;;   127.0.0.1 - [26/Aug/2026:20:55:01 +09:00] "GET / HTTP/1.1" 200 31 "-" "curl/8.7.1"
;;
;; Standard output is the transport's: the terminal on the interpreter and the
;; JVM, the container's log for -o app.war, `stdout [0] ::` under
;; `wasmtime serve` -- where the request carries no peer address, so the line
;; starts with NIL rather than an IP. `:logger` sends the line somewhere else and
;; `:formatter` changes its format -- both are ordinary keyword arguments of the
;; middleware, written as (:accesslog :logger f :formatter g) in the builder.
;;
;; lack-middleware-accesslog is quickloaded BY NAME because the :accesslog
;; keyword would otherwise load its system through quicklisp at RUN time, which
;; a compiled program has no way to do: it would compile cleanly and then fail
;; with `Middleware "LACK/MIDDLEWARE/ACCESSLOG" is not found`.

(ql:quickload '("clack" "lack-middleware-accesslog"))

(defun app (env)
  (let ((path (getf env :path-info)))
    (if (string= path "/")
        (list 200 '(:content-type "text/plain; charset=utf-8")
              (list (format nil "Hello from Clack on rontolisp!~%")))
        (list 404 '(:content-type "text/plain; charset=utf-8")
              (list (format nil "No such page: ~a~%" path))))))

;; Works on the interpreter and the JDK HTTP server only.
(defun server-port ()
  (let ((value (uiop:getenvp "PORT"))) (if value (parse-integer value) 8080)))

(clack:clackup (lack:builder :accesslog #'app)
               :server :rontolisp
               :address "0.0.0.0"
               :port (server-port)
               :use-thread nil)

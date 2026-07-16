;; Minimal HTTP/1.1 server on port 8080: answers every request with a small
;; HTML page showing the request line and a running request counter, one
;; connection per request (Connection: close). Serves forever -- stop with
;; Ctrl-C.
;;
;; The socket handle from rontolisp:tcp-accept is a line stream, and read-line
;; strips one trailing carriage return, so HTTP's CRLF-terminated request line
;; and headers read as plain lines (the blank line ending the headers reads as
;; ""). Response header lines get their carriage return back via code-char 13
;; before write-line appends the newline.
;;
;; Run (interpreter):        java -jar $JAR examples/net/http-hello.lisp
;; Run (JVM):                java -jar $JAR examples/net/http-hello.lisp -o HttpHello.class && java HttpHello
;; Run (WASM component):     java -jar $JAR examples/net/http-hello.lisp -o http-hello.wasm --component && \
;;                           wasmtime run -W gc=y \
;;                             -S tcp=y -S inherit-network=y http-hello.wasm
;; Talk to it with:          curl http://127.0.0.1:8080/   (or a browser)

;; Appends the carriage return of an HTTP CRLF line ending (write-line then
;; appends the newline).
(defun crlf (s)
  (concatenate 'string s (format nil "~a" (code-char 13))))

;; Consumes the request headers up to the blank line that ends them.
(defun drain-headers (sock)
  (do ((line (read-line sock) (read-line sock)))
      ((or (null line) (string= line "")))))

(let ((listener (rontolisp:tcp-listen 8080)))
  (if listener
      (progn
        (write-line "http server listening on http://127.0.0.1:8080/")
        (do ((n 1 (+ n 1))) (nil)
          (let* ((sock (rontolisp:tcp-accept listener))
                 (request (read-line sock)))
            (if request
                (let ((body (format nil "<h1>hello from rontolisp</h1><p>request ~a: ~a</p>" n request)))
                  (drain-headers sock)
                  (write-line (crlf "HTTP/1.1 200 OK") sock)
                  (write-line (crlf "Content-Type: text/html") sock)
                  ;; + 1: write-line terminates the body with a newline
                  (write-line (crlf (format nil "Content-Length: ~a" (+ (length body) 1))) sock)
                  (write-line (crlf "Connection: close") sock)
                  (write-line (crlf "") sock)
                  (write-line body sock)
                  (write-line (format nil "served request ~a: ~a" n request))))
            (close sock))))
      (write-line "tcp-listen failed (is port 8080 already in use?)")))

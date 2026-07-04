;; The TLS version of http-hello.lisp: a minimal HTTPS (HTTP/1.1 over TLS)
;; server on port 8443. Same behavior -- answers every request with a small
;; HTML page showing the request line and a running request counter, one
;; connection per request (Connection: close). Serves forever -- stop with
;; Ctrl-C.
;;
;; rontolisp:tls-listen needs a PKCS12 keystore holding the server key and
;; certificate. Generate a self-signed one for localhost with the JDK keytool
;; (or export one from openssl with `openssl pkcs12 -export`):
;;
;;   keytool -genkeypair -alias rontolisp-tls -keyalg EC -dname CN=localhost \
;;     -validity 365 -ext SAN=ip:127.0.0.1,dns:localhost \
;;     -storetype PKCS12 -keystore tls-server.p12 \
;;     -storepass changeit -keypass changeit
;;
;; The listener handle works with the plain rontolisp:tcp-accept, and each
;; accepted socket completes its TLS handshake on the first read -- everything
;; after tls-listen is identical to the plain-TCP http-hello.lisp.
;;
;; TLS is interpreter/JVM only (a compile error on the WASM backend).
;;
;; Run (interpreter):        java -jar $JAR examples/https-hello.lisp
;; Run (JVM):                java -jar $JAR examples/https-hello.lisp -o HttpsHello.class && java HttpsHello
;; Talk to it with:          curl -k https://127.0.0.1:8443/
;;                           (-k because the certificate is self-signed; or trust it
;;                           explicitly with --cacert after exporting the certificate)

;; Appends the carriage return of an HTTP CRLF line ending (write-line then
;; appends the newline).
(defun crlf (s)
  (concatenate 'string s (format nil "~a" (code-char 13))))

;; Consumes the request headers up to the blank line that ends them.
(defun drain-headers (sock)
  (do ((line (read-line sock) (read-line sock)))
      ((or (null line) (string= line "")))))

;; Unlike tcp-listen on the WASM backend, tls-listen never returns nil: a
;; missing keystore, a wrong password or a busy port signals an error instead
;; (interpreter and JVM both signal), so there is no nil check here.
(let ((listener (rontolisp:tls-listen "tls-server.p12" "changeit" 8443)))
  (write-line "https server listening on https://127.0.0.1:8443/")
  (do ((n 1 (+ n 1))) (nil)
    (let* ((sock (rontolisp:tcp-accept listener))
           (request (read-line sock)))
      (if request
          (let ((body (format nil "<h1>hello from rontolisp over TLS</h1><p>request ~a: ~a</p>" n request)))
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

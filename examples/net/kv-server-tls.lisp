;; The TLS version of kv-server.lisp: the same miniature Redis-compatible
;; in-memory key-value server, but serving TLS on port 6380 (like a real
;; Redis with --tls-port). The RESP2 protocol handling is identical -- only
;; the listen call differs: rontolisp:tls-listen wraps the listener in TLS,
;; the plain rontolisp:tcp-accept accepts connections, and each accepted
;; socket completes its handshake on the first read.
;;
;; See kv-server.lisp for the supported commands and protocol notes.
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
;; TLS is interpreter/JVM only (a compile error on the WASM backend).
;;
;; Run (interpreter):        java -jar $JAR examples/net/kv-server-tls.lisp
;; Run (JVM):                java -jar $JAR examples/net/kv-server-tls.lisp -o KvServerTls.class && java KvServerTls
;; Talk to it with:          redis-cli --tls --insecure -p 6380 set greeting hello
;;                           redis-cli --tls --insecure -p 6380 get greeting
;;                           (--insecure because the certificate is self-signed; or
;;                           trust it explicitly with --cacert after exporting it)

;; --- small string helpers ---------------------------------------------------

;; Appends the carriage return of a RESP CRLF line ending (write-line then
;; appends the newline).
(defun crlf (s)
  (concatenate 'string s (format nil "~a" (code-char 13))))

;; "SET key value" -> ("SET" "key" "value")
(defun split-words (s)
  (cond ((string= s "") nil)
        (t (let ((p (position #\space s)))
             (if p
                 (cons (subseq s 0 p) (split-words (subseq s (+ p 1))))
                 (list s))))))

;; ("hello" "world") -> "hello world"
(defun join-words (ws)
  (cond ((null ws) "")
        ((null (cdr ws)) (car ws))
        (t (concatenate 'string (car ws) " " (join-words (cdr ws))))))

;; t when s is a non-empty run of decimal digits (with an optional leading -).
(defun integer-string-p (s)
  (let* ((n (length s))
         (start (if (and (> n 0) (char= (char s 0) #\-)) 1 0)))
    (and (> n start)
         (do ((i start (+ i 1)))
             ((or (>= i n) (not (digit-char-p (char s i))))
              (>= i n))))))

;; --- RESP replies -----------------------------------------------------------

(defun reply-simple (s sock)
  (write-line (crlf (concatenate 'string "+" s)) sock))

(defun reply-error (s sock)
  (write-line (crlf (concatenate 'string "-ERR " s)) sock))

(defun reply-int (n sock)
  (write-line (crlf (format nil ":~a" n)) sock))

(defun reply-bulk (s sock)
  (if s
      (progn
        (write-line (crlf (format nil "$~a" (length s))) sock)
        (write-line (crlf s) sock))
      (write-line (crlf "$-1") sock)))

(defun reply-array-header (n sock)
  (write-line (crlf (format nil "*~a" n)) sock))

;; --- request framing --------------------------------------------------------

;; Reads one RESP bulk-string element: the "$<len>" header line, then the
;; payload line (the payload must not contain a newline).
(defun read-bulk (sock)
  (let ((header (read-line sock)))
    (if header (read-line sock) nil)))

(defun read-resp-array (count sock acc)
  (if (<= count 0)
      (reverse acc)
      (let ((arg (read-bulk sock)))
        (if arg
            (read-resp-array (- count 1) sock (cons arg acc))
            nil))))

;; Reads one command as a list of argument strings: a "*<n>" line starts a
;; RESP2 array (what redis-cli sends); anything else is an inline command
;; (what telnet/nc users type). nil at connection close.
(defun read-command (sock)
  (let ((line (read-line sock)))
    (cond ((null line) nil)
          ((string= line "") (read-command sock))
          ((char= (char line 0) #\*)
           (let ((count (subseq line 1)))
             (if (integer-string-p count)
                 (read-resp-array (parse-integer count) sock nil)
                 (list "!bad-frame"))))
          (t (split-words line)))))

;; --- commands ---------------------------------------------------------------

;; Handles one command; returns nil after QUIT (closing the session).
(defun handle-command (args store sock)
  (let ((cmd (string-upcase (car args)))
        (key (cadr args)))
    (cond ((string= cmd "PING")
           (if key (reply-bulk key sock) (reply-simple "PONG" sock))
           t)
          ((string= cmd "SET")
           (if (and key (cddr args))
               (progn
                 (setf (gethash key store) (join-words (cddr args)))
                 (reply-simple "OK" sock))
               (reply-error "wrong number of arguments for 'set' command" sock))
           t)
          ((string= cmd "GET")
           (if key
               (reply-bulk (gethash key store) sock)
               (reply-error "wrong number of arguments for 'get' command" sock))
           t)
          ((string= cmd "DEL")
           (let ((removed 0))
             (dolist (k (cdr args))
               (when (gethash k store)
                 (remhash k store)
                 (incf removed)))
             (reply-int removed sock))
           t)
          ((string= cmd "EXISTS")
           (reply-int (if (and key (gethash key store)) 1 0) sock)
           t)
          ((string= cmd "INCR")
           (let ((current (if key (or (gethash key store) "0") "0")))
             (cond ((null key)
                    (reply-error "wrong number of arguments for 'incr' command" sock))
                   ((integer-string-p current)
                    (let ((n (+ (parse-integer current) 1)))
                      (setf (gethash key store) (format nil "~a" n))
                      (reply-int n sock)))
                   (t (reply-error "value is not an integer or out of range" sock))))
           t)
          ((string= cmd "KEYS")
           (let ((pattern (or key "*"))
                 (keys nil))
             (maphash (lambda (k v)
                        (if (or (string= pattern "*") (string= pattern k))
                            (push k keys)))
                      store)
             (reply-array-header (length keys) sock)
             (dolist (k keys)
               (reply-bulk k sock)))
           t)
          ((string= cmd "DBSIZE")
           (reply-int (hash-table-count store) sock)
           t)
          ((string= cmd "COMMAND")
           ;; redis-cli asks COMMAND DOCS on connect; an empty array satisfies it.
           (reply-array-header 0 sock)
           t)
          ((string= cmd "QUIT")
           (reply-simple "OK" sock)
           nil)
          (t (reply-error (format nil "unknown command '~a'" (car args)) sock)
             t))))

;; --- server loop ------------------------------------------------------------

;; Unlike tcp-listen on the WASM backend, tls-listen never returns nil: a
;; missing keystore, a wrong password or a busy port signals an error instead
;; (interpreter and JVM both signal), so there is no nil check here.
(let ((store (make-hash-table))
      (listener (rontolisp:tls-listen "tls-server.p12" "changeit" 6380)))
  (write-line "mini-redis (TLS) listening on 127.0.0.1:6380 (try: redis-cli --tls --insecure -p 6380 ping)")
  (do ((n 1 (+ n 1))) (nil)
    (let ((sock (rontolisp:tcp-accept listener)))
      (do ((args (read-command sock) (read-command sock)))
          ((or (null args) (not (handle-command args store sock)))
           (close sock))))))

;; A miniature Redis-compatible in-memory key-value server on port 6379.
;;
;; It speaks enough of RESP2 (the Redis serialization protocol) that the real
;; `redis-cli` connects and works, and -- like real Redis -- it also accepts
;; "inline commands" (a plain space-separated line), so `telnet 127.0.0.1 6379`
;; or `nc 127.0.0.1 6379` work too. Both framings arrive as CRLF-terminated
;; lines, which read-line reads as plain lines (one trailing carriage return is
;; stripped on every backend).
;;
;; Supported commands (case-insensitive):
;;   PING [msg]         -> +PONG or the message echoed back
;;   SET <key> <value>  -> +OK        (inline mode joins the rest of the line)
;;   GET <key>          -> the value as a bulk string, or nil ($-1)
;;   DEL <key> [...]    -> :n removed
;;   EXISTS <key>       -> :0 / :1
;;   INCR <key>         -> :n (the value must be an integer; missing counts as 0)
;;   KEYS <pattern>     -> array of keys ("*" lists all; anything else matches exactly)
;;   DBSIZE             -> :n
;;   QUIT               -> +OK, closes the connection (the server keeps running)
;; The store is a hash table with string keys that survives across connections.
;; Values are treated as ASCII (bulk-string lengths count characters, and a
;; value must not contain a newline). Stop the server with Ctrl-C.
;;
;; Run (interpreter):        java -jar $JAR examples/net/kv-server.lisp
;; Run (JVM):                java -jar $JAR examples/net/kv-server.lisp -o KvServer.class && java KvServer
;; Run (WASM component):     java -jar $JAR examples/net/kv-server.lisp -o kv-server.wasm --component && \
;;                           wasmtime run -W gc=y -W component-model-async=y \
;;                             -W component-model-async-stackful=y -W component-model-more-async-builtins=y \
;;                             -S tcp=y -S inherit-network=y kv-server.wasm
;; Talk to it with:          redis-cli -p 6379 set greeting hello
;;                           redis-cli -p 6379 get greeting
;;                           telnet 127.0.0.1 6379   (then type: GET greeting)

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

(let ((store (make-hash-table))
      (listener (rontolisp:tcp-listen 6379)))
  (if listener
      (progn
        (write-line "mini-redis listening on 127.0.0.1:6379 (try: redis-cli -p 6379 ping)")
        (do ((n 1 (+ n 1))) (nil)
          (let ((sock (rontolisp:tcp-accept listener)))
            (do ((args (read-command sock) (read-command sock)))
                ((or (null args) (not (handle-command args store sock)))
                 (close sock))))))
      (write-line "tcp-listen failed (is port 6379 already in use? a real redis, perhaps)")))

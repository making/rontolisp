;; In-memory key-value store served over a line protocol on port 7777.
;; Commands (one per line):
;;   SET <key> <value...>   -> OK
;;   GET <key>              -> the value, or (nil)
;;   DEL <key>              -> OK
;;   COUNT                  -> number of keys
;;   QUIT                   -> BYE (closes the connection; the server keeps running)
;; The store is a hash table with string keys that lives across connections:
;; SET in one session, reconnect, GET it back. Stop the server with Ctrl-C.
;;
;; Run (interpreter):        java -jar $JAR examples/kv-server.lisp
;; Run (JVM):                java -jar $JAR examples/kv-server.lisp -o KvServer.class && java KvServer
;; Run (WASM component):     java -jar $JAR examples/kv-server.lisp -o kv-server.wasm --component && \
;;                           wasmtime run -W gc=y -W component-model-async=y \
;;                             -W component-model-async-stackful=y -W component-model-more-async-builtins=y \
;;                             -S tcp=y -S inherit-network=y kv-server.wasm
;; Talk to it with nc 127.0.0.1 7777, or reuse examples/echo-client.lisp (it
;; targets the same port and prints one reply per stdin line):
;;   printf 'SET greeting hello world\nGET greeting\nCOUNT\nQUIT\n' | \
;;     java -jar $JAR examples/echo-client.lisp

;; Index of the first space in s, or nil (position does not search strings).
(defun space-pos (s)
  (let ((n (length s)))
    (do ((i 0 (+ i 1)))
        ((or (>= i n) (char= (char s i) #\space))
         (if (>= i n) nil i)))))

;; "SET key value" -> "SET"
(defun head-word (s)
  (let ((p (space-pos s)))
    (if p (subseq s 0 p) s)))

;; "SET key value" -> "key value"
(defun tail-words (s)
  (let ((p (space-pos s)))
    (if p (subseq s (+ p 1)) "")))

(defun handle-command (line store sock)
  (let ((cmd (string-upcase (head-word line)))
        (args (tail-words line)))
    (cond ((string= cmd "SET")
           (let ((key (head-word args))
                 (value (tail-words args)))
             (if (string= key "")
                 (write-line "ERR usage: SET <key> <value>" sock)
                 (progn
                   (setf (gethash key store) value)
                   (write-line "OK" sock)))))
          ((string= cmd "GET")
           (let ((value (gethash args store)))
             (write-line (if value value "(nil)") sock)))
          ((string= cmd "DEL")
           (remhash args store)
           (write-line "OK" sock))
          ((string= cmd "COUNT")
           (write-line (format nil "~a" (hash-table-count store)) sock))
          (t (write-line "ERR unknown command (SET/GET/DEL/COUNT/QUIT)" sock)))))

(let ((store (make-hash-table))
      (listener (rontolisp:tcp-listen 7777)))
  (if listener
      (progn
        (write-line "kv server listening on 127.0.0.1:7777 (SET/GET/DEL/COUNT/QUIT)")
        (do ((n 1 (+ n 1))) (nil)
          (let ((sock (rontolisp:tcp-accept listener)))
            (do ((line (read-line sock) (read-line sock)))
                ((or (null line) (string= (string-upcase (head-word line)) "QUIT"))
                 (if line (write-line "BYE" sock))
                 (close sock))
              (handle-command line store sock)))))
      (write-line "tcp-listen failed (is port 7777 already in use?)")))

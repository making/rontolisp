;; A rontolisp port of the service half of wasmCloud's service-tcp template
;; (templates/service-tcp/service-leet in the wasmCloud repo): a long-running
;; TCP server that accepts connections on 127.0.0.1:7777, reads lines and
;; replies with the leet-speak transformation of each line.
;;
;; Runs on the interpreter, the JVM, as a WASI component under wasmtime run,
;; and on wasmCloud as a v2 SERVICE: compiled with --component the program
;; exports wasi:cli/run@0.3.0, which is exactly the shape wash's service
;; model hosts (this directory's .wash/config.yaml registers the build as
;; dev.service_file, so one `wash dev` runs both halves). On wasmCloud the
;; listener binds the workload's in-process virtual loopback, reachable only
;; by components in the same workload -- and the listen host must be the
;; explicit "127.0.0.1" below: wash's bind policy rejects the 0.0.0.0
;; default before its loopback rewrite.
;;
;; Connections are served one at a time (accept returns to the loop when the
;; client closes), which is enough for the per-request open/close pattern of
;; http-api.lisp. Stop it with Ctrl-C.
;;
;; Run (interpreter):
;;   rontolisp examples/wasmcloud/service-tcp/service-leet.lisp
;; Run (JVM):
;;   rontolisp examples/wasmcloud/service-tcp/service-leet.lisp -o ServiceLeet.class && \
;;     java ServiceLeet
;; Run (WASI component under wasmtime run):
;;   rontolisp examples/wasmcloud/service-tcp/service-leet.lisp -o service-leet.wasm --component && \
;;     wasmtime run -W gc=y -W exceptions=y -S tcp=y -S inherit-network=y service-leet.wasm
;; Run (wasmCloud, both halves in one host):
;;   cd examples/wasmcloud/service-tcp && wash dev
;; Talk to it with:
;;   nc 127.0.0.1 7777      (type a line, read it back in leet speak)

;; The same mapping as the original: match on the downcased character, but a
;; character that maps to itself keeps its original case ("Hello World" ->
;; "H3110 W0r1d").
(defun leet-char (c)
  (let ((lc (char-downcase c)))
    (cond ((char= lc #\a) #\4)
          ((char= lc #\e) #\3)
          ((char= lc #\i) #\1)
          ((char= lc #\o) #\0)
          ((char= lc #\s) #\5)
          ((char= lc #\t) #\7)
          ((char= lc #\l) #\1)
          (t c))))

(defun to-leet-speak (s) (map 'string #'leet-char s))

;; The explicit loopback host matters on wasmCloud (wash rejects a 0.0.0.0
;; bind from a service); everywhere else it just narrows the listener.
(let ((listener (rontolisp:tcp-listen 7777 "127.0.0.1")))
  (if listener
      (progn
        (write-line "leet service listening on 127.0.0.1:7777")
        (do ((n 1 (+ n 1)))
            (nil)
          (let ((sock (rontolisp:tcp-accept listener)))
            (write-line (format nil "client ~a connected" n))
            (do ((line (read-line sock) (read-line sock)))
                ((null line)
                 (close sock)
                 (write-line "client disconnected"))
              (write-line (to-leet-speak line) sock)))))
      (write-line "tcp-listen failed (is port 7777 already in use?)")))

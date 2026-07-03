;; TCP echo server: listens on port 7777 and echoes every received line back to
;; the client, one connection at a time, until the client closes (read-line
;; returns nil at peer close). Serves connections forever -- stop it with Ctrl-C.
;;
;; A socket handle returned by rontolisp:tcp-accept works with the standard
;; stream functions: read-line / write-line / close. On the WASM component
;; backend a failed tcp-listen returns nil instead of signaling, so the result
;; is checked before entering the accept loop.
;;
;; Run (interpreter):        java -jar $JAR examples/echo-server.lisp
;; Run (JVM):                java -jar $JAR examples/echo-server.lisp -o EchoServer.class && java EchoServer
;; Run (WASM component):     java -jar $JAR examples/echo-server.lisp -o echo-server.wasm --component && \
;;                           wasmtime run -W gc=y -W component-model-async=y \
;;                             -W component-model-async-stackful=y -W component-model-more-async-builtins=y \
;;                             -S tcp=y -S inherit-network=y echo-server.wasm
;; Talk to it with:          nc 127.0.0.1 7777   (or examples/echo-client.lisp)
(let ((listener (rontolisp:tcp-listen 7777)))
  (if listener
      (progn
        (write-line "echo server listening on 127.0.0.1:7777")
        (do ((n 1 (+ n 1))) (nil)
          (let ((sock (rontolisp:tcp-accept listener)))
            (write-line (format nil "client ~a connected" n))
            (do ((line (read-line sock) (read-line sock)))
                ((null line) (close sock) (write-line "client disconnected"))
              (write-line line sock)))))
      (write-line "tcp-listen failed (is port 7777 already in use?)")))

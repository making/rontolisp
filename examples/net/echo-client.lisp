;; TCP echo client: connects to the echo server on 127.0.0.1:7777, sends every
;; line read from standard input and prints the server's reply, until stdin ends.
;;
;; A socket handle returned by rontolisp:tcp-connect works with the standard
;; stream functions: read-line / write-line / close. On the WASM component
;; backend a failed connect returns nil instead of signaling, so the result is
;; checked before use.
;;
;; Start examples/net/echo-server.lisp first (any backend), then:
;; Run (interpreter):        echo hello | java -jar $JAR examples/net/echo-client.lisp
;; Run (JVM):                java -jar $JAR examples/net/echo-client.lisp -o EchoClient.class && \
;;                           echo hello | java EchoClient
;; Run (WASM component):     java -jar $JAR examples/net/echo-client.lisp -o echo-client.wasm --component && \
;;                           echo hello | wasmtime run -W gc=y -W component-model-async=y \
;;                             -W component-model-async-stackful=y -W component-model-more-async-builtins=y \
;;                             -S tcp=y -S inherit-network=y echo-client.wasm
(let ((sock (rontolisp:tcp-connect "127.0.0.1" 7777)))
  (if sock
      (do ((line (read-line) (read-line)))
          ((null line) (close sock))
        (write-line line sock)
        (write-line (read-line sock)))
      (write-line "cannot connect to 127.0.0.1:7777 (is echo-server.lisp running?)")))

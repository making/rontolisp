;; TCP echo client: connects to the echo server on 127.0.0.1:7777, sends every
;; line read from standard input and prints the server's reply, until stdin ends.
;;
;; Networking goes through the portable usocket API: with-client-socket
;; connects, binds a stream, and closes it on exit; the stream works with the
;; standard functions read-line / write-line. usocket:socket-connect signals
;; usocket:socket-error on failure (no server listening), caught below. The
;; API is a shim over the built-in rontolisp:tcp-* functions, so the same
;; source runs on the interpreter, the JVM, and a WASM component.
;;
;; Start examples/net/echo-server.lisp first (any backend), then:
;; Run (interpreter):        echo hello | java -jar $JAR examples/net/echo-client.lisp
;; Run (JVM):                java -jar $JAR examples/net/echo-client.lisp -o EchoClient.class && \
;;                           echo hello | java EchoClient
;; Run (WASM component):     java -jar $JAR examples/net/echo-client.lisp -o echo-client.wasm --component && \
;;                           echo hello | wasmtime run -W gc=y -W exceptions=y \
;;                             -S tcp=y -S inherit-network=y echo-client.wasm
(handler-case
    (usocket:with-client-socket (sock stream "127.0.0.1" 7777)
      (do ((line (read-line) (read-line)))
          ((null line))
        (write-line line stream)
        (write-line (read-line stream))))
  (usocket:socket-error (e)
    (declare (ignore e))
    (write-line "cannot connect to 127.0.0.1:7777 (is echo-server.lisp running?)")))

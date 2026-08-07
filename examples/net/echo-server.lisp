;; TCP echo server: listens on port 7777 and echoes every received line back to
;; the client, one connection at a time, until the client closes (read-line
;; returns nil at peer close). Serves connections forever -- stop it with Ctrl-C.
;;
;; Networking goes through the portable usocket API (socket-listen /
;; socket-accept / socket-stream and the with-server-socket macro), a shim over
;; the built-in rontolisp:tcp-* functions, so the same source runs on the
;; interpreter, the JVM, and a WASM component. A socket's stream works with the
;; standard functions: read-line / write-line / close. usocket:socket-listen
;; signals usocket:socket-error on failure (a busy port), caught below.
;;
;; Run (interpreter):        java -jar $JAR examples/net/echo-server.lisp
;; Run (JVM):                java -jar $JAR examples/net/echo-server.lisp -o EchoServer.class && java EchoServer
;; Run (WASM component):     java -jar $JAR examples/net/echo-server.lisp -o echo-server.wasm --component && \
;;                           wasmtime run -W gc=y -W exceptions=y \
;;                             -S tcp=y -S inherit-network=y echo-server.wasm
;; Talk to it with:          nc 127.0.0.1 7777   (or examples/net/echo-client.lisp)
(handler-case (let ((listener
                     (usocket:socket-listen "127.0.0.1" 7777 :reuse-address t)))
                (write-line "echo server listening on 127.0.0.1:7777")
                (do ((n 1 (+ n 1)))
                    (nil)
                  ;; with-server-socket closes the accepted socket on every exit.
                  (usocket:with-server-socket (sock
                                               (usocket:socket-accept listener))
                    (let ((stream (usocket:socket-stream sock)))
                      (write-line (format nil "client ~a connected" n))
                      (do ((line (read-line stream) (read-line stream)))
                          ((null line) (write-line "client disconnected"))
                        (write-line line stream))))))
  (usocket:socket-error (e)
    (declare (ignore e))
    (write-line "socket-listen failed (is port 7777 already in use?)")))

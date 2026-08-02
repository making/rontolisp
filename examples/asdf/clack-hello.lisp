;;;; Clack via ql:quickload -- clackup on the built-in rontolisp handler backend
;;;; Loads the REAL clack (unmodified upstream sources; Eitaro Fukamachi, MIT)
;;;; together with lack, and serves a Clack application through clack:clackup.
;;;; The :server :rontolisp backend is the built-in clack-handler-rontolisp
;;;; shim system, resolved by name at run time exactly the way clack finds any
;;;; handler backend. :use-thread nil keeps the process serving in the
;;;; foreground (Ctrl-C to stop), the script shape; in a REPL-style program the
;;;; default :use-thread t returns a handler object instead and
;;;; (clack:stop handler) shuts the server down.
;;;;
;;;; Run (downloads clack/lack into ~/.rontolisp/quicklisp on the first run):
;;;;   rontolisp examples/asdf/clack-hello.lisp
;;;;   curl http://127.0.0.1:5000/hello
;;;;
;;;; JVM (the served program needs the rontolisp jar on the runtime classpath):
;;;;   rontolisp examples/asdf/clack-hello.lisp -o ClackHello.class
;;;;   java -cp rontolisp-exec.jar:. ClackHello
;;;;
;;;; WASM component (the host owns the socket; the port argument is ignored):
;;;;   rontolisp examples/asdf/clack-hello.lisp -o clack-hello.wasm --component
;;;;   wasmtime serve -W gc=y -W exceptions=y -S cli=y -S tcp=y -S inherit-network=y clack-hello.wasm
;;;;
;;;; WASM Preview 1 has no incoming TCP by design: the program compiles, and
;;;; clackup signals "HTTP-HANDLER requires --component ..." at run time.

(ql:quickload "clack")

(clack:clackup
 (lambda (env)
   (list 200 '(:content-type "text/plain")
         (list (format nil "Hello, Clack on rontolisp! ~A ~A~%"
                       (getf env :request-method) (getf env :path-info)))))
 :server :rontolisp
 :port 5000
 :use-thread nil)

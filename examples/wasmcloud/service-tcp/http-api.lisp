;; A rontolisp port of the component half of wasmCloud's service-tcp template
;; (templates/service-tcp/http-api in the wasmCloud repo): an HTTP API that
;; forwards text to the leet TCP service (service-leet.lisp, listening on
;; 127.0.0.1:7777) and returns the transformed result.
;;
;;   GET  /      -> a short usage text (the original serves an HTML UI)
;;   POST /task  {"payload":"..."}  -> the payload in leet speak
;;   unknown path -> 404 "Not found"
;;
;; Works everywhere, wasmCloud included. Under wasmtime serve note the extra
;; -S cli=y, without which the serve linker reports the tcp-socket resource
;; as missing. Under `wash dev` 127.0.0.1 names the workload's in-process
;; virtual loopback, not the machine's -- so the leet service must run
;; INSIDE wasmCloud too (service-leet.lisp deployed as a v2 service; this
;; directory's .wash/config.yaml builds and registers both halves), while a
;; leet service running as a host process is unreachable from the component.
;; Non-loopback addresses connect over the real network.
;;
;; Run (start service-leet.lisp first; then, interpreter):
;;   rontolisp examples/wasmcloud/service-tcp/http-api.lisp
;; Run (JVM class; running it needs the rontolisp jar on the classpath):
;;   rontolisp examples/wasmcloud/service-tcp/http-api.lisp -o HttpApi.class && \
;;     java -cp rontolisp-0.1.0-SNAPSHOT-exec.jar:. HttpApi
;; Run (WASI component under wasmtime serve):
;;   rontolisp examples/wasmcloud/service-tcp/http-api.lisp -o http-api.wasm --component && \
;;     wasmtime serve -W gc=y -W exceptions=y -S cli=y -S tcp=y -S inherit-network=y http-api.wasm
;; Run (wasmCloud, both halves in one host; serves on :8000):
;;   cd examples/wasmcloud/service-tcp && wash dev
;; Talk to it with:
;;   curl -X POST -d '{"payload":"Hello World"}' http://127.0.0.1:8080/task
;;   -> H3110 W0r1d

;; t when the body looks like a JSON object (json-parse signals on garbage;
;; the cheap guard answers 400 without wrapping the parse in handler-case).
(defun json-object-p (body)
  (and (stringp body) (> (length body) 0) (eql (char body 0) #\{)))

(defun text-response (status body)
  (list status '(:content-type "text/plain") (list body)))

;; One round trip to the leet service: send the payload as a line, read the
;; transformed line back. A refused connection signals an error on the
;; interpreter/JVM but yields a nil sock on the WASM backend, so the nil
;; guard answers 502 there instead of trapping on (read-line nil); a nil
;; reply (service closed early) maps to 502 as well.
(defun leet-request (payload)
  (let ((sock (rontolisp:tcp-connect "127.0.0.1" 7777)))
    (if sock
        (progn
          (write-line payload sock)
          (let ((reply (read-line sock)))
            (close sock)
            reply))
        nil)))

(defun handle-task (env)
  (let ((body (getf env :body)))
    (if (json-object-p body)
        (let ((payload (gethash "payload" (rontolisp:json-parse body))))
          (if (stringp payload)
              (let ((reply (leet-request payload)))
                (if reply
                    (text-response 200 (format nil "~a~%" reply))
                    (text-response 502
                                   (format nil "leet service unavailable~%"))))
              (text-response 400
               (format nil
                "expected a JSON object with a string payload field~%"))))
        (text-response 400
         (format nil "expected a JSON object with a string payload field~%")))))

(defun home (env)
  (text-response 200
   (format nil
    "POST /task with {\"payload\":\"...\"} to get it back in leet speak~%")))

;; The env plist's :path-info carries the (percent-decoded) path only (any
;; query string arrives separately as :query-string), so the comparisons are
;; exact; :request-method is an interned keyword, so the comparison is eq.
(defun route (env)
  (let ((path (getf env :path-info)))
    (cond ((string= path "/") (home env))
          ((string= path "/task")
           (if (eq (getf env :request-method) :POST)
               (handle-task env)
               (text-response 405 (format nil "Method Not Allowed~%"))))
          (t (text-response 404 (format nil "Not found~%"))))))

;; The env :raw-body is an asynchronous stream on every backend; drain it once
;; here and hand the helpers an env whose :body is the whole string (getf
;; finds the prepended pair first).
(rontolisp:async-defun handle (env)
  (let ((body (rontolisp:await (rontolisp:read-all (getf env :raw-body)))))
    (route (append (list :body body) env))))

;; Blocks and serves on port 8080.
(rontolisp:http-handler 'handle 8080)

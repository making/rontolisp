;; A rontolisp port of the component half of wasmCloud's service-tcp template
;; (templates/service-tcp/http-api in the wasmCloud repo): an HTTP API that
;; forwards text to the leet TCP service (service-leet.lisp, listening on
;; 127.0.0.1:7777) and returns the transformed result.
;;
;;   GET  /      -> a short usage text (the original serves an HTML UI)
;;   POST /task  {"payload":"..."}  -> the payload in leet speak
;;   unknown path -> 404 "Not found"
;;
;; Interpreter / JVM only: serving and the tcp built-ins cannot be combined
;; in one --component binary -- see .todo/53-wasmcloud-template-gaps.md.
;;
;; Run (start service-leet.lisp first; then, interpreter):
;;   rontolisp examples/wasmcloud/service-tcp/http-api.lisp
;; Run (JVM class; running it needs the rontolisp jar on the classpath):
;;   rontolisp examples/wasmcloud/service-tcp/http-api.lisp -o HttpApi.class && \
;;     java -cp rontolisp-0.1.0-SNAPSHOT-exec.jar:. HttpApi
;; Talk to it with:
;;   curl -X POST -d '{"payload":"Hello World"}' http://127.0.0.1:8080/task
;;   -> H3110 W0r1d

;; t when the body looks like a JSON object (json-parse signals on garbage,
;; and rontolisp has no condition handling to recover from that).
(defun json-object-p (body)
  (and (stringp body) (> (length body) 0) (eql (char body 0) #\{)))

(defun text-response (status body)
  (list :status status
        :headers (list (cons "content-type" "text/plain"))
        :body body))

;; One round trip to the leet service: send the payload as a line, read the
;; transformed line back. A refused connection signals an error on the
;; interpreter/JVM, so the service must be running; a nil reply (service
;; closed early) maps to 502.
(defun leet-request (payload)
  (let ((sock (rontolisp:tcp-connect "127.0.0.1" 7777)))
    (write-line payload sock)
    (let ((reply (read-line sock)))
      (close sock)
      reply)))

(defun handle-task (request)
  (let ((body (getf request :body)))
    (if (json-object-p body)
        (let ((payload (getf (rontolisp:json-parse body) :payload)))
          (if (stringp payload)
              (let ((reply (leet-request payload)))
                (if reply
                    (text-response 200 (format nil "~a~%" reply))
                    (text-response 502 (format nil "leet service closed the connection~%"))))
              (text-response 400 (format nil "expected a JSON object with a string payload field~%"))))
        (text-response 400 (format nil "expected a JSON object with a string payload field~%")))))

(defun home (request)
  (text-response 200 (format nil "POST /task with {\"payload\":\"...\"} to get it back in leet speak~%")))

;; The request plist's :path carries the path only (any query string arrives
;; separately as :query), so the comparisons are exact.
(defun route (request)
  (let ((path (getf request :path)))
    (cond ((string= path "/") (home request))
          ((string= path "/task")
           (if (string= (getf request :method) "POST")
               (handle-task request)
               (text-response 405 (format nil "Method Not Allowed~%"))))
          (t (text-response 404 (format nil "Not found~%"))))))

;; The request :body is an asynchronous stream on every backend; drain it once
;; here and hand the helpers a request whose :body is the whole string (getf
;; finds the prepended pair first).
(rontolisp:async-defun handle (request)
  (let ((body (rontolisp:await (rontolisp:read-all (getf request :body)))))
    (route (append (list :body body) request))))

;; Blocks and serves on port 8080.
(rontolisp:http-handler 'handle 8080)

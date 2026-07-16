;; The Magic 8 Ball -- a rontolisp reproduction of the classic Spin tutorial
;; (https://spinframework.dev/ "Building a Magic 8 Ball JSON API"), on
;; rontolisp:http-handler. Ask it a yes/no question and it answers with one
;; of the twenty canonical Magic 8 Ball replies, drawn at random, as JSON:
;;
;;   GET  /?question=Will+it+work    -> {"question": "Will it work", "answer": "..."}
;;   POST /  with body               -> the body is the question -- either raw
;;                                      text, or JSON {"question": "..."}
;;   (also served on /magic-8, the tutorial's path; other paths 404,
;;    a missing question 400)
;;
;; Run (interpreter, blocking server on :8080):
;;   java -jar $JAR examples/net/magic-8-ball.lisp
;; Run (JVM class; needs the rontolisp jar on the classpath):
;;   java -jar $JAR examples/net/magic-8-ball.lisp -o Magic8Ball.class && java -cp $JAR:. Magic8Ball
;; Run (WASI component under wasmtime serve):
;;   java -jar $JAR examples/net/magic-8-ball.lisp -o magic-8-ball.wasm --component && \
;;     wasmtime serve -W gc=y -W exceptions=y magic-8-ball.wasm
;; Talk to it with:
;;   curl 'http://127.0.0.1:8080/?question=Will+rontolisp+run+everywhere'
;;   curl -X POST -d '{"question": "Should I deploy on Friday?"}' http://127.0.0.1:8080/magic-8

;; The twenty canonical answers: ten affirmative, five non-committal,
;; five negative.
(defvar *answers*
  '("It is certain." "It is decidedly so." "Without a doubt."
    "Yes definitely." "You may rely on it." "As I see it, yes."
    "Most likely." "Outlook good." "Yes." "Signs point to yes."
    "Reply hazy, try again." "Ask again later." "Better not tell you now."
    "Cannot predict now." "Concentrate and ask again."
    "Don't count on it." "My reply is no." "My sources say no."
    "Outlook not so good." "Very doubtful."))

(defun json-response (status obj)
  (list :status status
        :headers (list (cons "content-type" "application/json"))
        :body (format nil "~a~%" (rontolisp:json-stringify obj))))

;; --- pulling the question out of the request --------------------------------

;; ?question=... first (rontolisp:query-param url-decodes it, so + and %XX
;; become the spoken question); otherwise a JSON body's "question" field;
;; otherwise a non-empty raw body is the question itself.
(defun question-of (request)
  (let ((q (rontolisp:query-param (getf request :query) "question"))
        (body (getf request :body)))
    (cond ((and q (> (length q) 0)) q)
          ((and (stringp body) (> (length body) 0) (eql (char body 0) #\{))
           (getf (rontolisp:json-parse body) :question))
          ((and (stringp body) (> (length body) 0)) body)
          (t nil))))

;; --- consulting the ball -----------------------------------------------------

;; A fresh random draw per shake, like the real thing -- asking the same
;; question twice may answer differently.
(defun consult ()
  (nth (random (length *answers*)) *answers*))

;; The request plist's :path carries the path only (the query string arrives
;; separately as :query), so the comparisons are exact.
(defun route (request)
  (let ((path (getf request :path)))
    (if (or (string= path "/") (string= path "/magic-8"))
        (let ((question (question-of request)))
          (if question
              (json-response 200 (list :question question
                                       :answer (consult)))
              (json-response 400
                             (list :error "ask the ball a question"
                                   :usage "GET /?question=... or POST a question body"))))
        (json-response 404 (list :error "not found" :path path)))))

;; The request :body is an asynchronous stream on every backend; drain it once
;; here and hand the helpers a request whose :body is the whole string (getf
;; finds the prepended pair first).
(rontolisp:async-defun handle (request)
  (let ((body (rontolisp:await (rontolisp:read-all (getf request :body)))))
    (route (append (list :body body) request))))

;; On the interpreter / JVM this blocks and serves on port 8080; under
;; --component the port argument is ignored (the host provides the socket).
(rontolisp:http-handler 'handle 8080)

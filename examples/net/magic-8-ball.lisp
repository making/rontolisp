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
;; Run (JVM class; self-contained -- the embedded server travels beside it):
;;   java -jar $JAR examples/net/magic-8-ball.lisp -o Magic8Ball.class && java -cp . Magic8Ball
;; Run (WASI component under wasmtime serve):
;;   java -jar $JAR examples/net/magic-8-ball.lisp -o magic-8-ball.wasm --component && \
;;     wasmtime serve -W gc=y -W exceptions=y magic-8-ball.wasm
;; Talk to it with:
;;   curl 'http://127.0.0.1:8080/?question=Will+rontolisp+run+everywhere'
;;   curl -X POST -d '{"question": "Should I deploy on Friday?"}' http://127.0.0.1:8080/magic-8

;; The twenty canonical answers: ten affirmative, five non-committal,
;; five negative.
(defvar *answers*
  '("It is certain." "It is decidedly so." "Without a doubt." "Yes definitely."
    "You may rely on it." "As I see it, yes." "Most likely." "Outlook good."
    "Yes." "Signs point to yes." "Reply hazy, try again." "Ask again later."
    "Better not tell you now." "Cannot predict now."
    "Concentrate and ask again." "Don't count on it." "My reply is no."
    "My sources say no." "Outlook not so good." "Very doubtful."))

(defun json-response (status obj)
  (list status '(:content-type "application/json")
        (list (format nil "~a~%" (rontolisp:json-stringify obj)))))

;; --- pulling the question out of the request --------------------------------

;; ?question=... first (rontolisp:query-param url-decodes it, so + and %XX
;; become the spoken question); otherwise a JSON body's "question" field;
;; otherwise a non-empty raw body is the question itself.
(defun question-of (env)
  (let ((q (rontolisp:query-param (getf env :query-string) "question"))
        (body (getf env :body)))
    (cond ((and q (> (length q) 0)) q)
          ((and (stringp body) (> (length body) 0) (eql (char body 0) #\{))
           (gethash "question" (rontolisp:json-parse body)))
          ((and (stringp body) (> (length body) 0)) body)
          (t nil))))

;; --- consulting the ball -----------------------------------------------------

;; A fresh random draw per shake, like the real thing -- asking the same
;; question twice may answer differently.
(defun consult () (nth (random (length *answers*)) *answers*))

;; The env plist's :path-info carries the (percent-decoded) path only (the
;; query string arrives separately as :query-string), so the comparisons are
;; exact.
(defun route (env)
  (let ((path (getf env :path-info)))
    (if (or (string= path "/") (string= path "/magic-8"))
        (let ((question (question-of env)))
          (if question
              (json-response 200
                             (rontolisp:plist-hash-table
                              (list :question question :answer (consult))))
              (json-response 400
                             (rontolisp:plist-hash-table
                              (list :error "ask the ball a question"
                               :usage
                               "GET /?question=... or POST a question body")))))
        (json-response 404
         (rontolisp:plist-hash-table (list :error "not found" :path path))))))

;; The env :raw-body is an asynchronous stream on every backend; drain it once
;; here and hand the helpers an env whose :body is the whole string (getf
;; finds the prepended pair first).
(rontolisp:async-defun handle (env)
  (let ((body (rontolisp:await (rontolisp:read-all (getf env :raw-body)))))
    (route (append (list :body body) env))))

;; On the interpreter / JVM this blocks and serves on port 8080; under
;; --component the port argument is ignored (the host provides the socket).
(rontolisp:http-handler 'handle 8080)

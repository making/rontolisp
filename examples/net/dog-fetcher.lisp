;; The dog fetcher -- a rontolisp reproduction of wasmCloud's "dog-fetcher"
;; example (https://wasmcloud.com/docs/v1/examples/rust/component/dog-fetcher/;
;; source: examples/rust/components/dog-fetcher in the wasmCloud repo): a
;; served handler that itself makes an outgoing HTTP request, i.e.
;; rontolisp:fetch inside rontolisp:http-handler -- the classic
;; proxy/aggregator shape. Every GET asks the dog.ceo API for a random dog
;; picture and answers with JSON:
;;
;;   GET /            -> {"dog": "https://images.dog.ceo/breeds/.../xxx.jpg"}
;;   (other paths 404; an upstream failure 502)
;;
;; Run (interpreter, blocking server on :8080):
;;   java -jar $JAR examples/net/dog-fetcher.lisp
;; Run (JVM class; needs the rontolisp jar on the classpath):
;;   java -jar $JAR examples/net/dog-fetcher.lisp -o DogFetcher.class && java -cp $JAR:. DogFetcher
;; Run (WASI component under wasmtime serve; the wasi:http/client import that
;; carries the outbound fetch is host-provided by default):
;;   java -jar $JAR examples/net/dog-fetcher.lisp -o dog-fetcher.wasm --component && \
;;     wasmtime serve -W gc=y -W exceptions=y dog-fetcher.wasm
;; Talk to it with:
;;   curl http://127.0.0.1:8080/

(defun json-response (status obj)
  (list :status status
        :headers (list (cons "content-type" "application/json"))
        :body (format nil "~a~%" (rontolisp:json-stringify obj))))

;; One upstream round trip: dog.ceo answers {"message": "<image url>",
;; "status": "success"}. A failed fetch surfaces as a nil/non-200 response
;; plist, mapped to 502.
(rontolisp:async-defun fetch-dog ()
  ;; awaiting needs an async-defun; the response :body is an asynchronous
  ;; stream on the interpreter/JVM (drained with read-all) and still a whole
  ;; string under --component, hence the feature conditional.
  (let* ((res (rontolisp:await
               (rontolisp:fetch "https://dog.ceo/api/breeds/image/random")))
         (status (getf res :status))
         (body #+rontolisp-wasm (getf res :body)
               #-rontolisp-wasm (rontolisp:await (rontolisp:read-all (getf res :body)))))
    (if (and (integerp status) (= status 200))
        (getf (rontolisp:json-parse body) :message)
        nil)))

(rontolisp:async-defun handle (request)
  (if (string= (getf request :path) "/")
      (let ((dog (rontolisp:await (fetch-dog))))
        (if dog
            (json-response 200 (list :dog dog))
            (json-response 502 (list :error "the dog API did not answer"))))
      (json-response 404 (list :error "not found"))))

;; On the interpreter / JVM this blocks and serves on port 8080; under
;; --component the port argument is ignored (the host provides the socket).
(rontolisp:http-handler 'handle 8080)

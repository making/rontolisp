(defun handler (env)
  (let ((path (getf env :path-info))
        (method (getf env :request-method))
        (query (getf env :query-string))
        (headers (getf env :headers))
        (body (getf env :raw-body))
        (len (getf env :content-length)))
    (list 200
          (list :content-type "text/plain" :x-demo "one" :x-demo "two")
          (list (format nil "path=~a method=~a query=~a ua=~a len=~a body=~a"
                        path method query
                        (gethash "user-agent" headers)
                        len
                        (if (and body len (> len 0))
                            (let ((s (make-string len)))
                              (read-sequence s body)
                              s)
                            ""))))))

;; Register the handler in the single handler slot without holding a port:
;; the WAR shape a real implementation would emit instead of a blocking serve.
(defvar *server* (rontolisp::%http-server-start #'handler 0 nil :raw-body :buffered))
(rontolisp::%http-server-stop *server*)

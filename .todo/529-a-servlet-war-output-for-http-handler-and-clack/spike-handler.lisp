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

;; A jvm-export is what makes JvmLispCompiler set topLevelInClinit today: it is
;; standing in for the war mode flag .todo/530 would add.
(defun ping () 1)
(rontolisp:jvm-export 'ping :params '() :returns :s32 :as "ping")

(defvar *server* (rontolisp::%http-server-start #'handler 0 nil :raw-body :buffered))
(rontolisp::%http-server-stop *server*)

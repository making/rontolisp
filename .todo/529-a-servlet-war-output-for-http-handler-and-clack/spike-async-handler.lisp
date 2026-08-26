(rontolisp:async-defun handler (env)
  (let ((path (getf env :path-info))
        (headers (getf env :headers))
        (body (getf env :raw-body))
        (len (getf env :content-length)))
    (cond
      ((string= path "/error")
       (error "deliberate handler failure"))
      ((string= path "/slow")
       (rontolisp:await (rontolisp:wait-for 300))
       (list 200 (list :content-type "text/plain") (list "slow ok")))
      ((string= path "/bin")
       (let ((octets (make-array 3 :element-type '(unsigned-byte 8))))
         (setf (aref octets 0) #xff)
         (setf (aref octets 1) #xfe)
         (setf (aref octets 2) #x41)
         (list 200 (list :content-type "application/octet-stream") octets)))
      (t
       (list 200
             (list :content-type "text/plain" :x-demo "one" :x-demo "two")
             (list (format nil "path=~a ua=~a len=~a body=~a thread=~a"
                           path (gethash "user-agent" headers) len
                           (if (and body len (> len 0))
                               (let ((s (make-string len))) (read-sequence s body) s)
                               "")
                           (java:call (java:static "java.lang.Thread" "currentThread") "toString"))))))))

(defun ping () 1)
(rontolisp:jvm-export 'ping :params '() :returns :s32 :as "ping")

(defvar *server* (rontolisp::%http-server-start #'handler 0 nil :raw-body :buffered))
(rontolisp::%http-server-stop *server*)

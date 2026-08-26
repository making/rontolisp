(rontolisp:async-defun handler (env)
  (let* ((body (getf env :raw-body))
         (text (if body (rontolisp:await (rontolisp:read-all body)) "")))
    (list 200
          (list :content-type "text/plain")
          (list (format nil "async path=~a body=[~a]" (getf env :path-info) text)))))

(defvar *server* (rontolisp::%http-server-start #'handler 0 nil))
(rontolisp::%http-server-stop *server*)

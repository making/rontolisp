(defun url (path) (concatenate 'string "http://127.0.0.1:18431" path))
(rontolisp:async-defun body-text (r)
  (rontolisp:await (rontolisp:read-all (getf r :body))))
(print (handler-case
           (getf (rontolisp:await (rontolisp:fetch (url "/slow") (list :first-byte-timeout 200))) :status)
         (error (c) (format nil "~a" c))))
(let ((r (rontolisp:await (rontolisp:fetch (url "/slow") (list :first-byte-timeout 5000)))))
  (print (rontolisp:await (body-text r))))

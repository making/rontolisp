(rontolisp:async-defun body-text (r)
  (rontolisp:await (rontolisp:read-all (getf r :body))))
(let ((r (rontolisp:await (rontolisp:fetch "http://127.0.0.1:18431/hello" (list :method "GET")))))
  (print (rontolisp:await (body-text r))))

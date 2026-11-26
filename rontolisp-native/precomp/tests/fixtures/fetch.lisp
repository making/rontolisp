;; An HTTPS GET of the URL RLNATIVE_URL names, over the network runner's TLS (rustls,
;; ring): the code a handshake runs, which nothing else here reaches. The module is the
;; one a --native output carries (its fetch lowered to the runner's rlhttp imports), and
;; `wasmtime run` has no rlhttp: fetch.out is written by hand.
(let ((r (rontolisp:await (rontolisp:fetch (uiop:getenv "RLNATIVE_URL")))))
  (print (getf r :status))
  (print (rontolisp:await (rontolisp:read-all (getf r :body)))))

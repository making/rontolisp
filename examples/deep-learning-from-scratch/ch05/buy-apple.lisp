;; ch05/buy_apple.py -- backpropagation through the apple-shopping graph
;; (Deep Learning from Scratch).
;;
;; price = apple * apple_num * tax, then the gradients flow backwards:
;; d(price)/d(apple) = 2.2, d/d(apple_num) = 110, d/d(tax) = 200.
;;
;;   rontolisp ch05/buy-apple.lisp

(load "layer-naive.lisp")

(let ((apple 100)
      (apple-num 2)
      (tax 1.1)
      (mul-apple-layer (make-instance 'mul-layer))
      (mul-tax-layer (make-instance 'mul-layer)))
  ;; forward
  (let* ((apple-price (forward2 mul-apple-layer apple apple-num))
         (price (forward2 mul-tax-layer apple-price tax)))
    ;; backward
    (let* ((dprice 1)
           (dtax-pair (backward2 mul-tax-layer dprice))
           (dapple-price (car dtax-pair))
           (dtax (cadr dtax-pair))
           (dapple-pair (backward2 mul-apple-layer dapple-price))
           (dapple (car dapple-pair))
           (dapple-num (cadr dapple-pair)))
      (format t "price: ~a~%" (truncate price))
      (format t "dApple: ~,1f~%" dapple)
      (format t "dApple_num: ~a~%" (truncate dapple-num))
      (format t "dTax: ~a~%" (truncate dtax)))))

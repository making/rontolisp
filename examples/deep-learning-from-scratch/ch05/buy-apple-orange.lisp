;; ch05/buy_apple_orange.py -- backpropagation through the larger shopping
;; graph (Deep Learning from Scratch).
;;
;; price = (apple * apple_num + orange * orange_num) * tax; the add node
;; passes gradients through unchanged, the mul nodes swap their inputs.
;;
;;   rontolisp ch05/buy-apple-orange.lisp

(load "layer-naive.lisp")

(let ((apple 100)
      (apple-num 2)
      (orange 150)
      (orange-num 3)
      (tax 1.1)
      (mul-apple-layer (make-instance 'mul-layer))
      (mul-orange-layer (make-instance 'mul-layer))
      (add-apple-orange-layer (make-instance 'add-layer))
      (mul-tax-layer (make-instance 'mul-layer)))
  ;; forward
  (let* ((apple-price (forward2 mul-apple-layer apple apple-num))
         (orange-price (forward2 mul-orange-layer orange orange-num))
         (all-price (forward2 add-apple-orange-layer apple-price orange-price))
         (price (forward2 mul-tax-layer all-price tax)))
    ;; backward
    (let* ((dprice 1)
           (dtax-pair (backward2 mul-tax-layer dprice))
           (dall-price (car dtax-pair))
           (dtax (cadr dtax-pair))
           (dadd-pair (backward2 add-apple-orange-layer dall-price))
           (dapple-price (car dadd-pair))
           (dorange-price (cadr dadd-pair))
           (dorange-pair (backward2 mul-orange-layer dorange-price))
           (dorange (car dorange-pair))
           (dorange-num (cadr dorange-pair))
           (dapple-pair (backward2 mul-apple-layer dapple-price))
           (dapple (car dapple-pair))
           (dapple-num (cadr dapple-pair)))
      (format t "price: ~a~%" (truncate price))
      (format t "dApple: ~,1f~%" dapple)
      (format t "dApple_num: ~a~%" (truncate dapple-num))
      (format t "dOrange: ~,1f~%" dorange)
      (format t "dOrange_num: ~a~%" (truncate dorange-num))
      (format t "dTax: ~a~%" (truncate dtax)))))

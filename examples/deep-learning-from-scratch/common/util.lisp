;; common/util.py -- im2col / col2im (Deep Learning from Scratch).
;;
;; The two window transforms that turn a convolution into one matrix
;; product: im2col unfolds a rank-4 NCHW batch into the matrix whose row
;; (n, out-y, out-x) holds the filter-h x filter-w window of every channel
;; at that output position, and col2im is its adjoint -- a scatter-ADD fold
;; back into the image shape (overlapping windows accumulate), the shape
;; the convolution backward pass needs. The heavy index loops live in
;; linalg (linalg::%la-im2col / %la-col2im): direct index arithmetic
;; equivalent to the book's pad + strided-slice + 6-D transpose
;; composition, without materializing the scratch tensors.

(defun im2col (input-data filter-h filter-w &optional (stride 1) (pad 0))
  ;; (N C H W) -> (N*out-h*out-w, C*filter-h*filter-w); elements that fall
  ;; in the zero padding read 0.0.
  (linalg::%la-im2col input-data filter-h filter-w stride pad))

(defun col2im (col input-shape filter-h filter-w &optional (stride 1) (pad 0))
  ;; The im2col adjoint: scatter-adds col back into a fresh zero array of
  ;; input-shape (a dims list (N C H W)); padding contributions are dropped.
  (linalg::%la-col2im col input-shape filter-h filter-w stride pad))

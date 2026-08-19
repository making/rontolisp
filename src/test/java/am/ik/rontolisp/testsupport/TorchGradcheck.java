package am.ik.rontolisp.testsupport;

/**
 * The table-driven gradient check for the {@code torch} package, shared verbatim by the
 * interpreter, JVM and WASM test classes so all backends run the identical program: for
 * every differentiable operation, the analytic gradient {@code torch:backward} fills in
 * must match central-difference numerical differentiation of the forward pass to a
 * relative tolerance. Each new differentiable op extends the table with one
 * {@code gc-check} row. The program prints one {@code name: ok} line per row and
 * {@code ALL-OK} at the end; a failing row prints the offending element's analytic and
 * numeric values instead, so the assertion error carries the diagnosis.
 */
public final class TorchGradcheck {

	private TorchGradcheck() {
	}

	public static final String PROGRAM = """
			;; Central-difference gradient check over every differentiable torch op.
			(defparameter *gc-failures* nil)
			(defun gc-gref (g k)
			  (if (numberp g) g (row-major-aref g k)))
			(defun gc-check (name f inputs)
			  ;; f: tensors -> a scalar tensor; inputs: raw linalg arrays.
			  (let* ((eps 1.0e-4)
			         (tol 1.0e-3)
			         (ts (mapcar (lambda (x) (torch:tensor x :requires-grad t)) inputs))
			         (out (apply f ts))
			         (ok t))
			    (torch:backward out)
			    (do ((px inputs (cdr px)) (pt ts (cdr pt)))
			        ((null px))
			      (let* ((x (car px))
			             (g (torch:grad (car pt)))
			             (n (array-total-size x)))
			        (do ((k 0 (+ k 1)))
			            ((>= k n))
			          (let ((orig (row-major-aref x k)))
			            (setf (row-major-aref x k) (+ orig eps))
			            (let ((fp (torch:item (apply f (mapcar (function torch:tensor) inputs)))))
			              (setf (row-major-aref x k) (- orig eps))
			              (let ((fm (torch:item (apply f (mapcar (function torch:tensor) inputs)))))
			                (setf (row-major-aref x k) orig)
			                (let ((num (/ (- fp fm) (* 2 eps)))
			                      (ana (if (null g) 0.0 (gc-gref g k))))
			                  (unless (< (abs (- num ana)) (* tol (max 1.0 (abs num))))
			                    (setq ok nil)
			                    (setq *gc-failures*
			                          (cons (format nil "~a elt ~a: analytic ~a numeric ~a"
			                                        name k ana num)
			                                *gc-failures*))))))))))
			    (format t "~a: ~a~%" name (if ok "ok" "FAIL"))))
			(defun sq-loss (out) (torch:sum (torch:mul out out)))

			(defparameter *a23* (linalg:from-list '((0.5 -1.0 2.0) (1.5 0.25 -0.75))))
			(defparameter *b3* (linalg:from-list '(0.5 -0.25 1.25)))
			(defparameter *p23* (linalg:from-list '((0.5 1.0 2.0) (1.5 0.25 0.75))))
			(defparameter *m23* (linalg:from-list '((1.0 2.0 -1.0) (0.5 -0.5 1.5))))
			(defparameter *m32* (linalg:from-list '((0.5 1.0) (-1.0 2.0) (0.25 -0.5))))
			(defparameter *v3* (linalg:from-list '(1.0 -2.0 0.5)))
			(defparameter *v2* (linalg:from-list '(0.5 1.5)))

			(gc-check "add-broadcast" (lambda (a b) (sq-loss (torch:add a b))) (list *a23* *b3*))
			(gc-check "sub-broadcast" (lambda (a b) (sq-loss (torch:sub a b))) (list *a23* *b3*))
			(gc-check "mul-broadcast" (lambda (a b) (sq-loss (torch:mul a b))) (list *a23* *b3*))
			(gc-check "div" (lambda (a b) (sq-loss (torch:div a b))) (list *a23* (linalg:add *p23* 0.5)))
			(gc-check "neg" (lambda (a) (sq-loss (torch:neg a))) (list *a23*))
			(gc-check "power-scalar" (lambda (a) (sq-loss (torch:power a 3.0))) (list *p23*))
			(gc-check "power-both" (lambda (a b) (sq-loss (torch:power a b))) (list *p23* (linalg:add *p23* 0.5)))
			(gc-check "exp" (lambda (a) (sq-loss (torch:exp a))) (list *a23*))
			(gc-check "log" (lambda (a) (sq-loss (torch:log a))) (list *p23*))
			(gc-check "sqrt" (lambda (a) (sq-loss (torch:sqrt a))) (list *p23*))
			(gc-check "tanh" (lambda (a) (sq-loss (torch:tanh a))) (list *a23*))
			(gc-check "relu" (lambda (a) (sq-loss (torch:relu a))) (list *a23*))
			(gc-check "matmul-vv" (lambda (a b) (sq-loss (torch:matmul a b))) (list *v3* (linalg:add *v3* 0.25)))
			(gc-check "matmul-mv" (lambda (a b) (sq-loss (torch:matmul a b))) (list *m23* *v3*))
			(gc-check "matmul-vm" (lambda (a b) (sq-loss (torch:matmul a b))) (list *v2* *m23*))
			(gc-check "matmul-mm" (lambda (a b) (sq-loss (torch:matmul a b))) (list *m23* *m32*))
			(gc-check "matmul-batched"
			          (lambda (a b) (sq-loss (torch:matmul a b)))
			          (list (linalg:reshape (linalg:mul 0.125 (linalg:arange 12)) '(2 2 3)) *m32*))
			(gc-check "sum-axis" (lambda (a) (sq-loss (torch:sum a :axis 0 :keepdims t))) (list *a23*))
			(gc-check "sum-all" (lambda (a) (sq-loss (torch:sum a))) (list *a23*))
			(gc-check "mean-axis" (lambda (a) (sq-loss (torch:mean a :axis 1))) (list *a23*))
			(gc-check "var" (lambda (a) (sq-loss (torch:var a :ddof 1))) (list *a23*))
			(gc-check "var-axis" (lambda (a) (sq-loss (torch:var a :axis 1 :keepdims t))) (list *a23*))
			(gc-check "std" (lambda (a) (sq-loss (torch:std a))) (list *a23*))
			(gc-check "amax-ties" (lambda (a) (sq-loss (torch:amax a :axis 0)))
			          (list (linalg:from-list '((1.0 2.0 3.0) (1.0 -2.0 3.0)))))
			(gc-check "softmax" (lambda (a) (sq-loss (torch:softmax a :axis 1))) (list *a23*))
			(gc-check "log-softmax" (lambda (a) (sq-loss (torch:log-softmax a :axis 0))) (list *a23*))
			(gc-check "reshape" (lambda (a) (sq-loss (torch:reshape a '(3 2)))) (list *a23*))
			(gc-check "transpose" (lambda (a) (sq-loss (torch:transpose a))) (list *a23*))
			(gc-check "transpose-axes"
			          (lambda (a) (sq-loss (torch:transpose a '(1 0 2))))
			          (list (linalg:reshape (linalg:mul 0.25 (linalg:arange 12)) '(2 2 3))))
			(gc-check "unsqueeze" (lambda (a) (sq-loss (torch:unsqueeze a 1))) (list *a23*))
			(gc-check "squeeze" (lambda (a) (sq-loss (torch:squeeze a)))
			          (list (linalg:reshape *b3* '(1 3))))
			(gc-check "cat" (lambda (a b) (sq-loss (torch:cat (list a b) :axis 1))) (list *m23* *a23*))
			(gc-check "stack" (lambda (a b) (sq-loss (torch:stack (list a b) :axis 1))) (list *m23* *a23*))
			(gc-check "slice" (lambda (a) (sq-loss (torch:slice a (list nil (list 2 nil -2))))) (list *a23*))
			(gc-check "masked-fill"
			          (lambda (a) (sq-loss (torch:masked-fill a #2A((1 0 0) (0 1 0)) -2.0)))
			          (list *a23*))
			(gc-check "gather" (lambda (a) (sq-loss (torch:gather a #(2 0)))) (list *a23*))
			(gc-check "index-select-repeat"
			          (lambda (a) (sq-loss (torch:index-select a #(1 0 1)))) (list *m32*))
			;; shared-path accumulation: a is reached over three paths (add, mul, tanh)
			(gc-check "residual"
			          (lambda (a) (sq-loss (torch:add a (torch:mul a (torch:tanh a))))) (list *a23*))
			(if (null *gc-failures*)
			    (print 'all-ok)
			    (print (reverse *gc-failures*)))
			""";

	/** The expected stdout of {@link #PROGRAM}: one ok line per row, then ALL-OK. */
	public static final String EXPECTED = """
			add-broadcast: ok
			sub-broadcast: ok
			mul-broadcast: ok
			div: ok
			neg: ok
			power-scalar: ok
			power-both: ok
			exp: ok
			log: ok
			sqrt: ok
			tanh: ok
			relu: ok
			matmul-vv: ok
			matmul-mv: ok
			matmul-vm: ok
			matmul-mm: ok
			matmul-batched: ok
			sum-axis: ok
			sum-all: ok
			mean-axis: ok
			var: ok
			var-axis: ok
			std: ok
			amax-ties: ok
			softmax: ok
			log-softmax: ok
			reshape: ok
			transpose: ok
			transpose-axes: ok
			unsqueeze: ok
			squeeze: ok
			cat: ok
			stack: ok
			slice: ok
			masked-fill: ok
			gather: ok
			index-select-repeat: ok
			residual: ok
			ALL-OK""";

}

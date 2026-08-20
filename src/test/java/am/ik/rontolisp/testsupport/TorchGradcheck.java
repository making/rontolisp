package am.ik.rontolisp.testsupport;

/**
 * The shared {@code torch} acceptance programs, run verbatim by the interpreter, JVM and
 * WASM test classes so all backends execute the identical source: {@link #PROGRAM}, the
 * table-driven gradient check; {@link #NN_TRAINING_PROGRAM}, the nn-module training loop;
 * {@link #OPTIMIZER_PROGRAM}, the optimizer update rules plus the training-loop plumbing;
 * {@link #RECORD_PRINT_PROGRAM}, the records' printed form and identity semantics; and
 * {@link #ELEMENT_TYPE_PROGRAM}, the single-float element width torch originates every
 * array at.
 *
 * <p>
 * The gradient check: for every differentiable operation, the analytic gradient
 * {@code torch:backward} fills in must match central-difference numerical differentiation
 * of the forward pass to a relative tolerance. Each new differentiable op extends the
 * table with one {@code gc-check} row. Every tensor the check builds says
 * {@code :element-type 'double-float}, PINNING it to the wide type against
 * {@code torch::*default-element-type*}'s single-float default: it central-differences at
 * {@code eps 1e-4} against a {@code tol 1e-3} relative bound, and at f32 the subtraction
 * would leave about three significant digits -- at or past that bound. The check verifies
 * ADJOINTS, not the default dtype. The program prints one {@code name: ok} line per row
 * and {@code ALL-OK} at the end; a failing row prints the offending element's analytic
 * and numeric values instead, so the assertion error carries the diagnosis.
 */
public final class TorchGradcheck {

	private TorchGradcheck() {
	}

	public static final String PROGRAM = """
			;; Central-difference gradient check over every differentiable torch op.
			(defparameter *gc-failures* nil)
			(defun gc-gref (g k)
			  (if (numberp g) g (row-major-aref g k)))
			(defun gc-wide (inputs)
			  ;; The inputs as tensors of the WIDE type: this check central-differences
			  ;; at eps 1e-4 against a 1e-3 relative bound, which is at or past what f32
			  ;; leaves after the subtraction, and it verifies ADJOINTS rather than
			  ;; torch::*default-element-type* (.kb/torch.md).
			  (mapcar (lambda (x) (torch:tensor x :element-type 'double-float)) inputs))
			(defun gc-check (name f inputs)
			  ;; f: tensors -> a scalar tensor; inputs: raw linalg arrays.
			  (let* ((eps 1.0e-4)
			         (tol 1.0e-3)
			         (ts (mapcar (lambda (x)
			                       (torch:tensor x :requires-grad t
			                                     :element-type 'double-float))
			                     inputs))
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
			            (let ((fp (torch:item (apply f (gc-wide inputs)))))
			              (setf (row-major-aref x k) (- orig eps))
			              (let ((fm (torch:item (apply f (gc-wide inputs)))))
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
			(gc-check "erf" (lambda (a) (sq-loss (torch:erf a))) (list *a23*))
			(gc-check "gelu" (lambda (a) (sq-loss (torch:gelu a))) (list *a23*))
			(gc-check "gelu-tanh"
			          (lambda (a) (sq-loss (torch:gelu a :approximate :tanh))) (list *a23*))
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

			;; The nn module layer and the losses: every layer is checked through
			;; torch:forward with its parameters REPLACED by the checked inputs
			;; (torch:set-field), so the row also pins that the forward reads the
			;; registered fields rather than a closed-over copy.
			(defparameter *l33* (linalg:from-list '((0.5 -1.0 2.0) (1.5 0.25 -0.75) (-0.5 0.75 1.0))))
			(defparameter *w22* (linalg:from-list '((1.0 -0.5) (0.25 2.0))))
			(defun gc-linear (in out w b)
			  (let ((m (torch:linear in out :bias (if (null b) nil t))))
			    (torch:set-field m :weight w)
			    (torch:set-field m :bias b)
			    m))
			(gc-check "linear"
			          (lambda (x w b) (sq-loss (torch:forward (gc-linear 3 2 w b) x)))
			          (list *m23* *m32* *v2*))
			(gc-check "linear-no-bias"
			          (lambda (x w) (sq-loss (torch:forward (gc-linear 3 2 w nil) x)))
			          (list *m23* *m32*))
			(gc-check "linear-batched"
			          (lambda (x w b) (sq-loss (torch:forward (gc-linear 3 2 w b) x)))
			          (list (linalg:reshape (linalg:mul 0.125 (linalg:arange 12)) '(2 2 3)) *m32* *v2*))
			(gc-check "embedding"
			          (lambda (w)
			            (let ((m (torch:embedding 3 2)))
			              (torch:set-field m :weight w)
			              (sq-loss (torch:forward m #(1 0 1)))))
			          (list *m32*))
			(gc-check "layer-norm"
			          (lambda (x g b)
			            (let ((m (torch:layer-norm 3)))
			              (torch:set-field m :weight g)
			              (torch:set-field m :bias b)
			              (sq-loss (torch:forward m x))))
			          (list *a23* *b3* (linalg:add *b3* 0.5)))
			(gc-check "sequential"
			          (lambda (x w1 b1 w2)
			            (sq-loss (torch:forward (torch:sequential (gc-linear 3 2 w1 b1)
			                                                      (function torch:relu)
			                                                      (gc-linear 2 2 w2 nil))
			                                    x)))
			          (list *m23* *m32* *v2* *w22*))
			(gc-check "dropout-eval"
			          (lambda (x)
			            (let ((m (torch:dropout 0.5)))
			              (torch:eval m)
			              (sq-loss (torch:forward m x))))
			          (list *a23*))
			(gc-check "mse-loss" (lambda (a b) (torch:mse-loss a b)) (list *a23* *m23*))
			(gc-check "mse-loss-sum" (lambda (a b) (torch:mse-loss a b :reduction :sum)) (list *a23* *m23*))
			(gc-check "cross-entropy" (lambda (x) (torch:cross-entropy-loss x #(1 0 2))) (list *l33*))
			(gc-check "cross-entropy-ignore"
			          (lambda (x) (torch:cross-entropy-loss x #(1 0 2) :ignore-index 0)) (list *l33*))
			(gc-check "cross-entropy-rank3"
			          (lambda (x) (torch:cross-entropy-loss x #2A((1 0) (2 1))))
			          (list (linalg:reshape (linalg:mul 0.125 (linalg:arange 12)) '(2 2 3))))
			(gc-check "cross-entropy-sum"
			          (lambda (x) (torch:cross-entropy-loss x #(1 0 2) :reduction :sum)) (list *l33*))
			;; probability (soft-label) targets: a target of the LOGITS' own shape. The
			;; gradient is checked on both operands, so the row also pins that the target
			;; is differentiated through rather than treated as a constant.
			(gc-check "cross-entropy-soft"
			          (lambda (x p) (torch:cross-entropy-loss x p))
			          (list *l33* (linalg:from-list '((0.7 0.2 0.1) (0.1 0.6 0.3) (0.25 0.25 0.5)))))
			(gc-check "cross-entropy-soft-rank1"
			          (lambda (x p) (torch:cross-entropy-loss x p))
			          (list *v3* (linalg:from-list '(0.7 0.2 0.1))))
			(if (null *gc-failures*)
			    (print 'all-ok)
			    (print (reverse *gc-failures*)))
			""";

	/**
	 * The nn-module acceptance program: a 2-8-1 ReLU MLP built from
	 * {@code torch:sequential} trained on XOR for 200 steps of plain SGD over
	 * {@code torch:parameters}, with the update inside {@code torch:no-grad}. Only
	 * {@code + - * /} and {@code max} are involved (ReLU, MSE and the seeded
	 * Wichmann-Hill generator), so every backend follows the same trajectory; the program
	 * prints the parameter count the walk found and two convergence predicates rather
	 * than the loss, which is what "the loss goes down" means independently of the last
	 * digit.
	 */
	public static final String NN_TRAINING_PROGRAM = """
			(defun nn-sgd-step (m lr)
			  (torch:no-grad
			    (do ((p (torch:parameters m) (cdr p)))
			        ((null p))
			      (let ((tn (car p)))
			        (torch:set-data tn (linalg:sub (torch:data tn)
			                                       (linalg:mul lr (torch:grad tn))))))))
			(linalg:seed 3)
			(defparameter *net*
			  (torch:sequential (torch:linear 2 8) (function torch:relu) (torch:linear 8 1)))
			(defparameter *x* (torch:tensor '((0.0 0.0) (0.0 1.0) (1.0 0.0) (1.0 1.0))))
			(defparameter *y* (torch:tensor '((0.0) (1.0) (1.0) (0.0))))
			(defparameter *first* 0.0)
			(defparameter *last* 0.0)
			(dotimes (i 200)
			  (let ((loss (torch:mse-loss (torch:forward *net* *x*) *y*)))
			    (when (= i 0) (setq *first* (torch:item loss)))
			    (setq *last* (torch:item loss))
			    (torch:zero-grad *net*)
			    (torch:backward loss)
			    (nn-sgd-step *net* 0.2)))
			(print (list (length (torch:parameters *net*))
			             (< *last* (* 1.0e-3 *first*))
			             (< *last* 1.0e-6)))
			""";

	/** The expected stdout (and last value) of {@link #NN_TRAINING_PROGRAM}. */
	public static final String NN_TRAINING_EXPECTED = "(4 T T)";

	/**
	 * The printed form of the three records, which only exists because they are
	 * {@code defstruct}s carrying a {@code (:print-object ...)}: the hand-rolled tagged
	 * vectors they were before printed their raw slots, one of which is a backward
	 * closure with no portable rendering -- so a printed tensor could not be pinned at
	 * all. The printers spell only data the four backends agree on, and the identity
	 * lines pin what {@code eq}/{@code eql} answer for record instances (reference
	 * identity, which is what the tape's visited set and the parameter walk's dedup both
	 * rest on -- see .kb/torch.md).
	 */
	public static final String RECORD_PRINT_PROGRAM = """
			(defparameter *rp-t* (torch:tensor '(1.0 2.0) :requires-grad t))
			(defparameter *rp-s* (torch:tensor 0.5))
			(print *rp-t*)
			(print *rp-s*)
			(princ *rp-t*)
			(terpri)
			(format t "~a|~s~%" *rp-s* *rp-s*)
			(print (princ-to-string *rp-s*))
			(print (list *rp-s*))
			(print (torch:linear 2 2))
			(print (torch:sgd (list *rp-t*) :lr 0.5))
			;; A record is compared by IDENTITY, never by its slots: two tensors built
			;; from the same value are distinct, and member finds only the one that is
			;; there. Getting this wrong silently conflates two tape nodes.
			(print (list (eq *rp-t* *rp-t*) (eql *rp-t* *rp-t*)
			             (eq *rp-s* (torch:tensor 0.5))
			             (if (member (torch:tensor 0.5) (list *rp-s*)) t nil)
			             (if (member *rp-s* (list *rp-s*)) t nil)))
			""";

	/** The expected stdout of {@link #RECORD_PRINT_PROGRAM}. */
	public static final String RECORD_PRINT_EXPECTED = """
			#<TENSOR #f(1.0 2.0) :REQUIRES-GRAD T>
			#<TENSOR 0.5>
			#<TENSOR #f(1.0 2.0) :REQUIRES-GRAD T>
			#<TENSOR 0.5>|#<TENSOR 0.5>
			"#<TENSOR 0.5>"
			(#<TENSOR 0.5>)
			#<MODULE :LINEAR>
			#<OPTIMIZER :SGD :STEP-COUNT 0>
			(T T NIL NIL T)""";

	/**
	 * The element-width acceptance (todo-123 phase 0): a model built from
	 * {@code torch:tensor} + {@code torch:embedding} + {@code torch:linear} +
	 * {@code torch:layer-norm} + {@code torch:dropout} + {@code torch:pad-sequence}, run
	 * through a full forward AND backward pass, printing the {@code array-element-type}
	 * of every intermediate and every gradient. All of them must be {@code SINGLE-FLOAT}:
	 * torch originates its arrays at {@code torch::*default-element-type*} and every
	 * derived value inherits that width (`.kb/torch.md`). A missed origination site
	 * prints one {@code DOUBLE-FLOAT} here instead of silently pairing two widths, which
	 * every {@code --simd} kernel declines. The last line pins the other half of the
	 * split: {@code linalg:}'s own default is still double.
	 */
	public static final String ELEMENT_TYPE_PROGRAM = """
			(defun et (x) (if (arrayp x) (array-element-type x) 'number))
			(linalg:seed 7)
			(defparameter *et-emb* (torch:embedding 4 3))
			(defparameter *et-lin* (torch:linear 3 2))
			(defparameter *et-ln* (torch:layer-norm 2))
			(defparameter *et-drop* (torch:dropout 0.5))
			(defparameter *et-idx* (torch:pad-sequence '((0 1 2) (2 3))))
			(defparameter *et-h1* (torch:forward *et-emb* *et-idx*))
			(defparameter *et-h2* (torch:forward *et-lin* *et-h1*))
			(defparameter *et-h3* (torch:forward *et-ln* *et-h2*))
			(defparameter *et-h4* (torch:forward *et-drop* *et-h3*))
			(defparameter *et-loss* (torch:sum (torch:mul *et-h4* *et-h4*)))
			(torch:backward *et-loss*)
			(print (list (et (torch:data (torch:tensor '(1.0 2.0))))
			             (et (torch:data (torch:parameter '((1.0) (2.0)))))
			             (et (torch:data (torch:tensor (linalg:zeros 2))))
			             (et (torch:data *et-idx*))))
			(print (list (et (torch:data *et-h1*)) (et (torch:data *et-h2*))
			             (et (torch:data *et-h3*)) (et (torch:data *et-h4*))))
			(print (list (et (torch:grad (torch:field *et-emb* :weight)))
			             (et (torch:grad (torch:field *et-lin* :weight)))
			             (et (torch:grad (torch:field *et-lin* :bias)))
			             (et (torch:grad (torch:field *et-ln* :weight)))
			             (et (torch:grad (torch:field *et-ln* :bias)))))
			(print (list (et (linalg:zeros 2)) (et (linalg:randn '(2)))
			             (et (torch:data (torch:tensor '(1.0)
			                                           :element-type 'double-float)))))
			""";

	/** The expected stdout of {@link #ELEMENT_TYPE_PROGRAM}. */
	public static final String ELEMENT_TYPE_EXPECTED = """
			(SINGLE-FLOAT SINGLE-FLOAT SINGLE-FLOAT SINGLE-FLOAT)
			(SINGLE-FLOAT SINGLE-FLOAT SINGLE-FLOAT SINGLE-FLOAT)
			(SINGLE-FLOAT SINGLE-FLOAT SINGLE-FLOAT SINGLE-FLOAT SINGLE-FLOAT)
			(DOUBLE-FLOAT DOUBLE-FLOAT DOUBLE-FLOAT)""";

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
			erf: ok
			gelu: ok
			gelu-tanh: ok
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
			linear: ok
			linear-no-bias: ok
			linear-batched: ok
			embedding: ok
			layer-norm: ok
			sequential: ok
			dropout-eval: ok
			mse-loss: ok
			mse-loss-sum: ok
			cross-entropy: ok
			cross-entropy-ignore: ok
			cross-entropy-rank3: ok
			cross-entropy-sum: ok
			cross-entropy-soft: ok
			cross-entropy-soft-rank1: ok
			ALL-OK""";

	/**
	 * The optimizer acceptance program: the {@code torch:sgd} / {@code torch:adam} update
	 * rules checked against hand-computed PyTorch values (one step, an L2 term, a
	 * momentum sequence, and Adam's fully bias-corrected first step -- the classic
	 * off-by-one), the batching and mask helpers, and the identity-learning experiment
	 * from the book's chapter 2.3.4: the same two-layer feed-forward block trained by
	 * Adam with and without a skip connection around it. Prints one {@code name: ok} line
	 * per row and {@code ALL-OK} at the end, like {@link #PROGRAM}; the numeric rows
	 * compare to a relative 1e-9, so a backend's last-ulp difference in {@code expt} is
	 * not a failure while a wrong rule is.
	 */
	public static final String OPTIMIZER_PROGRAM = """
			;; The optimizer rules against hand-computed PyTorch values, the batching and
			;; mask helpers, and the residual-vs-plain identity-learning experiment.
			(defparameter *oc-failures* nil)
			(defun oc-flat (v)
			  ;; A tensor, a linalg array or a number as a flat list of numbers.
			  (let ((x (if (torch:tensorp v) (torch:data v) v)))
			    (cond ((numberp x) (list x))
			          ((consp x) x)
			          (t (linalg:to-list (linalg:flatten x))))))
			(defun oc-report (name ok got)
			  (unless ok
			    (setq *oc-failures* (cons (format nil "~a: got ~a" name got) *oc-failures*)))
			  (format t "~a: ~a~%" name (if ok "ok" "FAIL")))
			(defun oc-num (name v want)
			  ;; Every element of v within a relative 1e-9 of the hand-computed want.
			  (let ((got (oc-flat v)) (ok t))
			    (do ((p got (cdr p)) (q want (cdr q)))
			        ((null q))
			      (when (or (null p)
			                (>= (abs (- (car p) (car q))) (* 1.0e-9 (max 1.0 (abs (car q))))))
			        (setq ok nil)))
			    (unless (= (length got) (length want)) (setq ok nil))
			    (oc-report name ok got)))
			(defun oc-eq (name got want) (oc-report name (equal got want) got))
			(defun oc-param (x)
			  ;; A parameter of the WIDE type: the update rules below are checked against
			  ;; hand-computed PyTorch values at a relative 1e-9, which is inside f32's
			  ;; own resolution, so the rows that verify an optimizer's arithmetic pin the
			  ;; width rather than loosen the tolerance (TorchGradcheck.PROGRAM does the
			  ;; same). Everything after them -- topk, multinomial, pad-sequence, the
			  ;; masks, the residual experiment -- keeps torch's single-float default.
			  (torch:parameter x :element-type 'double-float))
			(defun oc-sq (p) (torch:sum (torch:mul p p)))

			;; --- SGD ---------------------------------------------------------------------
			;; p = (1 2), grad of sum(p*p) = (2 4); lr 0.1 -> p - 0.1 * grad.
			(defparameter *p* (oc-param '(1.0 2.0)))
			(defparameter *o* (torch:sgd (list *p*) :lr 0.1))
			(oc-eq "sgd-kind" (torch:optimizer-kind *o*) :sgd)
			(oc-eq "sgd-params" (length (torch:optimizer-params *o*)) 1)
			(oc-eq "step-count-0" (torch:step-count *o*) 0)
			(torch:backward (oc-sq *p*))
			(torch:step *o*)
			(oc-num "sgd-1step" *p* '(0.8 1.6))
			(oc-eq "step-count-1" (torch:step-count *o*) 1)
			(torch:zero-grad *o*)
			(oc-eq "zero-grad-optimizer" (torch:grad *p*) nil)
			;; the same step with an L2 term: g = grad + 0.5 * p = (2.5 5.0) at p = (1 2).
			(defparameter *pw* (oc-param '(1.0 2.0)))
			(defparameter *ow* (torch:sgd (list *pw*) :lr 0.1 :weight-decay 0.5))
			(torch:backward (oc-sq *pw*))
			(torch:step *ow*)
			(oc-num "sgd-weight-decay" *pw* '(0.75 1.5))
			;; momentum 0.5 on q = 1, loss q*q: buf 2, 2.6, 2.38 -> q 0.8, 0.54, 0.302.
			(defparameter *q* (oc-param '(1.0)))
			(defparameter *om* (torch:sgd (list *q*) :lr 0.1 :momentum 0.5))
			(defparameter *qs* nil)
			(dotimes (i 3)
			  (torch:zero-grad *om*)
			  (torch:backward (oc-sq *q*))
			  (torch:step *om*)
			  (setq *qs* (cons (torch:item *q*) *qs*)))
			(oc-num "sgd-momentum" (reverse *qs*) '(0.8 0.54 0.302))
			;; a scalar parameter (data is a plain number, not an array)
			(defparameter *s* (oc-param 3.0))
			(defparameter *os* (torch:sgd (list *s*) :lr 0.5))
			(torch:backward (torch:mul *s* *s*))
			(torch:step *os*)
			(oc-num "sgd-scalar" *s* '(0.0))
			;; a parameter no gradient reached is left alone
			(defparameter *pu* (oc-param '(5.0)))
			(defparameter *ou* (torch:sgd (list *pu*) :lr 1.0))
			(torch:step *ou*)
			(oc-num "sgd-no-grad" *pu* '(5.0))

			;; --- Adam --------------------------------------------------------------------
			;; r = 1, loss r*r. t = 1: m = 0.2, v = 0.004, m/(1-b1) = 2, v/(1-b2) = 4,
			;; step = lr * 2 / (2 + 1e-8) -- the fully bias-corrected first step, which is
			;; what pins t = 1 rather than 0 or 2.
			(defparameter *r* (oc-param '(1.0)))
			(defparameter *oa* (torch:adam (list *r*) :lr 0.1))
			(defparameter *rs* nil)
			(dotimes (i 3)
			  (torch:zero-grad *oa*)
			  (torch:backward (oc-sq *r*))
			  (torch:step *oa*)
			  (setq *rs* (cons (torch:item *r*) *rs*)))
			(oc-num "adam-3steps" (reverse *rs*)
			        '(0.9000000005 0.8004122286917928 0.7015862729460303))
			(oc-eq "adam-step-count" (torch:step-count *oa*) 3)
			(oc-eq "adam-betas" (torch:field *oa* :betas) '(0.9 0.999))
			;; the learning rate is an ordinary field, so a schedule is torch:set-field
			(defparameter *r2* (oc-param '(1.0)))
			(defparameter *oa2* (torch:adam (list *r2*) :lr 0.1))
			(torch:set-field *oa2* :lr 0.2)
			(torch:backward (oc-sq *r2*))
			(torch:step *oa2*)
			(oc-num "adam-set-lr" *r2* '(0.800000001))
			;; the L2 term rides the gradient: g = 2 + 0.5 * 1 = 2.5 at p = 1 (torch.optim.Adam)
			(defparameter *r3* (oc-param '(1.0)))
			(defparameter *oa3* (torch:adam (list *r3*) :lr 0.1 :weight-decay 0.5))
			(torch:backward (oc-sq *r3*))
			(torch:step *oa3*)
			(oc-num "adam-weight-decay" *r3* '(0.9000000003999999))

			;; --- AdamW -------------------------------------------------------------------
			;; the SAME rule with the decay decoupled: p first shrinks to 1 - 0.1*0.5 = 0.95,
			;; then takes the unmodified g = 2 Adam step (torch.optim.AdamW).
			(defparameter *rw* (oc-param '(1.0)))
			(defparameter *ow2* (torch:adamw (list *rw*) :lr 0.1 :weight-decay 0.5))
			(torch:backward (oc-sq *rw*))
			(torch:step *ow2*)
			(oc-num "adamw-1step" *rw* '(0.8500000004999999))
			(oc-eq "adamw-kind" (torch:optimizer-kind *ow2*) :adamw)
			(oc-eq "adamw-default-decay" (torch:field (torch:adamw (list *rw*)) :weight-decay) 0.01)

			;; --- gradient-norm clipping --------------------------------------------------
			;; two parameters, grads (2) and (2 4): total norm sqrt(4 + 4 + 16) = sqrt(24).
			(defparameter *c1* (oc-param '(1.0)))
			(defparameter *c2* (oc-param '(1.0 2.0)))
			(torch:backward (torch:add (oc-sq *c1*) (oc-sq *c2*)))
			(defparameter *cn* (torch:clip-grad-norm (list *c1* *c2*) 1.0))
			(oc-num "clip-grad-norm-returns" (list *cn*) '(4.898979485566356))
			;; every gradient scaled by 1 / (sqrt(24) + 1e-6), PyTorch's denominator
			(oc-num "clip-grad-norm-scaled"
			        (append (oc-flat (torch:grad *c1*)) (oc-flat (torch:grad *c2*)))
			        '(0.4082482071305467 0.4082482071305467 0.8164964142610934))
			;; below max-norm nothing is touched, and the measured norm is still returned
			(defparameter *c3* (oc-param '(1.0)))
			(torch:backward (oc-sq *c3*))
			(oc-num "clip-grad-norm-under" (list (torch:clip-grad-norm (list *c3*) 10.0)) '(2.0))
			(oc-num "clip-grad-norm-unscaled" (torch:grad *c3*) '(2.0))

			;; --- the sampling and walking helpers ----------------------------------------
			(oc-num "topk" (torch:topk (linalg:from-list '((1.0 5.0 3.0) (9.0 2.0 8.0))) 2)
			        '(5.0 3.0 9.0 8.0))
			(oc-num "topk-indices"
			        (torch:topk (linalg:from-list '((1.0 5.0 3.0) (9.0 2.0 8.0))) 2 :indices t)
			        '(1.0 2.0 0.0 2.0))
			(oc-num "topk-axis"
			        (torch:topk (linalg:from-list '((1.0 5.0 3.0) (9.0 2.0 8.0))) 1 :axis 0)
			        '(9.0 5.0 8.0))
			;; a one-hot distribution can only draw its own index, whatever the seed
			(linalg:seed 3)
			(oc-num "multinomial-degenerate"
			        (torch:multinomial (linalg:from-list '((0.0 1.0 0.0) (0.0 0.0 1.0))))
			        '(1.0 2.0))
			;; without replacement four draws from four equal weights are a permutation
			(oc-eq "multinomial-permutation"
			       (sort (car (linalg:to-list
			                   (torch:multinomial (linalg:from-list '((1.0 1.0 1.0 1.0)))
			                                      :num-samples 4)))
			             (function <))
			       '(0.0 1.0 2.0 3.0))
			;; the fields plist is the module walk: names in registration order, live values
			(defparameter *ln* (torch:layer-norm 2))
			(oc-eq "fields-names"
			       (do ((p (torch:fields *ln*) (cddr p)) (acc nil (cons (car p) acc)))
			           ((null p) (reverse acc)))
			       '(:weight :bias :eps))
			(oc-eq "fields-values"
			       (list (eq (nth 1 (torch:fields *ln*)) (torch:field *ln* :weight))
			             (eq (nth 3 (torch:fields *ln*)) (torch:field *ln* :bias))
			             (nth 5 (torch:fields *ln*)))
			       '(t t 1.0e-5))

			;; --- batching and the masks --------------------------------------------------
			(oc-num "pad-sequence" (torch:pad-sequence '((1 2 3) (4 5) (6)) :padding-value 9)
			        '(1.0 2.0 3.0 4.0 5.0 9.0 6.0 9.0 9.0))
			(oc-eq "pad-sequence-shape" (torch:shape (torch:pad-sequence '((1 2) (3)))) '(2 2))
			(oc-num "padding-mask"
			        (torch:padding-mask (torch:pad-sequence '((1 2 3) (4 5))) :pad-id 0)
			        '(0.0 0.0 0.0 0.0 0.0 1.0))
			(oc-num "subsequent-mask" (torch:subsequent-mask 3)
			        '(0.0 1.0 1.0 0.0 0.0 1.0 0.0 0.0 0.0))
			(linalg:seed 1)
			(oc-eq "shuffled-batches" (torch:shuffled-batches 7 3) '((6 0 5) (1 4 3) (2)))
			(oc-eq "shuffled-batches-order" (torch:shuffled-batches '(a b c d e) 2 :shuffle nil)
			       '((a b) (c d) (e)))
			(oc-eq "shuffled-batches-drop-last"
			       (torch:shuffled-batches '(a b c d e) 2 :shuffle nil :drop-last t)
			       '((a b) (c d)))

			;; --- the residual experiment (book chapter 2.3.4) ----------------------------
			;; Learning the identity with a two-layer feed-forward block, with and without
			;; the skip connection around it, trained by Adam on the same data.
			(defun ffn-forward (self x)
			  (torch:forward (torch:field self :linear2)
			                 (torch:relu (torch:forward (torch:field self :linear1) x))))
			(defun make-ffn (d-model d-ff)
			  (torch:module :ffn
			                (list :linear1 (torch:linear d-model d-ff)
			                      :linear2 (torch:linear d-ff d-model))
			                (function ffn-forward)))
			(defun skip-forward (self x)
			  (torch:add x (torch:forward (torch:field self :sublayer) x)))
			(defun make-skip (d-model d-ff)
			  (torch:module :skip (list :sublayer (make-ffn d-model d-ff))
			                (function skip-forward)))
			(linalg:seed 7)
			(defparameter *data* (torch:tensor (linalg:randn '(32 4))))
			(defparameter *plain* (make-ffn 4 8))
			(defparameter *res* (make-skip 4 8))
			(defparameter *op* (torch:adam *plain* :lr 0.01))
			(defparameter *or* (torch:adam *res* :lr 0.01))
			(defparameter *first-plain* 0.0)
			(defparameter *first-res* 0.0)
			(defparameter *last-plain* 0.0)
			(defparameter *last-res* 0.0)
			(dotimes (i 40)
			  (let ((lp (torch:mse-loss (torch:forward *plain* *data*) *data*))
			        (lr (torch:mse-loss (torch:forward *res* *data*) *data*)))
			    (when (= i 0)
			      (setq *first-plain* (torch:item lp))
			      (setq *first-res* (torch:item lr)))
			    (setq *last-plain* (torch:item lp))
			    (setq *last-res* (torch:item lr))
			    (torch:zero-grad *op*)
			    (torch:zero-grad *or*)
			    (torch:backward lp)
			    (torch:backward lr)
			    (torch:step *op*)
			    (torch:step *or*)))
			(oc-eq "identity-parameters"
			       (list (length (torch:parameters *plain*)) (length (torch:parameters *res*)))
			       '(4 4))
			(oc-eq "identity-learned"
			       (list (< *last-plain* (* 0.5 *first-plain*))
			             (< *last-res* (* 0.5 *first-res*))
			             (< *first-res* *first-plain*)
			             (< *last-res* *last-plain*))
			       '(t t t t))
			(if (null *oc-failures*)
			    (print 'all-ok)
			    (print (reverse *oc-failures*)))
			""";

	/**
	 * The expected stdout of {@link #OPTIMIZER_PROGRAM}: one ok line per row, then
	 * ALL-OK.
	 */
	public static final String OPTIMIZER_EXPECTED = """
			sgd-kind: ok
			sgd-params: ok
			step-count-0: ok
			sgd-1step: ok
			step-count-1: ok
			zero-grad-optimizer: ok
			sgd-weight-decay: ok
			sgd-momentum: ok
			sgd-scalar: ok
			sgd-no-grad: ok
			adam-3steps: ok
			adam-step-count: ok
			adam-betas: ok
			adam-set-lr: ok
			adam-weight-decay: ok
			adamw-1step: ok
			adamw-kind: ok
			adamw-default-decay: ok
			clip-grad-norm-returns: ok
			clip-grad-norm-scaled: ok
			clip-grad-norm-under: ok
			clip-grad-norm-unscaled: ok
			topk: ok
			topk-indices: ok
			topk-axis: ok
			multinomial-degenerate: ok
			multinomial-permutation: ok
			fields-names: ok
			fields-values: ok
			pad-sequence: ok
			pad-sequence-shape: ok
			padding-mask: ok
			subsequent-mask: ok
			shuffled-batches: ok
			shuffled-batches-order: ok
			shuffled-batches-drop-last: ok
			identity-parameters: ok
			identity-learned: ok
			ALL-OK""";

}

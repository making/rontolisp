package am.ik.rontolisp;

import java.util.List;

/**
 * The {@code linalg:} surface exercised over {@code #bf16} operands, in one place because
 * three harnesses need the SAME corpus and a copy per harness is how a seam ends up
 * covered on one backend only.
 *
 * <p>
 * Two properties are asked of it, and they need different instruments (see
 * {@code am.ik.rontolisp.eval.LinalgWidthWireTest}):
 *
 * <ul>
 * <li>the width is CARRIED -- a constructor takes {@code :element-type 'bfloat16} and
 * every transform answers {@code #bf16} rather than widening to {@code #d}, which is what
 * 2026-09-06 added and what {@code vec:} already had;
 * <li>every acceleration seam DECLINES it to the scalar defun, bit for bit. A seam that
 * declines changes nothing observable, so nothing but a differential run against the same
 * program without the flag can see a reader that GUESSES instead -- and a reader here
 * guesses by reading a {@code short[]} as "not a {@code float[]}, therefore
 * {@code double[]}", which answers a wrong number rather than crashing.
 * </ul>
 *
 * <p>
 * Every form is SELF-CONTAINED: the generator-seeded ones carry their own
 * {@code linalg:seed}, and the in-place members build their own destination. That is what
 * lets the interpreter harness evaluate each form in a fresh evaluator while the compiled
 * harness runs them all in one program, and still compare like with like.
 */
public final class LinalgBfloat16Corpus {

	private LinalgBfloat16Corpus() {
	}

	/**
	 * The operands the forms below name. Two {@code #bf16} arrays of one shape, a
	 * {@code #bf16} vector, and an {@code #f} / {@code #d} array of the same shape for
	 * the MIXED-width pairs -- which every kernel declines at any width, and must still
	 * decline when one side is the third one.
	 */
	public static final String PREAMBLE = """
			(defparameter *m* #bf16((1.0 2.0 3.0) (4.0 5.0 6.0)))
			(defparameter *n* #bf16((0.5 1.5 2.5) (3.5 4.5 5.5)))
			(defparameter *v* #bf16(1.0 2.0 3.0))
			(defparameter *f* #f((1.0 2.0 3.0) (4.0 5.0 6.0)))
			(defparameter *d* #d((1.0 2.0 3.0) (4.0 5.0 6.0)))
			""";

	/** The forms, each one evaluated against {@link #PREAMBLE}. */
	public static final List<String> FORMS = List.of(
			// Element-wise, including the broadcast, scalar-operand and mixed-width
			// shapes -- the pairings every kernel declines.
			"(linalg:add *m* *n*)", "(linalg:sub *m* *n*)", "(linalg:mul *m* *n*)", "(linalg:div *m* *n*)",
			"(linalg:add *m* 2.0)", "(linalg:add 2.0 *m*)", "(linalg:add *m* *v*)", "(linalg:add *m* *f*)",
			"(linalg:add *m* *d*)", "(linalg:maximum *m* *n*)", "(linalg:minimum *m* 3.0)",
			// The unary ufuncs.
			"(linalg:exp *m*)", "(linalg:log *m*)", "(linalg:tanh *m*)", "(linalg:sqrt *m*)",
			"(linalg:abs (linalg:negative *m*))", "(linalg:sign (linalg:sub *n* *m*))", "(linalg:erf *m*)",
			"(linalg:sin *m*)", "(linalg:cos *m*)", "(linalg:square *m*)", "(linalg:reciprocal *m*)",
			// Reductions, whole-array and per-axis.
			"(linalg:sum *m*)", "(linalg:sum *m* :axis 0)", "(linalg:sum *m* :axis 1 :keepdims t)", "(linalg:mean *m*)",
			"(linalg:norm *m*)", "(linalg:amax *m*)", "(linalg:amin *m* :axis 0)", "(linalg:argmax *v*)",
			"(linalg:argmin *m* :axis 1)", "(linalg:trace #bf16((1.0 2.0) (3.0 4.0)))",
			// Shape and product.
			"(linalg:transpose *m*)", "(linalg:transpose *m* '(1 0))", "(linalg:reshape *m* '(3 2))",
			"(linalg:flatten *m*)", "(linalg:dot *v* *v*)", "(linalg:dot *m* (linalg:transpose *n*))",
			"(linalg:outer *v* *v*)", "(linalg:matmul *m* (linalg:transpose *n*))",
			// The comparison masks and the select.
			"(linalg:greater *m* *n*)", "(linalg:greater-equal *m* 2.0)", "(linalg:less *m* *n*)",
			"(linalg:less-equal *m* *n*)", "(linalg:equal *m* *n*)", "(linalg:where (linalg:greater *m* 2.0) *m* *n*)",
			// The copies: take-rows, the strided gather behind slice and broadcast-to
			// (called directly with the bfloat16 width CODE, which the protocol can name
			// and a boolean could not), and the scatter-add adjoint.
			"(linalg:take-rows *m* #d(1.0 0.0 1.0))", "(linalg:slice *m* '((0 2) (0 3 2)))",
			"(linalg::%la-gather-strided *m* '(2 2) '(1 3) 1 2)", "(linalg::%la-broadcast-to *v* '(2 3))",
			"(linalg::%la-scatter-rows (linalg:zeros '(3 3) :element-type 'bfloat16) *m* #d(2.0 0.0))",
			"(linalg::%la-sum-squares *m* 0.5)", "(linalg::%la-scale *m* 0.5)",
			// Assembly and slicing.
			"(linalg:concatenate (list *m* *n*) :axis 0)", "(linalg:stack (list *m* *n*) :axis 0)",
			"(linalg:expand-dims *m* 1)", "(linalg:squeeze (linalg:expand-dims *m* 1) :axis 1)",
			"(linalg:pad *m* '((1 1) (0 1)))", "(linalg:triu *m*)", "(linalg:tril *m* :k 1)", "(linalg:softmax *m*)",
			"(linalg:log-softmax *m* :axis 1)", "(linalg:row *m* 1)", "(linalg:gather *m* #d(0.0 1.0))",
			"(linalg:diff *m*)", "(linalg:emap (function (lambda (x) (+ x 1))) *m*)", "(linalg:clip *m* 2.0 4.0)",
			"(linalg:relu (linalg:sub *m* 3.0))", "(linalg:zeros-like *m*)",
			// The stacked product and its two transposed orientations.
			"(linalg::%la-matmul-nd (linalg:stack (list *m* *n*))"
					+ " (linalg:stack (list (linalg:transpose *m*) (linalg:transpose *n*))))",
			"(linalg::%la-matmul-nd-ta (linalg:stack (list (linalg:transpose *m*) (linalg:transpose *n*)))"
					+ " (linalg:stack (list (linalg:transpose *m*) (linalg:transpose *n*))))",
			"(linalg::%la-matmul-nd-tb (linalg:stack (list *m* *n*)) (linalg:stack (list *m* *n*)))",
			// The fused torch: compositions -- the members only --gpu intercepts, so the
			// width reaches a device reader nothing else here does.
			"(linalg::%la-gelu *m*)", "(linalg::%la-gelu-grad *n* *m* nil)", "(linalg::%la-layer-norm *m* 1.0e-5)",
			"(linalg::%la-layer-norm-grad *n* *m* 1.0e-5 nil)", "(linalg::%la-layer-norm-grad-norm *n* *m* 1.0e-5 nil)",
			"(linalg::%la-layer-norm-affine *m* *v* *v* 1.0e-5)",
			"(linalg::%la-layer-norm-affine-grad *n* *m* *v* 1.0e-5 nil)",
			"(linalg::%la-softmax-grad *n* (linalg:softmax *m* :axis 1) 1)",
			"(linalg::%la-log-softmax-grad *n* (linalg:log-softmax *m* :axis 1) 1)",
			"(linalg::%la-scaled-masked-softmax *m* 0.5 (linalg:triu (linalg:ones '(2 3)) :k 1) -1.0e9 1)",
			// The CNN window pair.
			"(linalg::%la-im2col (linalg:reshape (linalg:arange 16 :element-type 'bfloat16) '(1 1 4 4)) 2 2 1 0)",
			"(linalg::%la-col2im (linalg:ones '(9 4) :element-type 'bfloat16) '(1 1 4 4) 2 2 1 0)",
			// The fused optimizer update, in place over four aligned bfloat16 buffers.
			"(linalg::%la-adam-step (linalg:full '(2 3) 1.0 :element-type 'bfloat16) *m*"
					+ " (linalg:zeros '(2 3) :element-type 'bfloat16)"
					+ " (linalg:zeros '(2 3) :element-type 'bfloat16)"
					+ " #d(0.001 0.0 0.0 0.9 0.1 0.999 0.001 1.0e-8 1.0 1.0 0.0))",
			// The generator: the one fill loop, the dropout mask (whose width was the
			// LAST boolean in this library), and the public draw.
			"(progn (linalg:seed 7) (linalg::%la-rng-fill (linalg:zeros 8 :element-type 'bfloat16)"
					+ " (linalg::%la-rng-state) 0 0.0 1.0))",
			"(progn (linalg:seed 7) (linalg::%la-dropout-mask '(4 4) 0.5 (linalg::%la-rng-state) 2))",
			"(progn (linalg:seed 7) (linalg:rand '(2 2) :element-type 'bfloat16))",
			// The constructors, every one of them.
			"(linalg:zeros '(2 2) :element-type 'bfloat16)", "(linalg:ones 3 :element-type 'bfloat16)",
			"(linalg:full '(2 2) 1.5 :element-type 'bfloat16)", "(linalg:eye 2 :element-type 'bfloat16)",
			"(linalg:arange 4 :element-type 'bfloat16)", "(linalg:linspace 0 1 3 :element-type 'bfloat16)",
			"(linalg:from-list '((1.0 2.0) (3.0 4.0)) :element-type 'bfloat16)",
			"(linalg:one-hot #d(0.0 2.0) 3 :element-type 'bfloat16)",
			"(progn (linalg:seed 3) (linalg:uniform 0 1 '(2 2) :element-type 'bfloat16))",
			"(progn (linalg:seed 3) (linalg:randn '(2 2) :element-type 'bfloat16))");

}

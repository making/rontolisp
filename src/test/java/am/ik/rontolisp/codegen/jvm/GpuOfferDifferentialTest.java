package am.ik.rontolisp.codegen.jvm;

import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispBFloat16Array;
import am.ik.rontolisp.LispDoubleFloatArray;
import am.ik.rontolisp.LispFloatArray;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSingleFloatArray;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.eval.Environment;
import am.ik.rontolisp.eval.LinalgGpu;
import am.ik.rontolisp.eval.LispEvaluator;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The ONE differential over the {@code --gpu} device-offer predicates, which are decided
 * TWICE: {@code eval/LinalgGpu} is what the interpreter runs and {@link JvmGpuTemplate}
 * is the copy a compiled program carries, and both sit ABOVE {@code am.ik.gpu}, so
 * neither backend can correct a disagreement between them. A shape one accepts and the
 * other declines is a program that runs {@code java -jar} and {@code -o out.class} down
 * different paths, at the same inputs, with nothing failing.
 *
 * <p>
 * <strong>This is deliberately not a per-helper pin.</strong> The two files share
 * thirteen predicates -- twelve under one name each ({@code batchStride}, {@code bcast},
 * {@code bcastShape}, {@code bcastStrides}, {@code copyInto}, {@code foldAxis},
 * {@code map}, {@code resident}, {@code rowMajorStrides}, {@code sameShape},
 * {@code scale}, {@code zip}) and one under two ({@code LinalgGpu.suffixLength} against
 * {@code JvmGpuTemplate.softmaxMaskLength}). Thirteen assertions would fix only that
 * today's thirteen agree and would say nothing when a FOURTEENTH is added to one side --
 * which is exactly how the mask rule got two names and no test. So the assertion is made
 * over the OFFER instead: the same operands, in each path's own call shape, and the two
 * must agree on accept versus decline and, where they accept, produce the same bits.
 *
 * <p>
 * Two halves, and only the FIRST of them runs in CI.
 * {@link #theTwoPathsAccelerateTheSameMemberSet()} holds on EVERY machine and is the one
 * that catches a member added to one path alone; the shape table needs a device -- see
 * that method's own comment for why the gate is not a hole -- and is chosen at the ACCEPT
 * BOUNDARY rather than for coverage -- a mask that is a trailing suffix and one whose
 * middle axis is extent 1, an exactly-equal pair, a rank mismatch, a fold on the last
 * axis and one that is not, a resident operand and a fresh one, both widths.
 */
class GpuOfferDifferentialTest {

	static boolean aDeviceIsAvailable() {
		return LinalgGpu.available();
	}

	/** Whether this machine's device has a {@code double} (CUDA does, Metal does not). */
	private static final boolean DOUBLES = am.ik.gpu.GpuThresholds.supportsDouble();

	/**
	 * The element count every "big enough to be offered" operand below is sized to: twice
	 * the largest of the four thresholds a member here can be turned down by, so that a
	 * shape meant to probe a PREDICATE is never declined for its size instead -- which
	 * would make the case agree vacuously.
	 *
	 * <p>
	 * Each threshold goes through {@link #offeredSize} first, because a backend that is
	 * not a member of a tier at all answers {@link Long#MAX_VALUE} rather than a large
	 * number, and the sentinel through this arithmetic does the OPPOSITE of what the
	 * paragraph above asks for: {@code 2 * Long.MAX_VALUE} wraps to {@code -2}, the
	 * {@code Math.max} in {@link #B} hands back its own floor of 2, and every operand
	 * here collapses to 1024 elements -- under every threshold on that machine. Metal
	 * answers the sentinel for the axis fold, so this file failed on it (2026-09-03)
	 * rather than merely agreeing vacuously, since the warm-up in {@link #toLisp} asserts
	 * that its member was accepted.
	 */
	private static final long BIG = 2 * Math.max(
			Math.max(offeredSize(am.ik.gpu.GpuThresholds.mapMinElements()),
					offeredSize(am.ik.gpu.GpuThresholds.stridedMinElements())),
			Math.max(offeredSize(am.ik.gpu.GpuThresholds.foldMinElements()),
					offeredSize(am.ik.gpu.GpuThresholds.fusedMinElements())));

	/**
	 * {@code threshold}, or {@code 0} when this backend is not a member of that tier at
	 * all -- a tier no size reaches must not be what an operand is sized for.
	 * @param threshold one of the thresholds in force
	 * @return a count safe to take a maximum over
	 */
	private static long offeredSize(long threshold) {
		return threshold == Long.MAX_VALUE ? 0 : threshold;
	}

	/** The last axis of every rank-3 operand -- a softmax row, and a fold's inner run. */
	private static final int C = 64;

	/**
	 * The middle axis, which must be above 1 for the extent-1 mask case to mean anything.
	 */
	private static final int R = 8;

	/** The leading axis, sized so that {@code B * R * C} clears {@link #BIG}. */
	private static final int B = (int) Math.max(2, (BIG + (long) R * C - 1) / ((long) R * C));

	/**
	 * The side of the smallest square product this machine accepts, with a safety factor.
	 */
	private static final int SIDE = squareSide();

	private static int squareSide() {
		int n = (int) Math.ceil(Math.cbrt((double) am.ik.gpu.GpuThresholds.minWork()));
		return Math.max(64, (n + n / 4 + 15) / 16 * 16);
	}

	/**
	 * One operand of a case. Held as a SPEC rather than an array so that both paths
	 * encode their own from it and cannot be handed different numbers; the same instance
	 * used twice in one case encodes to the same array on each side, which is what makes
	 * {@code (linalg:add a a)} the equal-shape case rather than two equal arrays.
	 *
	 * @param dims the shape
	 * @param resident whether the operand must be RESIDENT when the member is offered
	 * @param single {@code null} to follow the run's width, else the operand's own
	 */
	private record Operand(int[] dims, boolean resident, @Nullable Boolean single) {
		Operand(int... dims) {
			this(dims, false, null);
		}

		Operand warmed() {
			return new Operand(this.dims, true, this.single);
		}

		Operand asSingle() {
			return new Operand(this.dims, this.resident, Boolean.TRUE);
		}

		Operand asDouble() {
			return new Operand(this.dims, this.resident, Boolean.FALSE);
		}
	}

	/**
	 * An axes list argument: a Lisp proper list on one path, a compiled one on the other.
	 */
	private record Axes(long... axes) {
	}

	/**
	 * The absent argument -- {@code nil} to the interpreter, a Java {@code null} to the
	 * bridge. A stand-in rather than a bare {@code null} so that a case's argument list
	 * holds no nulls of its own and the two encodings stay each other's mirror.
	 */
	private static final Object NIL = new Object();

	/**
	 * One boundary case. The arguments appear twice because the two paths take genuinely
	 * different CALL SHAPES -- the interpreter's option form is
	 * {@code (linalg:sum a :axis 1 :keepdims t)} and the bridge's is
	 * {@code gpuSumAxis(a, 1L, t)} -- but the operands in them are the same instances, so
	 * only the shape of the call differs and never the data.
	 *
	 * @param member the member's unqualified name ({@link LispNames})
	 * @param internal whether it is a {@code %}-prefixed internal symbol
	 * @param extended whether the bridge takes it at its option-form call shape
	 * @param why what boundary this case sits on, for the assertion message
	 * @param lisp the interpreter's argument list
	 * @param compiled the bridge's argument list
	 */
	private record Case(String member, boolean internal, boolean extended, String why, List<Object> lisp,
			List<Object> compiled) {
	}

	// --- the half that holds on every machine ------------------------------------------

	/**
	 * The member SET is the same on both paths. This is the assertion a per-helper pin
	 * cannot make: a member accelerated on one path and not the other is the same defect
	 * as a predicate that disagrees, one level up, and it is caught here whatever the
	 * machine.
	 *
	 * <p>
	 * Both directions, from one mechanism. Every name the compile path claims
	 * ({@link JvmLinalgGpu#qualifiedMembers()}) is bound to a sentinel and then handed to
	 * {@code LinalgGpu.install}, which OVERRIDES what it accelerates and captures the
	 * rest as its own decline target: a name still bound to the sentinel afterwards is
	 * one the interpreter does not accelerate. And a name the interpreter accelerates
	 * that the compile path does not claim was never bound, which {@code install} refuses
	 * to accelerate at all -- so it fails as an exception naming the member.
	 */
	@Test
	void theTwoPathsAccelerateTheSameMemberSet() {
		List<String> claimed = JvmLinalgGpu.qualifiedMembers();
		Environment env = Environment.createGlobal(new PrintStream(OutputStream.nullOutputStream()));
		for (String name : claimed) {
			env.defineFunction(name, sentinel(name));
		}
		LispEvaluator evaluator = new LispEvaluator(new PrintStream(OutputStream.nullOutputStream()));
		assertThatCode(() -> {
			LinalgGpu.install(env, evaluator);
			LinalgGpu.installVec(env, evaluator);
		}).as("the interpreter accelerates a member the compiled bridge does not claim").doesNotThrowAnyException();
		List<String> notAccelerated = new ArrayList<>();
		for (String name : claimed) {
			if (env.lookupFunctionOrNull(name) instanceof LispFunction f && f.name().startsWith("declined ")) {
				notAccelerated.add(name);
			}
		}
		assertThat(notAccelerated).as("claimed by the compiled bridge, not accelerated by the interpreter").isEmpty();
	}

	// --- the half that needs a device
	// ---------------------------------------------------

	/**
	 * Every case in {@link #boundary()}, at both widths the machine has: the two paths
	 * agree on accept versus decline, and where they accept the result carries the same
	 * shape and the same bits.
	 *
	 * <p>
	 * <strong>This test does NOT run in CI, by design, and that is not a hole.</strong>
	 * Every machine the project's CI has is GPU-less and the gate above turns this method
	 * off there, so the shape rule is pinned only where a developer runs the suite on a
	 * device. It stays that way because the DEFECT is gated by the same thing the test
	 * is: every entry point in {@code am.ik.gpu.Gpu} is {@code device != null && ...}
	 * over its probe holder, so on a machine with no device nothing is ever accepted and
	 * both paths take their scalar fallback whatever their predicates say. A disagreement
	 * between {@code LinalgGpu} and {@link JvmGpuTemplate} can therefore produce a wrong
	 * answer only on a machine where this method already runs; the gate defers detection
	 * to the first machine on which the defect is observable at all, and does not leave
	 * it unprotected anywhere.
	 *
	 * <p>
	 * Making it CI-visible was priced and declined -- a stand-in {@code GpuDevice}, or a
	 * parallel shape-predicate surface on the bridge; {@code .kb/gpu.md}, "Closing the
	 * gap was priced and DECLINED" has the numbers and the reasons, of which the sharpest
	 * is that a device answering {@code true} without touching memory would make the
	 * seven RESIDENT-operand cases below agree vacuously and would compare an unwritten
	 * destination against another unwritten destination for the bits. What DOES run in CI
	 * is {@link #theTwoPathsAccelerateTheSameMemberSet()}, one method up.
	 */
	@Test
	@EnabledIf("aDeviceIsAvailable")
	void theTwoPathsAgreeOnEveryBoundaryShapeAndOnTheBits() {
		int accepted = 0, declined = 0;
		for (Case boundary : boundary()) {
			for (boolean single : DOUBLES ? new boolean[] { false, true } : new boolean[] { true }) {
				if (assertAgree(boundary, single)) {
					accepted++;
				}
				else {
					declined++;
				}
			}
		}
		// The census, which is what stops the whole table from agreeing VACUOUSLY: a
		// machine whose device turned every shape down -- or a table whose shapes all
		// fell below a threshold -- would pass every assertion above and pin nothing.
		assertThat(accepted).as("shapes both paths ACCEPTED").isGreaterThan(10);
		assertThat(declined).as("shapes both paths DECLINED").isGreaterThan(10);
	}

	/**
	 * The cases, at the accept boundary of the six members that between them run every
	 * one of the thirteen shared predicates that decides an offer:
	 * {@code %la-scaled-masked-softmax} (the mask suffix rule, under its two names),
	 * {@code linalg:add} ({@code sameShape} / {@code zip} / {@code bcastShape} /
	 * {@code bcastStrides} / {@code scale} / {@code resident}), {@code linalg:sum}'s
	 * option form ({@code foldAxis}), {@code linalg::%la-matmul-nd}
	 * ({@code batchStride}), {@code linalg:exp} ({@code map}), {@code linalg:transpose}'s
	 * axes form (the strided gather's own stride derivation) and {@code linalg:reshape}'s
	 * {@code -1} extent (the resident tier's own {@code copyInto}, resolved against the
	 * operand's element count before the shared shape reader ever sees it).
	 */
	private static List<Case> boundary() {
		List<Case> cases = new ArrayList<>();
		Operand a = new Operand(B, R, C);
		Operand warm = a.warmed();
		// The mask rule -- the pair that carries two names. Its dims, leading extent-1
		// axes dropped, must be a SUFFIX of the operand's.
		cases.add(softmax("no mask at all", a, NIL, 2L));
		cases.add(softmax("the mask is the last axis", a, new Operand(C), 2L));
		cases.add(softmax("the mask is the trailing two axes", a, new Operand(R, C), 2L));
		cases.add(softmax("the mask is the operand's own shape", a, new Operand(B, R, C), 2L));
		cases.add(softmax("a leading extent-1 axis is dropped", a, new Operand(1, R, C), 2L));
		// The shape .todo/650 was filed for: the mask is (batch 1 key) against a
		// (batch query key) score, so the axis UNDER the leading one is extent 1 and the
		// suffix rule turns it down. Measured at the acceptance ceiling and left as it
		// is; what the rule must not do is differ between the two paths.
		cases.add(softmax("a MIDDLE axis of extent 1 is not a suffix", a, new Operand(B, 1, C), 2L));
		cases.add(softmax("every leading axis is 1, above the operand's rank", a, new Operand(1, 1, 1, C), 2L));
		cases.add(softmax("the last axis disagrees", a, new Operand(C + 1), 2L));
		cases.add(softmax("the negative axis is the last one", a, new Operand(R, C), -1L));
		cases.add(softmax("any axis but the last", a, new Operand(R, C), 0L));
		// The binary members at their three shapes. An EQUAL-shaped pair is the resident
		// tier's and is declined at any size over an operand the device has not seen; a
		// BROADCAST pair is the strided tier's and is taken from the size threshold.
		cases.add(binary("equal shapes over a fresh operand", a, a));
		cases.add(binary("equal shapes over a RESIDENT operand", warm, warm));
		cases.add(binary("a broadcast pair above the threshold", a, new Operand(1, 1, C)));
		cases.add(binary("a broadcast pair stretched in the middle", a, new Operand(1, R, 1)));
		cases.add(binary("a rank mismatch, above the threshold", a, new Operand(C)));
		cases.add(binary("a rank mismatch below the threshold", new Operand(R, C), new Operand(C)));
		cases.add(binary("an incompatible extent", a, new Operand(B, R + 1, C)));
		cases.add(binary("a mixed-width pair", a.asDouble(), new Operand(B, R, C).asSingle()));
		cases.add(binary("a fresh array with a scalar", a, 2.0));
		cases.add(binary("a RESIDENT array with a scalar", a.warmed(), 2.0));
		cases.add(binary("a scalar on the LEFT of a RESIDENT array", 2.0, a.warmed()));
		// The resident-tier reshape's own -1 spelling: one extent inferred from the
		// element count, at most one, and only where it divides evenly -- the same
		// resolution the defun itself does before allocating the output.
		// A divisor one more than the warmed operand's own element count can never
		// divide it evenly (the remainder is the count itself), regardless of what B
		// resolves to on this machine -- so the decline holds without depending on the
		// threshold arithmetic above.
		long notADivisor = (long) B * R * C + 1;
		cases.add(reshape("a bare -1 shape flattens", warm, -1L));
		cases.add(reshape("a -1 extent is inferred from the trailing dims", warm, new Axes(-1, C)));
		cases.add(reshape("more than one -1 declines", warm, new Axes(-1, -1)));
		cases.add(reshape("a -1 extent that does not divide evenly declines", warm, new Axes(-1, notADivisor)));
		// The axis fold, whose output shape decides as much as its axis does.
		cases.add(fold("a fold on the LAST axis", a, 2L, false));
		cases.add(fold("a fold on the leading axis", a, 0L, false));
		cases.add(fold("a fold on the middle axis, keeping it", a, 1L, true));
		cases.add(fold("the negative axis is the last one", a, -1L, false));
		cases.add(fold("an axis out of range", a, 3L, false));
		cases.add(fold("the whole-array form has no axis", a, NIL, false));
		cases.add(fold("a vector folded away leaves no output shape", new Operand(B * R * C), 0L, false));
		cases.add(fold("a vector folded with :keepdims does", new Operand(B * R * C), 0L, true));
		// The stacked product's per-batch stride, which is ARITHMETIC and not just an
		// offer: a disagreement here is a wrong answer rather than a slow one.
		cases.add(product("a contiguous batch", new Operand(2, SIDE, SIDE), new Operand(2, SIDE, SIDE)));
		cases.add(product("a wholly broadcast right operand", new Operand(2, SIDE, SIDE), new Operand(SIDE, SIDE)));
		cases.add(product("a broadcast axis UNDER a non-broadcast one", new Operand(2, 1, SIDE, SIDE),
				new Operand(1, 3, SIDE, SIDE)));
		cases.add(product("a mismatched inner extent", new Operand(2, SIDE, SIDE), new Operand(2, SIDE + 1, SIDE)));
		// The element-wise tier, whose whole rule is the size threshold.
		cases.add(unary("above the element threshold", a));
		cases.add(unary("below the element threshold", new Operand(4, 4)));
		// The strided gather behind the axes transpose.
		cases.add(transpose("a rank-3 permutation above the threshold", a, new Axes(2, 0, 1)));
		cases.add(transpose("a repeated axis is not a permutation", a, new Axes(0, 0, 1)));
		cases.add(transpose("too few axes for the rank", a, new Axes(1, 0)));
		cases.add(transpose("below the threshold, over a fresh operand", new Operand(4, 4), new Axes(1, 0)));
		return cases;
	}

	private static Case softmax(String why, Operand a, Object mask, long axis) {
		List<Object> args = List.of(a, 8.0, mask, -1.0e9, axis);
		return new Case(LispNames.LINALG_SCALED_MASKED_SOFTMAX, true, false, why, args, args);
	}

	private static Case binary(String why, Object left, Object right) {
		List<Object> args = List.of(left, right);
		return new Case(LispNames.LINALG_ADD, false, false, why, args, args);
	}

	private static Case fold(String why, Operand a, Object axis, boolean keepdims) {
		List<Object> lisp = keepdims
				? List.of(a, new LispSymbol(":AXIS"), axis, new LispSymbol(":KEEPDIMS"), Boolean.TRUE)
				: List.of(a, new LispSymbol(":AXIS"), axis);
		List<Object> compiled = List.of(a, axis, keepdims ? Boolean.TRUE : NIL);
		return new Case(LispNames.LINALG_SUM, false, true, why, lisp, compiled);
	}

	private static Case product(String why, Operand left, Operand right) {
		List<Object> args = List.of(left, right);
		return new Case(LispNames.LINALG_MATMUL_ND, true, false, why, args, args);
	}

	private static Case reshape(String why, Object a, Object shape) {
		List<Object> args = List.of(a, shape);
		return new Case(LispNames.LINALG_RESHAPE, false, false, why, args, args);
	}

	private static Case unary(String why, Operand a) {
		List<Object> args = List.of(a);
		return new Case(LispNames.LINALG_EXP, false, false, why, args, args);
	}

	private static Case transpose(String why, Operand a, Axes axes) {
		List<Object> args = List.of(a, axes);
		return new Case(LispNames.LINALG_TRANSPOSE, false, true, why, args, args);
	}

	// --- running one case on each path
	// ---------------------------------------------------

	private boolean assertAgree(Case boundary, boolean single) {
		String what = boundary.member() + " -- " + boundary.why() + (single ? " (single-float)" : " (double-float)");
		Map<Operand, Object> lispOperands = new IdentityHashMap<>();
		List<LispVal> lispArgs = new ArrayList<>();
		for (Object arg : boundary.lisp()) {
			lispArgs.add(toLisp(arg, single, lispOperands));
		}
		Map<Operand, Object> compiledOperands = new IdentityHashMap<>();
		@Nullable Object[] compiledArgs = new @Nullable Object[boundary.compiled().size()];
		for (int i = 0; i < compiledArgs.length; i++) {
			compiledArgs[i] = toCompiled(boundary.compiled().get(i), single, compiledOperands);
		}
		LispVal fromInterpreter = interceptor(boundary.member(), boundary.internal()).body().apply(lispArgs);
		Object fromCompiled = invoke(bridge(boundary.member(), boundary.extended(), compiledArgs.length), compiledArgs);
		boolean interpreterAccepted = !(fromInterpreter instanceof LispNil);
		assertThat(fromCompiled != null).as("accepted, on the compiled path: %s", what).isEqualTo(interpreterAccepted);
		if (!interpreterAccepted) {
			return false;
		}
		LispFloatArray accepted = (LispFloatArray) fromInterpreter;
		Object packed = java.util.Objects.requireNonNull(fromCompiled);
		assertThat(shapeOf(packed)).as("the result's shape: %s", what).isEqualTo(accepted.dims());
		assertThat(bitsOf(packed)).as("the result's bits: %s", what).isEqualTo(bitsOf(accepted));
		return true;
	}

	/**
	 * The interpreter's interceptor for the member, installed over a SENTINEL: what comes
	 * back is the device's answer, or -- when the interceptor declined -- {@code nil},
	 * which no accepted member ever answers.
	 */
	private static LispFunction interceptor(String member, boolean internal) {
		String qualified = internal ? PackageRegistry.qualifyInternal(LispNames.LINALG_PKG, member)
				: PackageRegistry.qualify(LispNames.LINALG_PKG, member);
		return (LispFunction) java.util.Objects.requireNonNull(interpreterOffers().lookupFunctionOrNull(qualified),
				() -> qualified + " is not installed");
	}

	private static @Nullable Environment interpreterOffers;

	private static Environment interpreterOffers() {
		Environment cached = interpreterOffers;
		if (cached != null) {
			return cached;
		}
		{
			Environment env = Environment.createGlobal(new PrintStream(OutputStream.nullOutputStream()));
			for (String name : JvmLinalgGpu.qualifiedMembers()) {
				env.defineFunction(name, sentinel(name));
			}
			LinalgGpu.install(env, new LispEvaluator(new PrintStream(OutputStream.nullOutputStream())));
			interpreterOffers = env;
			return env;
		}
	}

	/**
	 * The binding {@code LinalgGpu.install} captures as its decline target. In a real
	 * program that is the scalar defun; here it answers {@code nil}, so that a decline is
	 * exactly as observable as the bridge's {@code null}.
	 */
	private static LispFunction sentinel(String name) {
		return new LispFunction("declined " + name, args -> LispNil.INSTANCE);
	}

	/**
	 * The bridge method backing the member, found through the compile path's OWN name
	 * table ({@link JvmLinalgGpu#kernelKey} / {@link JvmLinalgGpu#extendedKernelKey}) --
	 * so a member re-pointed at a different kernel is followed here rather than pinned to
	 * a name this test spelled out.
	 */
	private static Method bridge(String member, boolean extended, int arity) {
		String key = extended ? JvmLinalgGpu.extendedKernelKey(member) : JvmLinalgGpu.kernelKey(member);
		assertThat(key).as("the compiled bridge claims %s", member).isNotNull();
		// The ops key IS the bridge method's name for most members and its name without
		// the gpu prefix for the handful named by a JvmGpuRuntimeBuilder constant
		// (matmulNd -> gpuMatmulNd), which is the builder's own convention.
		String prefixed = "gpu" + Character.toUpperCase(key.charAt(0)) + key.substring(1);
		for (Method method : JvmGpuTemplate.class.getDeclaredMethods()) {
			if ((method.getName().equals(key) || method.getName().equals(prefixed))
					&& method.getParameterCount() == arity) {
				method.setAccessible(true);
				return method;
			}
		}
		throw new AssertionError("no bridge method named " + key + " taking " + arity + " arguments");
	}

	private static @Nullable Object invoke(Method bridge, @Nullable Object[] args) {
		try {
			return bridge.invoke(null, args);
		}
		catch (ReflectiveOperationException e) {
			throw new AssertionError(bridge.getName() + " threw", e);
		}
	}

	// --- encoding one operand for each path
	// ----------------------------------------------

	private static LispVal toLisp(Object arg, boolean single, Map<Operand, Object> encoded) {
		if (arg == NIL) {
			return LispNil.INSTANCE;
		}
		return switch (arg) {
			case Operand operand -> (LispVal) encoded.computeIfAbsent(operand, o -> {
				boolean f = o.single() != null ? o.single() : single;
				LispVal value = f ? new LispSingleFloatArray(floats(o), o.dims().clone())
						: new LispDoubleFloatArray(doubles(o), o.dims().clone());
				if (o.resident()) {
					assertThat(interceptor(LispNames.LINALG_EXP, false).body().apply(List.of(value)))
						.as("the warm-up member must be ACCEPTED, or the operand is not resident")
						.isNotInstanceOf(LispNil.class);
				}
				return value;
			});
			case Axes axes -> {
				LispVal list = LispNil.INSTANCE;
				for (int i = axes.axes().length - 1; i >= 0; i--) {
					list = new LispCons(new LispInteger(axes.axes()[i]), list);
				}
				yield list;
			}
			case LispSymbol keyword -> keyword;
			case Boolean ignored -> new LispSymbol("T");
			case Double d -> new LispDouble(d);
			case Long n -> new LispInteger(n);
			default -> throw new AssertionError("no Lisp encoding for " + arg);
		};
	}

	private static @Nullable Object toCompiled(Object arg, boolean single, Map<Operand, Object> encoded) {
		if (arg == NIL) {
			return null;
		}
		return switch (arg) {
			case Operand operand -> encoded.computeIfAbsent(operand, o -> {
				boolean f = o.single() != null ? o.single() : single;
				Object value = f ? packedF(o) : packedD(o);
				if (o.resident()) {
					assertThat(invoke(bridge(LispNames.LINALG_EXP, false, 1), new Object[] { value }))
						.as("the warm-up member must be ACCEPTED, or the operand is not resident")
						.isNotNull();
				}
				return value;
			});
			case Axes axes -> {
				Object list = null;
				for (int i = axes.axes().length - 1; i >= 0; i--) {
					list = new Object[] { axes.axes()[i], list };
				}
				yield list;
			}
			case Boolean t -> t;
			case Double d -> d;
			case Long n -> n;
			default -> throw new AssertionError("no compiled encoding for " + arg);
		};
	}

	/**
	 * The elements every operand of a shape is filled with. Deterministic in the index
	 * and away from zero, so a softmax row and a product both have something to say, and
	 * identical on both paths because both fill from here.
	 */
	private static double element(int i) {
		return 0.5 + (i % 17) * 0.125 - (i % 5) * 0.0625;
	}

	private static int count(int[] dims) {
		int n = 1;
		for (int d : dims) {
			n *= d;
		}
		return n;
	}

	private static double[] doubles(Operand o) {
		double[] data = new double[count(o.dims())];
		for (int i = 0; i < data.length; i++) {
			data[i] = element(i);
		}
		return data;
	}

	private static float[] floats(Operand o) {
		float[] data = new float[count(o.dims())];
		for (int i = 0; i < data.length; i++) {
			data[i] = (float) element(i);
		}
		return data;
	}

	private static double[] packedD(Operand o) {
		int[] dims = o.dims();
		double[] packed = new double[1 + dims.length + count(dims)];
		packed[0] = dims.length;
		for (int k = 0; k < dims.length; k++) {
			packed[1 + k] = dims[k];
		}
		for (int i = 0; i < count(dims); i++) {
			packed[1 + dims.length + i] = element(i);
		}
		return packed;
	}

	private static float[] packedF(Operand o) {
		int[] dims = o.dims();
		float[] packed = new float[1 + dims.length + count(dims)];
		packed[0] = dims.length;
		for (int k = 0; k < dims.length; k++) {
			packed[1 + k] = dims[k];
		}
		for (int i = 0; i < count(dims); i++) {
			packed[1 + dims.length + i] = (float) element(i);
		}
		return packed;
	}

	// --- reading a result off each path
	// ---------------------------------------------------

	private static int[] shapeOf(Object packed) {
		int rank = packed instanceof float[] f ? (int) f[0] : (int) ((double[]) packed)[0];
		int[] dims = new int[rank];
		for (int k = 0; k < rank; k++) {
			dims[k] = packed instanceof float[] f ? (int) f[1 + k] : (int) ((double[]) packed)[1 + k];
		}
		return dims;
	}

	/**
	 * The result's elements as RAW bits, which is what "bit-identical" means: a hex
	 * comparison separates a {@code -0.0} and a {@code NaN} payload that {@code ==} would
	 * not.
	 */
	private static List<String> bitsOf(LispFloatArray result) {
		List<String> bits = new ArrayList<>();
		switch (result) {
			case LispSingleFloatArray f -> {
				for (float v : f.data()) {
					bits.add(Integer.toHexString(Float.floatToRawIntBits(v)));
				}
			}
			case LispDoubleFloatArray d -> {
				for (double v : d.data()) {
					bits.add(Long.toHexString(Double.doubleToRawLongBits(v)));
				}
			}
			case LispBFloat16Array b -> {
				// The stored pattern IS the bits at this width -- no conversion, so
				// nothing a NaN payload could be lost through.
				for (short v : b.data()) {
					bits.add(Integer.toHexString(v & 0xFFFF));
				}
			}
		}
		return bits;
	}

	private static List<String> bitsOf(Object packed) {
		Object home = java.util.Objects.requireNonNull(JvmGpuTemplate.gpuMaterialize(packed));
		int rank = home instanceof float[] f ? (int) f[0] : (int) ((double[]) home)[0];
		List<String> bits = new ArrayList<>();
		if (home instanceof float[] f) {
			for (int i = 1 + rank; i < f.length; i++) {
				bits.add(Integer.toHexString(Float.floatToRawIntBits(f[i])));
			}
		}
		else {
			double[] d = (double[]) home;
			for (int i = 1 + rank; i < d.length; i++) {
				bits.add(Long.toHexString(Double.doubleToRawLongBits(d[i])));
			}
		}
		return bits;
	}

}

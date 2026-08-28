package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.jvm.ByteCodeWriter;
import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.macro.LispMacroExpander;
import org.jspecify.annotations.Nullable;

/**
 * The JVM analogue of the wasm backend's integer expression-tree fusion
 * ({@code WasmIntFusionCompiler}, {@code .kb/wasm-int-fusion.md}): a nested
 * arithmetic/bitwise tree over {@code + - * mod rem logand logior logxor lognot ash}
 * (plus {@code 1+}/{@code 1-}) compiles into ONE unboxed evaluation instead of a chain of
 * generic-helper calls that box every intermediate {@code java.lang.Long}.
 *
 * <p>
 * Unlike the wasm version, a fused site is OUTLINED into its own private static method
 * ({@code _fx$N}): the call site evaluates the non-constant leaves once, left to right,
 * as the call's arguments, and the method guards each leaf ({@code instanceof Long}),
 * unboxes it once into a primitive {@code long} local, runs the tree as raw {@code long}
 * arithmetic (checked through the {@code Math.*Exact} intrinsics, whose
 * {@code ArithmeticException} is the bail signal), and boxes only the root. The fallback
 * -- the target of every guard failure and of the overflow handler -- recomputes the SAME
 * tree from the SAME arguments through the generic runtime helpers the per-op compilers
 * call ({@code _add}-family, {@code _logand}-family), so a bailing fast path reproduces
 * the generic result bit for bit, including promotion to {@code BigInteger} and the
 * division-by-zero error shape. Outlining is what keeps the double emission from pushing
 * an enclosing method toward HotSpot's 8000-bytecode {@code HugeMethodLimit}
 * ({@code .kb/hot-path-method-size.md}): the enclosing method holds one
 * {@code invokestatic} where the generic emission held the whole per-op chain, and
 * structurally identical sites (SHA-256's 64 rounds) SHARE one method.
 *
 * <p>
 * The same classification serves three more entry points: fused binary comparisons
 * ({@code _fx$N} returning a raw {@code int} truth value, consumed boxed in value
 * position and raw by {@code if}/{@code while} tests), assignments into unboxed
 * dual-representation locals ({@link RawLocal}, registered by {@link JvmLetCompiler}),
 * and calls to fusion-inlinable one-liner defuns ({@code mod32+}/{@code rol32}) and
 * {@code flet}-bound local functions, whose bodies substitute into the tree.
 *
 * <p>
 * Everything here is a speed-for-size trade {@code --optimize=size} declines (the same
 * gate as {@link JvmTypedLoopCompiler}); with fusion off every call site falls through to
 * the per-op path it always had, byte for byte.
 */
final class JvmIntFusionCompiler {

	/** Force-disables integer fusion, for A/B profiling. */
	private static final boolean DISABLED = Boolean.getBoolean("rontolisp.debug.nointfusion");

	/**
	 * Trees past these bounds fall back to the generic per-op path: the fused method
	 * emits the tree twice (fast + fallback), and a method body must stay well under
	 * HotSpot's 8000-bytecode compile refusal.
	 */
	private static final int MAX_EXPR_LEAVES = 32;

	private static final int MAX_OPS = 64;

	/** How many nested defun/local-function body substitutions a tree may perform. */
	private static final int MAX_INLINE_DEPTH = 4;

	private JvmIntFusionCompiler() {
	}

	/**
	 * Whether this compile emits the integer-fusion speed-for-size trades at all: on at
	 * the default and speed levels, declined by {@code --optimize=size} (the
	 * {@code Ctx.intFusion} half of the same gate {@code Ctx.typedLoops} reads), never
	 * under {@code --dynamic} (late binding must keep observing redefinition).
	 * @param ctx the compilation context
	 * @return {@code true} when fused sites may be emitted
	 */
	static boolean enabled(JvmLispCompiler.Ctx ctx) {
		return ctx.intFusion && !ctx.dynamic && ctx.fusedState != null && !DISABLED;
	}

	// ------------------------------------------------------------------ the tree model

	private sealed interface Node permits OpNode, ConstLeaf, ExprLeaf, ArefLeaf, RandomLeaf, RawLeaf {

	}

	private record OpNode(String op, List<Node> args) implements Node {
	}

	private record ConstLeaf(long value) implements Node {
	}

	/** An opaque expression: evaluated at the call site, guarded in the method. */
	private static final class ExprLeaf implements Node {

		final LispVal expr;

		int paramSlot = -1;

		int longSlot = -1;

		int dblSlot = -1;

		ExprLeaf(LispVal expr) {
			this.expr = expr;
		}

	}

	/**
	 * A rank-1 {@code (aref a i)} leaf: the array evaluates once as a call argument and
	 * the fast path reads the element RAW -- no {@code Long.valueOf} per read -- from
	 * either packed representation, the bare {@code long[]} packed integer vector
	 * ({@code _iv*}) or the general array's length-6 header over a flat {@code long[]}
	 * ({@code .kb/adjustable-arrays.md}). Any other array shape, a non-{@code Long}
	 * index, an out-of-range position or the nil sentinel bails to the fallback, which
	 * reruns the ordinary rank-1 aref dispatch from the same arguments (the read is
	 * pure), reproducing today's behavior including the error shapes.
	 *
	 * <p>
	 * The INDEX is itself a fusion node ({@link #arefIndexNode}): a literal folds into
	 * the method, an unboxed local and a {@code random} draw read the raw slot the
	 * prologue filled, and anything else is an ordinary guarded {@code Object} argument
	 * -- so {@code (aref a (random n))} and {@code (aref a i)} over an unboxed {@code i}
	 * pay no box on the way in either.
	 */
	private static final class ArefLeaf implements Node {

		final LispVal arrayExpr;

		@Nullable Node indexNode;

		int arrParam = -1;

		int longSlot = -1;

		ArefLeaf(LispVal arrayExpr) {
			this.arrayExpr = arrayExpr;
		}

	}

	/**
	 * A {@code (random <integer>)} leaf: the draw is a {@code long} internally already
	 * ({@code .kb/random.md}), so the tree takes the generator's raw result instead of
	 * {@code _random}'s boxed return -- and a LITERAL limit needs no call argument at
	 * all, so the boxed limit disappears from the call site too.
	 *
	 * <p>
	 * The fast path computes the same expression {@code _random} computes for a
	 * {@code Long} limit ({@code (long) (ThreadLocalRandom.current().nextDouble() *
	 * limit)}), so the two are one formula, not two generators.
	 *
	 * <p>
	 * This is the only leaf kind that is NOT pure, and the whole draw protocol exists for
	 * that: the fallback re-emits its tree, and a node bound to a substituted parameter
	 * used twice is re-emitted twice, so a fallback that could draw would draw a
	 * different number in each occurrence -- {@code (defun dif (x) (- x x))} over
	 * {@code (dif (random lim))} would stop answering 0. So the draw happens EXACTLY
	 * ONCE, in the prologue, for every leaf, on every path: a {@code Long} limit draws
	 * raw into {@code longSlot} (flag set), anything else calls {@code _random} into
	 * {@code boxSlot} (flag clear) and raises the method's bail flag, which is tested
	 * once after all the draws. Both branches assign every slot, so nothing here can bail
	 * past another leaf's draw, and the fallback only ever READS what the prologue
	 * already decided.
	 */
	private static final class RandomLeaf implements Node {

		/** The limit expression, or {@code null} when {@link #limitConst} is it. */
		@Nullable final LispVal limitExpr;

		final long limitConst;

		int limitParam = -1;

		int longSlot = -1;

		/** Only for a non-literal limit: 0 means {@link #boxSlot} holds the draw. */
		int flagSlot = -1;

		/** Only for a non-literal limit: {@code _random}'s boxed draw. */
		int boxSlot = -1;

		RandomLeaf(@Nullable LispVal limitExpr, long limitConst) {
			this.limitExpr = limitExpr;
			this.limitConst = limitConst;
		}

	}

	/**
	 * A read of an unboxed dual-representation local ({@link RawLocal}) inside a fused
	 * tree: the call site passes the (raw {@code long}, boxed shadow, flag) slot triple
	 * as three arguments -- a snapshot at the read's source position -- and the method
	 * resolves them once: the raw value when the flag is set, the shadow's guarded unbox
	 * otherwise, the fallback for a non-{@code Long} shadow.
	 */
	private static final class RawLeaf implements Node {

		final RawLocal src;

		int rawParam = -1;

		int shadowParam = -1;

		int flagParam = -1;

		int longSlot = -1;

		int dblSlot = -1;

		RawLeaf(RawLocal src) {
			this.src = src;
		}

	}

	/**
	 * An unboxed (dual-representation) {@code let} local: {@code longSlot} (two JVM
	 * slots) holds the raw value, {@code shadowSlot} an ordinary boxed reference, and
	 * {@code flagSlot} an {@code int} that is non-zero while the raw slot is
	 * authoritative. A cleared flag means "use the shadow, whatever it holds -- INCLUDING
	 * null, which is nil" (null cannot be the raw marker: a local assigned nil must read
	 * back as nil). A flag slot rather than a sentinel object, so no static field and no
	 * {@code <clinit>} ride along -- a program whose only fused shapes get
	 * dead-code-shaken leaves no residue. Registered per eligible binding by
	 * {@link JvmLetCompiler}; every assignment funnels through {@link #compileRawStore}
	 * and every boxed read through {@link #emitRawLocalBoxedRead}.
	 */
	record RawLocal(int longSlot, int shadowSlot, int flagSlot) {
	}

	/**
	 * A let-bound local function eligible for fused-call substitution: the {@code (let
	 * ((__FLETn_f (lambda ...))) ...)} shape {@code flet} lowers to, with fixed plain
	 * parameters and a single body expression that is a closed integer-operation tree
	 * over them. Registered by {@link JvmLetCompiler} for the extent of the binding's
	 * body, consumed at {@code (funcall __FLETn_f ...)} sites.
	 */
	record LocalIntLambda(List<String> params, LispVal body) {
	}

	// ------------------------------------------------------------------ shared state

	/**
	 * The per-compile registry of outlined fused-site methods, shared by every
	 * {@code Ctx} of one {@link JvmLispCompiler} run: structurally identical sites share
	 * one method (SHA-256's 64 rounds are a handful of shapes), and the pending list is
	 * what the compiler's fused-method pass then emits bodies for.
	 */
	static final class State {

		final String className;

		final List<Pending> pending = new ArrayList<>();

		private final Map<String, MethodrefConstant> byKey = new HashMap<>();

		private int nextId;

		/**
		 * Set when any raw local is read boxed: the {@code _ubRead} helper is emitted
		 * (and shaken back out with its callers when they turn out unreachable).
		 */
		boolean usesUbRead;

		/** Set when a fused fast path shifts: the {@code _fxAsh} helper is emitted. */
		boolean usesFxAsh;

		State(String className) {
			this.className = className;
		}

	}

	/** One outlined fused-site method awaiting its body (the compiler's fused pass). */
	record Pending(MethodrefConstant ref, Utf8Constant nameUtf8, Utf8Constant descUtf8, Node root, List<Node> leaves,
			int cmpMask) {

		boolean isCompare() {
			return this.cmpMask >= 0;
		}

	}

	/**
	 * Per-site classification state: the registered leaves, plus what makes raw-local
	 * reads shareable -- a local no leaf of this site can reassign snapshots once and is
	 * read at every occurrence.
	 */
	private static final class Site {

		final List<Node> leaves = new ArrayList<>();

		final Map<String, RawLeaf> sharedRawLeaves = new HashMap<>();

		final Set<String> assignedNames = new HashSet<>();

		Site(LispVal expr) {
			collectAssignedNames(expr, this.assignedNames);
		}

		private static void collectAssignedNames(LispVal form, Set<String> out) {
			if (!(form instanceof LispCons cons)) {
				return;
			}
			if (cons.car() instanceof LispSymbol head && cons.isProperList()
					&& (LispNames.SETQ.equals(head.name()) || LispNames.SETF.equals(head.name()))) {
				List<LispVal> parts = cons.toList();
				for (int i = 1; i + 1 < parts.size(); i += 2) {
					if (parts.get(i) instanceof LispSymbol target) {
						out.add(target.name());
					}
				}
			}
			LispVal cur = cons;
			while (cur instanceof LispCons cell) {
				collectAssignedNames(cell.car(), out);
				cur = cell.cdr();
			}
		}

	}

	// ------------------------------------------------------------------ entry points

	/**
	 * Compiles the call as a fused integer expression tree (one {@code invokestatic} of
	 * an outlined method over the once-evaluated leaves), or returns {@code false}
	 * (emitting nothing) when the form does not qualify -- the caller then runs the
	 * ordinary per-operation path.
	 */
	static boolean tryCompile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		if (!enabled(ctx)) {
			return false;
		}
		Site site = new Site(cons);
		Node root = classify(cons, ctx, Map.of(), site, 0);
		if (!(root instanceof OpNode)) {
			return false;
		}
		int ops = countOps(root);
		if (ops > MAX_OPS || site.leaves.size() > MAX_EXPR_LEAVES) {
			return false;
		}
		if (ops < 2 && !hasRawRead(site.leaves) && !hasConstOperand(root)) {
			// A single operation over plain boxed leaves runs no leaner fused; the
			// generic call keeps owning that shape (and its emission stays byte-stable).
			return false;
		}
		MethodrefConstant ref = methodFor(root, site.leaves, -1, ctx);
		pushLeaves(site.leaves, ctx, className);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ref.index());
		return true;
	}

	/**
	 * Compiles a binary numeric comparison ({@code = < > <= >=}) whose operands are
	 * integer expression trees as one outlined raw {@code long} compare, leaving the
	 * BOXED t/nil on the stack (value position). Skips the both-plain-leaves shape to
	 * keep the generic emission unchanged; returns {@code false} (emitting nothing) when
	 * it does not apply.
	 */
	static boolean tryCompileCompareValue(LispCons cons, JvmLispCompiler.Ctx ctx, String className, int branchOpcode) {
		if (!emitCompareCall(cons, ctx, className, branchOpcode)) {
			return false;
		}
		JvmEmitHelper.emitBoolFromInt(ctx);
		return true;
	}

	/**
	 * The condition-position variant for {@code if}/{@code while} tests: when the test is
	 * a fusable binary comparison, emits the outlined compare call and leaves the RAW
	 * {@code int} truth value (0 = false) on the stack -- the caller branches with
	 * {@code IFEQ} instead of {@code IFNULL}, skipping the boxed t/nil round trip every
	 * loop head used to pay. Returns {@code false} (emitting nothing) when the test is
	 * not that shape.
	 */
	static boolean tryCompileCondition(LispVal test, JvmLispCompiler.Ctx ctx, String className) {
		if (!(test instanceof LispCons cons) || !cons.isProperList() || !(cons.car() instanceof LispSymbol head)
				|| cons.toList().size() != 3) {
			return false;
		}
		int branchOpcode = switch (head.name()) {
			case LispNames.EQ -> Opcode.IFEQ;
			case LispNames.LT -> Opcode.IFLT;
			case LispNames.GT -> Opcode.IFGT;
			case LispNames.LE -> Opcode.IFLE;
			case LispNames.GE -> Opcode.IFGE;
			default -> -1;
		};
		return branchOpcode >= 0 && emitCompareCall(cons, ctx, className, branchOpcode);
	}

	private static boolean emitCompareCall(LispCons cons, JvmLispCompiler.Ctx ctx, String className, int branchOpcode) {
		if (!enabled(ctx)) {
			return false;
		}
		List<LispVal> parts = cons.toList();
		if (JvmLispCompiler.hasDoubleLiteral(parts)) {
			// The double-literal path owns this shape (IEEE compare over unboxed
			// doubles); fusing it would change nothing for the better.
			return false;
		}
		Site site = new Site(cons);
		Node left = classify(parts.get(1), ctx, Map.of(), site, 0);
		if (left == null) {
			return false;
		}
		Node right = classify(parts.get(2), ctx, Map.of(), site, 0);
		if (right == null) {
			return false;
		}
		if (countOps(left) + countOps(right) > MAX_OPS || site.leaves.size() > MAX_EXPR_LEAVES) {
			return false;
		}
		if (left instanceof ExprLeaf && right instanceof ExprLeaf) {
			return false;
		}
		Node root = new OpNode(CMP_ROOT, List.of(left, right));
		MethodrefConstant ref = methodFor(root, site.leaves, maskFor(branchOpcode), ctx);
		pushLeaves(site.leaves, ctx, className);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ref.index());
		return true;
	}

	/** The synthetic root op a compare method's two operand trees hang under. */
	private static final String CMP_ROOT = "%cmp";

	private static int maskFor(int branchOpcode) {
		// _cmpb's bitmask vocabulary: 1 = lt, 2 = eq, 4 = gt, 0 = unordered -- a NaN
		// operand fails every operator on the fallback exactly as it does today.
		return switch (branchOpcode) {
			case Opcode.IFEQ -> 0b010;
			case Opcode.IFLT -> 0b001;
			case Opcode.IFGT -> 0b100;
			case Opcode.IFLE -> 0b011;
			case Opcode.IFGE -> 0b110;
			default -> throw new IllegalArgumentException("unexpected comparison branch: " + branchOpcode);
		};
	}

	/**
	 * The {@code funcall} entry point: compiles {@code (funcall var args...)} of a
	 * registered local function as a fused tree (the substituted body becomes the root),
	 * or returns {@code false} (emitting nothing) for anything else.
	 */
	static boolean tryCompileLocalCall(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		return enabled(ctx) && cons.cdr() instanceof LispCons fnCell && fnCell.car() instanceof LispSymbol fvar
				&& ctx.localIntLambdas.containsKey(fvar.name()) && tryCompile(cons, ctx, className);
	}

	// ------------------------------------------------------------------ raw locals

	/**
	 * A quick syntactic filter for {@link JvmLetCompiler}'s unboxed-local eligibility:
	 * does this assignment value LOOK like an integer-operation root? Precision only
	 * affects performance, never correctness -- {@link #compileRawStore} stores boxed
	 * into the shadow for anything that does not actually classify.
	 */
	static boolean isRawAssignShaped(LispVal expr, JvmLispCompiler.Ctx ctx) {
		if (expr instanceof LispInteger) {
			return true;
		}
		if (expr instanceof LispSymbol sym) {
			// An outer unboxed local: the assignment is a raw-to-raw slot copy.
			return ctx.rawLocals.containsKey(sym.name());
		}
		if (!(expr instanceof LispCons cons) || !(cons.car() instanceof LispSymbol head)) {
			return false;
		}
		if (cons.isProperList() && JvmLispCompiler.hasDoubleLiteral(cons.toList())) {
			// Float-contaminated: the value can never land in the raw long slot, so the
			// dual representation would pay its per-site dispatch to always take the
			// shadow store -- and every read of the name would pay _ubRead for a value
			// the raw slot never holds.
			return false;
		}
		return switch (head.name()) {
			case LispNames.ADD, LispNames.SUB, LispNames.MUL, LispNames.MOD, LispNames.REM, LispNames.LOGAND,
					LispNames.LOGIOR, LispNames.LOGXOR, LispNames.LOGNOT, LispNames.ASH, LispNames.ONE_PLUS,
					LispNames.ONE_MINUS, LispNames.LDB, LispNames.AREF ->
				true;
			default -> ctx.inlinableDefuns.containsKey(head.name());
		};
	}

	/**
	 * Assignment sites past this bound decline the dual representation: the raw store's
	 * per-site dispatch is larger than a plain boxed store, and a generated straight-line
	 * body with thousands of {@code setq}s (fast-http's state machines, the 16-bit-branch
	 * pinning test) must not outgrow the 64 KB method limit it fit before. A loop assigns
	 * at a handful of SITES however many times it runs, so the shapes the representation
	 * exists for are unaffected.
	 */
	private static final int MAX_RAW_ASSIGN_SITES = 64;

	/**
	 * A body with more assignment sites than this IN TOTAL (any name) declines the dual
	 * representation for every binding of its {@code let}: it is generated straight-line
	 * code (an unrolled hash function, a parser state machine), where the per-site
	 * dispatch bytes multiply into real method growth -- ironclad's unrolled
	 * {@code update-sha256-block} nearly doubled, past the 8000-byte JIT cliff the
	 * outlining exists to avoid. A loop body assigns at a handful of sites.
	 */
	private static final int MAX_LET_BODY_ASSIGN_SITES = 100;

	/**
	 * {@link JvmLetCompiler}'s eligibility scan over the parts it cannot see locally:
	 * some assignment (the init, or a {@code setq}/{@code setf} pair found by a
	 * shadowing-blind body walk) is integer-shaped, the site count stays under
	 * {@link #MAX_RAW_ASSIGN_SITES}, the name is not a promoted top-level global (a
	 * nested assignment inside a top-level form gives the name a global backing store
	 * other code reads -- the two homes must not diverge), and the body defines no nested
	 * {@code defun} ({@code FreeVarAnalyzer.findCapturedVars} skips {@code defun} by
	 * design, so a closure-over-let function body would resolve the name elsewhere). A
	 * false positive only costs a shadow store; a false negative only costs the fast
	 * path.
	 */
	static boolean rawBindingEligible(String name, LispVal init, List<LispVal> bodyForms, JvmLispCompiler.Ctx ctx) {
		if (!enabled(ctx) || ctx.globals.contains(name)) {
			return false;
		}
		if (JvmLispCompiler.containsDouble(init)) {
			// A float initializer settles the representation: mandelbrot's (let ((zr
			// 0.0d0)) ...) accumulators are Doubles for the whole loop, and a raw long
			// slot they never fill only adds _ubRead to every read of them.
			return false;
		}
		int[] rawShapedAndSites = new int[3];
		for (LispVal form : bodyForms) {
			if (!scanAssigns(form, name, ctx, rawShapedAndSites)) {
				return false;
			}
		}
		if (rawShapedAndSites[1] > MAX_RAW_ASSIGN_SITES || rawShapedAndSites[2] > MAX_LET_BODY_ASSIGN_SITES) {
			return false;
		}
		// At least one integer-shaped BODY assignment, not merely an integer init: an
		// init-only binding is boxed once either way, so the dual representation would
		// pay its per-site dispatch bytes for nothing -- ironclad's functional
		// round-temp chains are that shape, and they nearly doubled update-sha512-block
		// before this required a reassignment. Loop counters and accumulators (the
		// shapes the representation exists for) are always reassigned.
		return rawShapedAndSites[0] > 0;
	}

	/**
	 * Walks one form counting {@code setq}/{@code setf} sites of {@code name} (into
	 * {@code out[1]}, integer-shaped ones also into {@code out[0]}) and of ANY name
	 * ({@code out[2]}); answers false on a nested function definition, which vetoes the
	 * binding outright.
	 */
	private static boolean scanAssigns(LispVal form, String name, JvmLispCompiler.Ctx ctx, int[] out) {
		if (!(form instanceof LispCons cons)) {
			return true;
		}
		if (cons.car() instanceof LispSymbol head) {
			if (LispNames.QUOTE.equals(head.name())) {
				return true;
			}
			if (LispNames.DEFUN.equals(head.name()) || LispNames.ASYNC_DEFUN.equals(head.name())
					|| LispNames.ASYNC_DEFUN_QUALIFIED.equals(head.name())) {
				return false;
			}
			if (cons.isProperList() && (LispNames.SETQ.equals(head.name()) || LispNames.SETF.equals(head.name()))) {
				List<LispVal> parts = cons.toList();
				for (int i = 1; i + 1 < parts.size(); i += 2) {
					out[2]++;
					if (parts.get(i) instanceof LispSymbol target && name.equals(target.name())) {
						out[1]++;
						if (isRawAssignShaped(parts.get(i + 1), ctx)) {
							out[0]++;
						}
					}
				}
			}
		}
		LispVal cur = cons;
		while (cur instanceof LispCons cell) {
			if (!scanAssigns(cell.car(), name, ctx, out)) {
				return false;
			}
			cur = cell.cdr();
		}
		return true;
	}

	/**
	 * Compiles an assignment into an unboxed local. The value classifies as an integer
	 * tree: the outlined fused method computes it (boxed at its root), and the site
	 * dispatches on the RESULT's type -- a {@code Long} fills the raw slot and sets the
	 * flag, anything else (overflow promotion, a float, nil) lands boxed in the shadow,
	 * which is then authoritative. Leaves NOTHING on the stack; the caller re-reads
	 * through {@link #emitRawLocalBoxedRead} when the assignment's value is needed.
	 */
	static void compileRawStore(LispVal expr, JvmLispCompiler.Ctx ctx, String className, RawLocal target) {
		if (expr instanceof LispInteger lit) {
			JvmEmitHelper.emitRawLong(lit.value(), ctx);
			emitRawSlotStore(target, ctx);
			return;
		}
		if (expr instanceof LispSymbol sym && ctx.rawLocals.get(sym.name()) instanceof RawLocal src) {
			// A raw-to-raw copy ((setq a b) with both unboxed) transfers ALL slots:
			// total for every tier, so no guard and no dispatch.
			ctx.emit(Opcode.LLOAD);
			ctx.emit(src.longSlot());
			ctx.emit(Opcode.LSTORE);
			ctx.emit(target.longSlot());
			ctx.emit(Opcode.ALOAD);
			ctx.emit(src.shadowSlot());
			ctx.emit(Opcode.ASTORE);
			ctx.emit(target.shadowSlot());
			ctx.emit(Opcode.ILOAD);
			ctx.emit(src.flagSlot());
			ctx.emit(Opcode.ISTORE);
			ctx.emit(target.flagSlot());
			return;
		}
		if (enabled(ctx)) {
			Site site = new Site(expr);
			Node root = classify(expr, ctx, Map.of(), site, 0);
			if (root instanceof ConstLeaf c) {
				// A tree folded to a literal: the raw value directly.
				JvmEmitHelper.emitRawLong(c.value(), ctx);
				emitRawSlotStore(target, ctx);
				return;
			}
			if ((root instanceof OpNode || root instanceof ArefLeaf) && countOps(root) <= MAX_OPS
					&& site.leaves.size() <= MAX_EXPR_LEAVES) {
				MethodrefConstant ref = methodFor(root, site.leaves, -1, ctx);
				pushLeaves(site.leaves, ctx, className);
				ctx.emit(Opcode.INVOKESTATIC);
				ctx.emitU2(ref.index());
				// Dispatch on the VALUE's type, not on which path computed it: a Long
				// is the raw representation whichever path answered it.
				int tmp = ctx.allocTemp();
				ctx.emit(Opcode.ASTORE);
				ctx.emit(tmp);
				ctx.emit(Opcode.ALOAD);
				ctx.emit(tmp);
				ctx.emit(Opcode.INSTANCEOF);
				ctx.emitU2(ctx.longClass.index());
				int notLong = ctx.code.size();
				ctx.emit(Opcode.IFEQ);
				ctx.emitU2(0);
				ctx.emit(Opcode.ALOAD);
				ctx.emit(tmp);
				JvmEmitHelper.unboxLong(ctx);
				emitRawSlotStore(target, ctx);
				int done = ctx.code.size();
				ctx.emit(Opcode.GOTO);
				ctx.emitU2(0);
				JvmEmitHelper.patchBranch(ctx, notLong, ctx.code.size());
				ctx.emit(Opcode.ALOAD);
				ctx.emit(tmp);
				emitShadowSlotStore(target, ctx);
				JvmEmitHelper.patchBranch(ctx, done, ctx.code.size());
				return;
			}
		}
		// Not an integer tree at all: the boxed value lands in the shadow, which is
		// then authoritative -- lists, floats, nil, anything.
		JvmExprCompiler.compileExpr(expr, ctx, className);
		emitShadowSlotStore(target, ctx);
	}

	/** Raw {@code long} on the stack -> the raw slot; the flag marks it authoritative. */
	private static void emitRawSlotStore(RawLocal target, JvmLispCompiler.Ctx ctx) {
		ctx.emit(Opcode.LSTORE);
		ctx.emit(target.longSlot());
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.ISTORE);
		ctx.emit(target.flagSlot());
	}

	/** Boxed value on the stack -> the shadow slot; the flag marks the raw slot stale. */
	private static void emitShadowSlotStore(RawLocal target, JvmLispCompiler.Ctx ctx) {
		ctx.emit(Opcode.ASTORE);
		ctx.emit(target.shadowSlot());
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.ISTORE);
		ctx.emit(target.flagSlot());
	}

	/**
	 * Reads an unboxed local as an ordinary boxed value, through the shared
	 * {@code _ubRead} helper: the shadow unless the flag says the raw slot is
	 * authoritative, else the raw {@code long} boxed.
	 */
	static void emitRawLocalBoxedRead(RawLocal raw, JvmLispCompiler.Ctx ctx) {
		State state = java.util.Objects.requireNonNull(ctx.fusedState);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(raw.shadowSlot());
		ctx.emit(Opcode.LLOAD);
		ctx.emit(raw.longSlot());
		ctx.emit(Opcode.ILOAD);
		ctx.emit(raw.flagSlot());
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ubReadRef(ctx, state).index());
	}

	private static MethodrefConstant ubReadRef(JvmLispCompiler.Ctx ctx, State state) {
		state.usesUbRead = true;
		return JvmEmitHelper.selfMethod(ctx, state.className, "_ubRead", "(Ljava/lang/Object;JI)Ljava/lang/Object;");
	}

	private static MethodrefConstant fxAshRef(JvmLispCompiler.Ctx ctx, State state) {
		state.usesFxAsh = true;
		return JvmEmitHelper.selfMethod(ctx, state.className, "_fxAsh", "(JJ)J");
	}

	// ------------------------------------------------------------- inlinable functions

	/**
	 * Whether a defun qualifies for fused-call substitution: fixed arity and a single
	 * body expression (after any leading {@code declare}s) that is a CLOSED
	 * integer-operation tree over the parameters. Closedness is what makes the
	 * substitution hygienic and the pure-operator whitelist what makes the fallback's
	 * recomputation safe. Uniqueness of the definition is checked by the caller in
	 * {@link JvmLispCompiler}.
	 */
	static boolean isInlinableDefun(JvmLispCompiler.DefunDecl defun) {
		if (defun.variadic()) {
			return false;
		}
		for (String param : defun.paramNames()) {
			if (param.startsWith("&")) {
				return false;
			}
		}
		LispVal body = singleBodyExpr(defun.bodyExprs());
		return body != null && isClosedIntTree(body, defun.paramNames(), null);
	}

	/**
	 * Classifies a {@code let}-init lambda form as an inlinable local function, or
	 * returns {@code null}. The flet lowering wraps the body in {@code (block name
	 * expr)}; the block is transparent here because an exit form could never pass the
	 * closed-integer-tree check.
	 */
	@Nullable static LocalIntLambda eligibleLocalLambda(LispCons lambdaCons, JvmLispCompiler.Ctx ctx) {
		if (!lambdaCons.isProperList()) {
			return null;
		}
		List<LispVal> parts = lambdaCons.toList();
		if (parts.size() < 3 || !(parts.get(0) instanceof LispSymbol head) || !LispNames.LAMBDA.equals(head.name())) {
			return null;
		}
		List<String> params = new ArrayList<>();
		if (parts.get(1) instanceof LispCons paramCons) {
			if (!paramCons.isProperList()) {
				return null;
			}
			for (LispVal p : paramCons.toList()) {
				if (!(p instanceof LispSymbol ps) || ps.name().startsWith("&")) {
					return null;
				}
				params.add(ps.name());
			}
		}
		else if (!(parts.get(1) instanceof am.ik.rontolisp.LispNil)) {
			return null;
		}
		LispVal body = singleBodyExpr(parts.subList(2, parts.size()));
		if (body instanceof LispCons bodyCons && bodyCons.isProperList() && bodyCons.car() instanceof LispSymbol h
				&& LispNames.BLOCK.equals(h.name())) {
			// The block body may carry leading (declare ...) forms; skipping them is
			// what singleBodyExpr already does for the lambda level.
			List<LispVal> blockParts = bodyCons.toList();
			body = blockParts.size() >= 3 ? singleBodyExpr(blockParts.subList(2, blockParts.size())) : null;
		}
		if (body == null || !isClosedIntTree(body, params, ctx)) {
			return null;
		}
		return new LocalIntLambda(params, body);
	}

	@Nullable private static LispVal singleBodyExpr(List<LispVal> bodyExprs) {
		LispVal single = null;
		for (LispVal expr : bodyExprs) {
			if (expr instanceof LispCons cons && cons.car() instanceof LispSymbol head
					&& LispNames.DECLARE.equals(head.name())) {
				continue;
			}
			if (single != null) {
				return null;
			}
			single = expr;
		}
		return single;
	}

	/**
	 * With a non-null {@code ctx}, a call to a fusion-inlinable defun also qualifies as a
	 * tree node (a local function like sigma0 wraps {@code rol32}); defun eligibility
	 * itself always passes {@code null} -- it is decided before any {@code Ctx} exists,
	 * keeping defun bodies self-contained and order-independent.
	 */
	private static boolean isClosedIntTree(LispVal expr, List<String> params, JvmLispCompiler.@Nullable Ctx ctx) {
		if (expr instanceof LispInteger) {
			return true;
		}
		if (expr instanceof LispSymbol sym) {
			return params.contains(sym.name());
		}
		if (!(expr instanceof LispCons cons) || !cons.isProperList() || !(cons.car() instanceof LispSymbol head)) {
			return false;
		}
		List<LispVal> parts = cons.toList();
		int arity = parts.size() - 1;
		boolean headOk = switch (head.name()) {
			case LispNames.ADD, LispNames.SUB, LispNames.MUL, LispNames.LOGAND, LispNames.LOGIOR, LispNames.LOGXOR ->
				arity >= 2;
			case LispNames.MOD, LispNames.REM, LispNames.ASH, LispNames.AREF, LispNames.BYTE, LispNames.LDB ->
				arity == 2;
			case LispNames.LOGNOT, LispNames.ONE_PLUS, LispNames.ONE_MINUS -> arity == 1;
			default -> false;
		};
		if (!headOk) {
			if (ctx == null) {
				return false;
			}
			JvmLispCompiler.DefunDecl defun = ctx.inlinableDefuns.get(head.name());
			if (defun == null || arity != defun.paramNames().size()) {
				return false;
			}
		}
		for (int i = 1; i < parts.size(); i++) {
			if (!isClosedIntTree(parts.get(i), params, ctx)) {
				return false;
			}
		}
		return true;
	}

	// ------------------------------------------------------------------ classification

	/**
	 * Classifies an expression into a fusion tree. Anything that is not a literal integer
	 * or a fusable operation over fusable arguments becomes an {@link ExprLeaf}, compiled
	 * by the ordinary expression compiler at the call site and guarded at run time. Leaf
	 * nodes are REGISTERED at creation, so leaf evaluation order is the source order of
	 * the ORIGINAL call arguments -- inlined bodies reuse the already-registered argument
	 * nodes and add nothing.
	 */
	@Nullable private static Node classify(LispVal expr, JvmLispCompiler.Ctx ctx, Map<String, Node> env, Site site, int depth) {
		List<Node> leaves = site.leaves;
		if (expr instanceof LispInteger i) {
			return new ConstLeaf(i.value());
		}
		if (expr instanceof LispSymbol sym) {
			Node bound = env.get(sym.name());
			if (bound != null) {
				return bound;
			}
			if (!env.isEmpty()) {
				// A free symbol inside an inlined body would compile in the CALLER's
				// scope -- a hygiene violation. Bodies are pre-checked closed, so this
				// is a defensive bail, not a reachable path.
				return null;
			}
			RawLocal raw = ctx.rawLocals.get(sym.name());
			if (raw != null) {
				if (!site.assignedNames.contains(sym.name())) {
					// No leaf in this site can reassign the local, so every occurrence
					// shares ONE snapshot instead of paying its own.
					RawLeaf shared = site.sharedRawLeaves.get(sym.name());
					if (shared == null) {
						shared = new RawLeaf(raw);
						site.sharedRawLeaves.put(sym.name(), shared);
						registerLeaf(shared, leaves);
					}
					return shared;
				}
				return registerLeaf(new RawLeaf(raw), leaves);
			}
		}
		if (!(expr instanceof LispCons cons) || !cons.isProperList() || !(cons.car() instanceof LispSymbol sym)) {
			return registerLeaf(new ExprLeaf(expr), leaves);
		}
		List<LispVal> parts = cons.toList();
		int arity = parts.size() - 1;
		String op = sym.name();
		if (LispNames.AREF.equals(op) && arity == 2 && env.isEmpty() && ctx.usesArrays) {
			// A rank-1 aref where a packed representation can exist: the fast path
			// reads the long[] element raw. Inside an inlined body (env non-empty) the
			// operands may be parameter references, which the argument-position
			// ArefLeaf cannot express; substituteCall handles the parameter-shaped
			// accessor case.
			return arefLeaf(parts.get(1), parts.get(2), ctx, site, depth);
		}
		if (LispNames.RANDOM.equals(op) && arity == 1 && env.isEmpty()) {
			RandomLeaf leaf = randomLeaf(parts, ctx);
			if (leaf != null) {
				return registerLeaf(leaf, leaves);
			}
		}
		if (LispNames.LDB.equals(op) && arity == 2) {
			// (ldb (byte s p) x) with a literal byte spec lowers to its pure
			// logand/ash expansion, which classifies as an ordinary subtree.
			if (parts.get(1) instanceof LispCons spec && spec.car() instanceof LispSymbol specHead
					&& LispNames.BYTE.equals(specHead.name()) && spec.cdr() instanceof LispCons sCell
					&& sCell.car() instanceof LispInteger && sCell.cdr() instanceof LispCons pCell
					&& pCell.car() instanceof LispInteger && pCell.cdr() instanceof am.ik.rontolisp.LispNil) {
				return classify(LispMacroExpander.expandLdb(cons), ctx, env, site, depth);
			}
			return env.isEmpty() ? registerLeaf(new ExprLeaf(expr), leaves) : null;
		}
		if (LispNames.FUNCALL.equals(op) && arity >= 1 && parts.get(1) instanceof LispSymbol fvar
				&& !env.containsKey(fvar.name()) && depth < MAX_INLINE_DEPTH) {
			// (funcall __FLETn_f args...) of a let-bound local function (the flet
			// lowering): substitute its body exactly like an inlinable defun's. The
			// function-position read of the variable is pure, so eliding it is
			// unobservable.
			LocalIntLambda lambda = ctx.localIntLambdas.get(fvar.name());
			if (lambda != null && arity - 1 == lambda.params().size()) {
				Node substituted = substituteCall(lambda.params(), lambda.body(), parts.subList(2, parts.size()), ctx,
						env, site, depth);
				if (substituted != null) {
					return substituted;
				}
			}
		}
		JvmLispCompiler.DefunDecl inlinable = ctx.inlinableDefuns.get(op);
		if (inlinable != null && arity == inlinable.paramNames().size() && depth < MAX_INLINE_DEPTH) {
			LispVal body = java.util.Objects.requireNonNull(singleBodyExpr(inlinable.bodyExprs()));
			Node substituted = substituteCall(inlinable.paramNames(), body, parts.subList(1, parts.size()), ctx, env,
					site, depth);
			if (substituted != null) {
				return substituted;
			}
			// The body did not classify under this env (a parameter-shaped aref, say):
			// fall through to the ordinary leaf treatment of the call itself.
		}
		boolean fusable = switch (op) {
			case LispNames.ADD, LispNames.SUB, LispNames.MUL, LispNames.LOGAND, LispNames.LOGIOR, LispNames.LOGXOR ->
				arity >= 2;
			case LispNames.MOD, LispNames.REM, LispNames.ASH -> arity == 2;
			case LispNames.LOGNOT, LispNames.ONE_PLUS, LispNames.ONE_MINUS -> arity == 1;
			default -> false;
		};
		if (!fusable) {
			// Inside an inlined body every form must classify; at the top level it is
			// an ordinary guarded leaf.
			return env.isEmpty() ? registerLeaf(new ExprLeaf(expr), leaves) : null;
		}
		if (JvmLispCompiler.hasDoubleLiteral(parts)) {
			// The double-literal routing predicate the per-op compilers read: such a
			// node takes the unboxed-double path today and keeps it (as a leaf here).
			return env.isEmpty() ? registerLeaf(new ExprLeaf(expr), leaves) : null;
		}
		for (int i = 1; i < parts.size(); i++) {
			// An immediate big-integer or ratio literal makes the fast path pointless
			// (the guard would fail every time); the generic compiler owns those.
			if (parts.get(i) instanceof am.ik.rontolisp.LispBigInteger
					|| parts.get(i) instanceof am.ik.rontolisp.LispRatio) {
				return env.isEmpty() ? registerLeaf(new ExprLeaf(expr), leaves) : null;
			}
		}
		List<Node> args = new ArrayList<>(arity);
		for (int i = 1; i < parts.size(); i++) {
			Node arg = classify(parts.get(i), ctx, env, site, depth);
			if (arg == null) {
				return null;
			}
			args.add(arg);
		}
		return switch (op) {
			case LispNames.ONE_PLUS -> makeOp(LispNames.ADD, List.of(args.get(0), new ConstLeaf(1)));
			case LispNames.ONE_MINUS -> makeOp(LispNames.SUB, List.of(args.get(0), new ConstLeaf(1)));
			default -> makeOp(op, args);
		};
	}

	/**
	 * Builds an operation node, folding it to a constant when every argument is a literal
	 * and the exact result fits a {@code long}. Folding never changes a result: it
	 * computes exactly what the fast path would (and bails to the ordinary node on
	 * overflow or a zero divisor, so promotion/error behavior is preserved).
	 */
	private static Node makeOp(String op, List<Node> args) {
		for (Node arg : args) {
			if (!(arg instanceof ConstLeaf)) {
				return new OpNode(op, args);
			}
		}
		try {
			long acc = ((ConstLeaf) args.get(0)).value();
			if (LispNames.LOGNOT.equals(op)) {
				return new ConstLeaf(~acc);
			}
			for (int i = 1; i < args.size(); i++) {
				long v = ((ConstLeaf) args.get(i)).value();
				acc = switch (op) {
					case LispNames.ADD -> Math.addExact(acc, v);
					case LispNames.SUB -> Math.subtractExact(acc, v);
					case LispNames.MUL -> Math.multiplyExact(acc, v);
					case LispNames.LOGAND -> acc & v;
					case LispNames.LOGIOR -> acc | v;
					case LispNames.LOGXOR -> acc ^ v;
					case LispNames.MOD -> Math.floorMod(acc, v);
					case LispNames.REM -> acc % v;
					case LispNames.ASH -> foldAsh(acc, v);
					default -> throw new ArithmeticException("not foldable");
				};
			}
			return new ConstLeaf(acc);
		}
		catch (ArithmeticException overflowOrZeroDivide) {
			return new OpNode(op, args);
		}
	}

	private static long foldAsh(long value, long count) {
		int c = (int) count;
		if (count != c) {
			// The runtime narrows the count with L2I before anything else; folding a
			// literal that narrowing would change is left to the runtime path.
			throw new ArithmeticException("count out of int");
		}
		if (c <= -64) {
			return value >> 63;
		}
		if (c <= 0) {
			return value >> -c;
		}
		if (c > 62) {
			throw new ArithmeticException("shift out of long");
		}
		long shifted = value << c;
		if ((shifted >> c) != value) {
			throw new ArithmeticException("shift out of long");
		}
		return shifted;
	}

	/**
	 * Substitutes an inlinable body with the parameters bound to the classified
	 * arguments. Each argument is classified ONCE (registering its leaves in source
	 * order) and the resulting node is shared across every occurrence of its parameter.
	 * On failure the leaves registered by this attempt are rolled back, so the caller's
	 * fall-through leaf treatment does not ALSO evaluate them.
	 */
	@Nullable private static Node substituteCall(List<String> params, LispVal body, List<LispVal> args, JvmLispCompiler.Ctx ctx,
			Map<String, Node> env, Site site, int depth) {
		List<Node> leaves = site.leaves;
		// An accessor-shaped body -- exactly (aref P I) over parameters/literals -- maps
		// straight onto an ArefLeaf over the CALLER's operand expressions, provided each
		// is a bare symbol or literal at the top env (pure, so re-reading and eliding
		// the call are unobservable). This lets a typed-struct accessor call read a
		// packed vector's element raw inside a fused tree.
		if (env.isEmpty() && ctx.usesArrays && body instanceof LispCons bodyCons && bodyCons.isProperList()
				&& bodyCons.car() instanceof LispSymbol bodyHead && LispNames.AREF.equals(bodyHead.name())) {
			List<LispVal> bodyParts = bodyCons.toList();
			if (bodyParts.size() == 3) {
				LispVal arr = inlineArefOperand(bodyParts.get(1), params, args);
				LispVal idx = inlineArefOperand(bodyParts.get(2), params, args);
				if (arr != null && idx != null) {
					return arefLeaf(arr, idx, ctx, site, depth);
				}
			}
		}
		int mark = leaves.size();
		Map<String, Node> callEnv = new HashMap<>();
		Node substituted = null;
		for (int i = 0; i < params.size(); i++) {
			Node argNode = classify(args.get(i), ctx, env, site, depth);
			if (argNode == null) {
				break;
			}
			callEnv.put(params.get(i), argNode);
		}
		if (callEnv.size() == params.size()) {
			substituted = classify(body, ctx, callEnv, site, depth + 1);
		}
		if (substituted == null) {
			leaves.subList(mark, leaves.size()).clear();
		}
		return substituted;
	}

	/**
	 * Builds and registers a rank-1 aref leaf. The leaf registers BEFORE its index, so
	 * the call site pushes the array first and the index's own leaves after it -- the
	 * generic {@code (aref a i)} argument order.
	 */
	private static Node arefLeaf(LispVal arrayExpr, LispVal indexExpr, JvmLispCompiler.Ctx ctx, Site site, int depth) {
		ArefLeaf leaf = new ArefLeaf(arrayExpr);
		registerLeaf(leaf, site.leaves);
		leaf.indexNode = arefIndexNode(indexExpr, ctx, site, depth);
		return leaf;
	}

	/**
	 * The index of an aref leaf as a node the prologue can resolve into a raw
	 * {@code long} slot (or a constant). A literal, a symbol (an unboxed local's slot
	 * triple, else an ordinary guarded argument) and a {@code random} draw classify;
	 * anything else -- an arithmetic index, a call -- becomes one opaque guarded
	 * argument, exactly the boxed index the leaf always took.
	 */
	private static Node arefIndexNode(LispVal indexExpr, JvmLispCompiler.Ctx ctx, Site site, int depth) {
		boolean resolvable = indexExpr instanceof LispInteger || indexExpr instanceof LispSymbol
				|| (indexExpr instanceof LispCons cons && cons.isProperList() && cons.car() instanceof LispSymbol head
						&& LispNames.RANDOM.equals(head.name()) && cons.toList().size() == 2);
		if (!resolvable) {
			return registerLeaf(new ExprLeaf(indexExpr), site.leaves);
		}
		Node node = classify(indexExpr, ctx, Map.of(), site, depth);
		if (!(node instanceof ConstLeaf || node instanceof ExprLeaf || node instanceof RawLeaf
				|| node instanceof RandomLeaf)) {
			throw new IllegalStateException("aref index did not resolve to a slot: " + indexExpr);
		}
		return node;
	}

	/**
	 * A {@code (random <limit>)} leaf, or {@code null} when the generic {@code _random}
	 * keeps the form: a float limit (whose result is a Double), and a big-integer or
	 * ratio literal, which the fast path's {@code Long} formula cannot answer.
	 */
	@Nullable private static RandomLeaf randomLeaf(List<LispVal> parts, JvmLispCompiler.Ctx ctx) {
		if (!enabled(ctx) || JvmLispCompiler.hasDoubleLiteral(parts)) {
			return null;
		}
		LispVal limit = parts.get(1);
		if (limit instanceof LispInteger lit) {
			return new RandomLeaf(null, lit.value());
		}
		if (limit instanceof am.ik.rontolisp.LispBigInteger || limit instanceof am.ik.rontolisp.LispRatio) {
			return null;
		}
		return new RandomLeaf(limit, 0);
	}

	private static Node registerLeaf(Node leaf, List<Node> leaves) {
		leaves.add(leaf);
		return leaf;
	}

	@Nullable private static LispVal inlineArefOperand(LispVal operand, List<String> params, List<LispVal> args) {
		if (operand instanceof LispInteger) {
			return operand;
		}
		if (operand instanceof LispSymbol sym) {
			int i = params.indexOf(sym.name());
			if (i >= 0 && i < args.size()
					&& (args.get(i) instanceof LispSymbol || args.get(i) instanceof LispInteger)) {
				return args.get(i);
			}
		}
		return null;
	}

	/** Counts fused operations (an n-ary node left-folds into arity - 1 binary ops). */
	private static int countOps(Node node) {
		return switch (node) {
			case ExprLeaf ignored -> 0;
			case ArefLeaf ignored -> 0;
			case RandomLeaf ignored -> 0;
			case RawLeaf ignored -> 0;
			case ConstLeaf ignored -> 0;
			case OpNode op -> {
				int ops = CMP_ROOT.equals(op.op()) ? 1 : Math.max(1, op.args().size() - 1);
				for (Node arg : op.args()) {
					ops += countOps(arg);
				}
				yield ops;
			}
		};
	}

	private static boolean hasRawRead(List<Node> leaves) {
		for (Node leaf : leaves) {
			if (leaf instanceof RawLeaf || leaf instanceof ArefLeaf || leaf instanceof RandomLeaf) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasConstOperand(Node root) {
		return root instanceof OpNode op && op.args().stream().anyMatch(arg -> arg instanceof ConstLeaf);
	}

	// -------------------------------------------------------- the outlined method

	/**
	 * The method reference for this tree shape, minting (and queueing for the fused pass)
	 * a new {@code _fx$N} only when no structurally identical site exists yet.
	 */
	private static MethodrefConstant methodFor(Node root, List<Node> leaves, int cmpMask, JvmLispCompiler.Ctx ctx) {
		State state = java.util.Objects.requireNonNull(ctx.fusedState);
		StringBuilder desc = new StringBuilder("(");
		for (Node leaf : leaves) {
			switch (leaf) {
				case ExprLeaf ignored -> desc.append("Ljava/lang/Object;");
				case ArefLeaf ignored -> desc.append("Ljava/lang/Object;");
				case RandomLeaf l -> desc.append(l.limitExpr == null ? "" : "Ljava/lang/Object;");
				case RawLeaf ignored -> desc.append("JLjava/lang/Object;I");
				default -> throw new IllegalStateException("not a registered leaf: " + leaf);
			}
		}
		desc.append(")").append(cmpMask >= 0 ? "I" : "Ljava/lang/Object;");
		String key = cmpMask + "|" + desc + "|" + structureKey(root, leaves);
		MethodrefConstant existing = state.byKey.get(key);
		if (existing != null) {
			return existing;
		}
		String name = "_fx$" + state.nextId++;
		Utf8Constant nameUtf8 = ctx.cp.addUtf8(name);
		Utf8Constant descUtf8 = ctx.cp.addUtf8(desc.toString());
		MethodrefConstant ref = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8(state.className)),
				ctx.cp.addNameAndType(nameUtf8, descUtf8));
		state.byKey.put(key, ref);
		state.pending.add(new Pending(ref, nameUtf8, descUtf8, root, leaves, cmpMask));
		return ref;
	}

	/** A deterministic structural serialization: leaves by ordinal, ops by name. */
	private static String structureKey(Node root, List<Node> leaves) {
		StringBuilder sb = new StringBuilder();
		appendKey(root, leaves, sb);
		return sb.toString();
	}

	private static void appendKey(Node node, List<Node> leaves, StringBuilder sb) {
		switch (node) {
			case ConstLeaf c -> sb.append('#').append(c.value());
			case OpNode op -> {
				sb.append('(').append(op.op());
				for (Node arg : op.args()) {
					sb.append(' ');
					appendKey(arg, leaves, sb);
				}
				sb.append(')');
			}
			case ExprLeaf leaf -> sb.append('e').append(leafIndex(leaf, leaves));
			case ArefLeaf leaf -> {
				sb.append('a').append(leafIndex(leaf, leaves)).append('[');
				appendKey(java.util.Objects.requireNonNull(leaf.indexNode), leaves, sb);
				sb.append(']');
			}
			case RandomLeaf leaf -> sb.append('n')
				.append(leafIndex(leaf, leaves))
				.append(leaf.limitExpr == null ? "#" + leaf.limitConst : "");
			case RawLeaf leaf -> sb.append('r').append(leafIndex(leaf, leaves));
		}
	}

	private static int leafIndex(Node leaf, List<Node> leaves) {
		for (int i = 0; i < leaves.size(); i++) {
			if (leaves.get(i) == leaf) {
				return i;
			}
		}
		throw new IllegalStateException("leaf not registered");
	}

	/**
	 * Evaluates every non-constant leaf ONCE, left to right (the same observable order as
	 * the generic path's argument evaluation), as the outlined call's arguments. An aref
	 * leaf evaluates its array then its index, exactly like the generic aref argument
	 * order; a raw-local leaf pushes its (raw, shadow) slot pair, which IS the snapshot
	 * -- a later leaf's side effect cannot change what this read observed.
	 */
	private static void pushLeaves(List<Node> leaves, JvmLispCompiler.Ctx ctx, String className) {
		for (Node node : leaves) {
			switch (node) {
				case ExprLeaf leaf -> JvmExprCompiler.compileExpr(leaf.expr, ctx, className);
				case ArefLeaf leaf -> JvmExprCompiler.compileExpr(leaf.arrayExpr, ctx, className);
				case RandomLeaf leaf -> {
					if (leaf.limitExpr != null) {
						JvmExprCompiler.compileExpr(leaf.limitExpr, ctx, className);
					}
				}
				case RawLeaf leaf -> {
					ctx.emit(Opcode.LLOAD);
					ctx.emit(leaf.src.longSlot());
					ctx.emit(Opcode.ALOAD);
					ctx.emit(leaf.src.shadowSlot());
					ctx.emit(Opcode.ILOAD);
					ctx.emit(leaf.src.flagSlot());
				}
				default -> throw new IllegalStateException("not a registered leaf: " + node);
			}
		}
	}

	// ------------------------------------------------------------- method body pass

	/**
	 * Emits one pending fused method's body into a fresh context (the compiler's fused
	 * pass, after every program body is compiled): guards + unboxes each leaf once, the
	 * raw fast path under an {@code ArithmeticException} region whose handler is the
	 * bail, and the generic-helper fallback.
	 */
	static void emitMethodBody(Pending pending, JvmLispCompiler.Ctx ctx, String className) {
		State state = java.util.Objects.requireNonNull(ctx.fusedState);
		// Parameter slots in leaf order.
		int slot = 0;
		for (Node leaf : pending.leaves()) {
			switch (leaf) {
				case ExprLeaf l -> l.paramSlot = slot++;
				case ArefLeaf l -> l.arrParam = slot++;
				case RandomLeaf l -> {
					if (l.limitExpr != null) {
						l.limitParam = slot++;
					}
				}
				case RawLeaf l -> {
					l.rawParam = slot;
					slot += 2;
					l.shadowParam = slot++;
					l.flagParam = slot++;
				}
				default -> throw new IllegalStateException("not a registered leaf: " + leaf);
			}
		}
		ctx.nextLocal = slot;
		ctx.maxLocals = Math.max(ctx.maxLocals, slot);
		List<Integer> bails = new ArrayList<>();
		ClassConstant longArrayClass = ctx.cp.addClass(ctx.cp.addUtf8("[J"));
		// Every random leaf draws ONCE, here, before any guard and without any bail of
		// its own: a leaf whose limit is not a Long takes its draw through _random and
		// raises the shared bail flag instead of jumping, so no draw can be skipped and
		// the fallback never draws (it must not -- it re-emits, and a substituted
		// parameter used twice re-emits twice). One test of the flag after the draws
		// is the bail.
		int bailFlag = -1;
		int limitScratch = -1;
		for (Node leaf : pending.leaves()) {
			if (leaf instanceof RandomLeaf l && l.limitExpr != null) {
				// One shared scratch pair for every non-literal limit: each draw
				// unboxes into it and reads it back immediately.
				limitScratch = ctx.allocTemp();
				ctx.allocTemp();
				bailFlag = ctx.allocTemp();
				ctx.emit(Opcode.ICONST_0);
				ctx.emit(Opcode.ISTORE);
				ctx.emit(bailFlag);
				break;
			}
		}
		for (Node leaf : pending.leaves()) {
			if (leaf instanceof RandomLeaf l) {
				l.longSlot = ctx.allocTemp();
				ctx.allocTemp();
				if (l.limitExpr != null) {
					l.flagSlot = ctx.allocTemp();
					l.boxSlot = ctx.allocTemp();
				}
				emitRandomDraw(l, ctx, ctx.numOp(JvmNumericRuntimeBuilder.RANDOM), limitScratch, bailFlag);
			}
		}
		if (bailFlag >= 0) {
			ctx.emit(Opcode.ILOAD);
			ctx.emit(bailFlag);
			bails.add(branch(ctx, Opcode.IFNE));
		}
		for (Node leaf : pending.leaves()) {
			switch (leaf) {
				case ExprLeaf l -> {
					ctx.emit(Opcode.ALOAD);
					ctx.emit(l.paramSlot);
					ctx.emit(Opcode.INSTANCEOF);
					ctx.emitU2(ctx.longClass.index());
					bails.add(branch(ctx, Opcode.IFEQ));
					ctx.emit(Opcode.ALOAD);
					ctx.emit(l.paramSlot);
					JvmEmitHelper.unboxLong(ctx);
					l.longSlot = ctx.allocTemp();
					ctx.allocTemp();
					ctx.emit(Opcode.LSTORE);
					ctx.emit(l.longSlot);
				}
				case RawLeaf l -> {
					// Flag set: the raw param already holds the value. A Long shadow:
					// unbox into the raw param's slot (same numeric). Anything else:
					// bail.
					ctx.emit(Opcode.ILOAD);
					ctx.emit(l.flagParam);
					int isRaw = branch(ctx, Opcode.IFNE);
					ctx.emit(Opcode.ALOAD);
					ctx.emit(l.shadowParam);
					ctx.emit(Opcode.INSTANCEOF);
					ctx.emitU2(ctx.longClass.index());
					bails.add(branch(ctx, Opcode.IFEQ));
					ctx.emit(Opcode.ALOAD);
					ctx.emit(l.shadowParam);
					JvmEmitHelper.unboxLong(ctx);
					ctx.emit(Opcode.LSTORE);
					ctx.emit(l.rawParam);
					JvmEmitHelper.patchBranch(ctx, isRaw, ctx.code.size());
					l.longSlot = l.rawParam;
				}
				case ArefLeaf ignored -> {
					// Read in a later pass: the index resolves through another leaf's
					// slot, which this pass is still filling.
				}
				case RandomLeaf ignored -> {
					// Drawn above, before the guards.
				}
				default -> throw new IllegalStateException("not a registered leaf: " + leaf);
			}
		}
		ArefScratch arefScratch = null;
		for (Node leaf : pending.leaves()) {
			if (leaf instanceof ArefLeaf l) {
				if (arefScratch == null) {
					arefScratch = new ArefScratch(ctx.allocTemp(), ctx.allocTemp(), ctx.allocTemp());
				}
				emitArefRead(l, ctx, bails, longArrayClass, arefScratch);
			}
		}
		// The fast path, protected: an ArithmeticException (Math.*Exact overflow,
		// _fxAsh, a zero divisor) discards the partial operand stack and lands in the
		// bail, whose fallback recomputes generically -- including the generic error
		// shape for the zero divisor.
		int tryStart = ctx.code.size();
		if (pending.isCompare()) {
			OpNode root = (OpNode) pending.root();
			emitFast(root.args().get(0), ctx, state);
			emitFast(root.args().get(1), ctx, state);
			ctx.emit(Opcode.LCMP);
			emitCompareResult(branchForMask(pending.cmpMask()), ctx);
		}
		else {
			emitFast(pending.root(), ctx, state);
			JvmEmitHelper.boxLong(ctx);
			ctx.emit(Opcode.ARETURN);
		}
		int tryEnd = ctx.code.size();
		// The IEEE double fast path: the same tree over leaves that are all Doubles,
		// tried when the Long guards fail. It sits OUTSIDE the checked region -- an
		// overflow means the exact integer result did not fit, which the fallback owns,
		// not the doubles.
		List<Integer> fallbackBails = bails;
		if (doubleEligible(pending.root(), pending.leaves()) && ctx.nextLocal + 2 * pending.leaves().size() <= 250) {
			int doubleEntry = ctx.code.size();
			for (int pos : bails) {
				JvmEmitHelper.patchBranch(ctx, pos, doubleEntry);
			}
			List<Integer> doubleBails = new ArrayList<>();
			emitDoubleGuards(pending.leaves(), ctx, doubleBails);
			if (pending.isCompare()) {
				OpNode root = (OpNode) pending.root();
				emitFastDouble(root.args().get(0), ctx);
				emitFastDouble(root.args().get(1), ctx);
				int branchOpcode = branchForMask(pending.cmpMask());
				// javac's NaN rule, which is exactly the bitmask _cmpb answers: DCMPG
				// for < and <= (unordered falls out as +1, failing IFLT/IFLE), DCMPL
				// for the rest (unordered falls out as -1, failing IFEQ/IFGT/IFGE).
				ctx.emit(branchOpcode == Opcode.IFLT || branchOpcode == Opcode.IFLE ? Opcode.DCMPG : Opcode.DCMPL);
				emitCompareResult(branchOpcode, ctx);
			}
			else {
				emitFastDouble(pending.root(), ctx);
				JvmEmitHelper.boxDouble(ctx);
				ctx.emit(Opcode.ARETURN);
			}
			fallbackBails = doubleBails;
		}
		emitBailAndFallback(pending, ctx, state, fallbackBails, tryStart, tryEnd, className);
	}

	/**
	 * The prologue's {@code random} draw -- exactly one per leaf, on every path. A
	 * {@code Long} limit (and a literal one, which needs no argument at all) computes the
	 * same expression {@code _random} evaluates for it,
	 * {@code (long) (ThreadLocalRandom.current().nextDouble() * limit)}, straight into a
	 * raw slot, with the box on both ends gone; any other limit (a float reaching
	 * {@code random} through a variable) takes its ONE draw from {@code _random} into the
	 * boxed slot and raises the bail flag, so the tree falls back with the value already
	 * drawn.
	 */
	private static void emitRandomDraw(RandomLeaf leaf, JvmLispCompiler.Ctx ctx, MethodrefConstant randomHelper,
			int limitScratch, int bailFlag) {
		if (leaf.limitExpr == null) {
			emitDrawTimesDouble(ctx, -1, leaf.limitConst);
			ctx.emit(Opcode.LSTORE);
			ctx.emit(leaf.longSlot);
			return;
		}
		ctx.emit(Opcode.ALOAD);
		ctx.emit(leaf.limitParam);
		ctx.emit(Opcode.INSTANCEOF);
		ctx.emitU2(ctx.longClass.index());
		int notLong = branch(ctx, Opcode.IFEQ);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(leaf.limitParam);
		JvmEmitHelper.unboxLong(ctx);
		ctx.emit(Opcode.LSTORE);
		ctx.emit(limitScratch);
		emitDrawTimesDouble(ctx, limitScratch, 0);
		ctx.emit(Opcode.LSTORE);
		ctx.emit(leaf.longSlot);
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.ISTORE);
		ctx.emit(leaf.flagSlot);
		ctx.emit(Opcode.ACONST_NULL);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(leaf.boxSlot);
		int drawn = branch(ctx, Opcode.GOTO);
		JvmEmitHelper.patchBranch(ctx, notLong, ctx.code.size());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(leaf.limitParam);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(randomHelper.index());
		ctx.emit(Opcode.ASTORE);
		ctx.emit(leaf.boxSlot);
		JvmEmitHelper.emitRawLong(0, ctx);
		ctx.emit(Opcode.LSTORE);
		ctx.emit(leaf.longSlot);
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.ISTORE);
		ctx.emit(leaf.flagSlot);
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.ISTORE);
		ctx.emit(bailFlag);
		JvmEmitHelper.patchBranch(ctx, drawn, ctx.code.size());
	}

	/**
	 * {@code (long) (ThreadLocalRandom.current().nextDouble() * limit)} -- {@code
	 * _random}'s own Long-limit expression, over a raw slot or a constant.
	 */
	private static void emitDrawTimesDouble(JvmLispCompiler.Ctx ctx, int limitSlot, long limitConst) {
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.mathOp(JvmMathFnCompiler.TLR_CURRENT).index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(ctx.mathOp(JvmMathFnCompiler.TLR_NEXT_DOUBLE).index());
		if (limitSlot >= 0) {
			ctx.emit(Opcode.LLOAD);
			ctx.emit(limitSlot);
			ctx.emit(Opcode.L2D);
		}
		else {
			JvmEmitHelper.emitRawDouble(limitConst, ctx);
		}
		ctx.emit(Opcode.DMUL);
		ctx.emit(Opcode.D2L);
	}

	/**
	 * The prologue's raw rank-1 aref read, over whichever packed representation the
	 * program can hold: the bare {@code long[]} packed integer vector (elements from slot
	 * 1, past the width header) and the general array's length-6 header over a flat
	 * {@code long[]} (elements from slot 0, {@code Long.MIN_VALUE} for nil). Every other
	 * shape -- a string, a boxed general array, a displaced array, a character vector, an
	 * out-of-range index, a nil element -- bails into the same {@code _aref1} the unfused
	 * emission would have called.
	 */
	private static void emitArefRead(ArefLeaf leaf, JvmLispCompiler.Ctx ctx, List<Integer> bails,
			ClassConstant longArrayClass, ArefScratch scratch) {
		// idx = (int) <index> -- the same truncation _aref1's ((Long) i).intValue()
		// applies.
		int idxSlot = scratch.idxSlot();
		Node index = java.util.Objects.requireNonNull(leaf.indexNode);
		if (index instanceof ConstLeaf c) {
			JvmEmitHelper.emitIntConst(ctx, (int) c.value());
		}
		else {
			emitLongLoad(rawSlotOf(index), ctx);
			ctx.emit(Opcode.L2I);
		}
		ctx.emit(Opcode.ISTORE);
		ctx.emit(idxSlot);
		leaf.longSlot = ctx.allocTemp();
		ctx.allocTemp();
		List<Integer> done = new ArrayList<>();
		if (ctx.usesIntArray) {
			ctx.emit(Opcode.ALOAD);
			ctx.emit(leaf.arrParam);
			ctx.emit(Opcode.INSTANCEOF);
			ctx.emitU2(longArrayClass.index());
			int notPackedVector = branch(ctx, Opcode.IFEQ);
			ctx.emit(Opcode.ILOAD);
			ctx.emit(idxSlot);
			bails.add(branch(ctx, Opcode.IFLT));
			ctx.emit(Opcode.ILOAD);
			ctx.emit(idxSlot);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(leaf.arrParam);
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(longArrayClass.index());
			ctx.emit(Opcode.ARRAYLENGTH);
			ctx.emit(Opcode.ICONST_1);
			ctx.emit(Opcode.ISUB);
			bails.add(branch(ctx, Opcode.IF_ICMPGE));
			ctx.emit(Opcode.ALOAD);
			ctx.emit(leaf.arrParam);
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(longArrayClass.index());
			ctx.emit(Opcode.ICONST_1);
			ctx.emit(Opcode.ILOAD);
			ctx.emit(idxSlot);
			ctx.emit(Opcode.IADD);
			ctx.emit(Opcode.LALOAD);
			ctx.emit(Opcode.LSTORE);
			ctx.emit(leaf.longSlot);
			done.add(branch(ctx, Opcode.GOTO));
			JvmEmitHelper.patchBranch(ctx, notPackedVector, ctx.code.size());
		}
		if (ctx.usesArrays) {
			ClassConstant arrayListClass = ctx.cp.addClass(ctx.cp.addUtf8("java/util/ArrayList"));
			ClassConstant objectArrayClass = ctx.cp.addClass(ctx.cp.addUtf8("[Ljava/lang/Object;"));
			MethodrefConstant alSize = ctx.cp.addMethodref(arrayListClass,
					ctx.cp.addNameAndType(ctx.cp.addUtf8("size"), ctx.cp.addUtf8("()I")));
			MethodrefConstant alGet = ctx.cp.addMethodref(arrayListClass,
					ctx.cp.addNameAndType(ctx.cp.addUtf8("get"), ctx.cp.addUtf8("(I)Ljava/lang/Object;")));
			int headerSlot = scratch.headerSlot();
			int dataSlot = scratch.dataSlot();
			ctx.emit(Opcode.ALOAD);
			ctx.emit(leaf.arrParam);
			ctx.emit(Opcode.INSTANCEOF);
			ctx.emitU2(arrayListClass.index());
			bails.add(branch(ctx, Opcode.IFEQ));
			// The same "is this an array?" shape test _arrayp makes, so get(0) on an
			// ArrayList that is not one cannot throw past the bail.
			ctx.emit(Opcode.ALOAD);
			ctx.emit(leaf.arrParam);
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(arrayListClass.index());
			ctx.emit(Opcode.INVOKEVIRTUAL);
			ctx.emitU2(alSize.index());
			bails.add(branch(ctx, Opcode.IFEQ));
			ctx.emit(Opcode.ALOAD);
			ctx.emit(leaf.arrParam);
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(arrayListClass.index());
			ctx.emit(Opcode.ICONST_0);
			ctx.emit(Opcode.INVOKEVIRTUAL);
			ctx.emitU2(alGet.index());
			ctx.emit(Opcode.ASTORE);
			ctx.emit(headerSlot);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(headerSlot);
			ctx.emit(Opcode.INSTANCEOF);
			ctx.emitU2(objectArrayClass.index());
			bails.add(branch(ctx, Opcode.IFEQ));
			// Header length 6 IS the packed shape: 4 is a character vector, 5 a
			// displaced array, 3 the boxed general array -- all of them _aref1's.
			ctx.emit(Opcode.ALOAD);
			ctx.emit(headerSlot);
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(objectArrayClass.index());
			ctx.emit(Opcode.ARRAYLENGTH);
			JvmEmitHelper.emitIntConst(ctx, 6);
			bails.add(branch(ctx, Opcode.IF_ICMPNE));
			ctx.emit(Opcode.ALOAD);
			ctx.emit(headerSlot);
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(objectArrayClass.index());
			ctx.emit(Opcode.ICONST_5);
			ctx.emit(Opcode.AALOAD);
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(longArrayClass.index());
			ctx.emit(Opcode.ASTORE);
			ctx.emit(dataSlot);
			ctx.emit(Opcode.ILOAD);
			ctx.emit(idxSlot);
			bails.add(branch(ctx, Opcode.IFLT));
			ctx.emit(Opcode.ILOAD);
			ctx.emit(idxSlot);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(dataSlot);
			ctx.emit(Opcode.ARRAYLENGTH);
			bails.add(branch(ctx, Opcode.IF_ICMPGE));
			ctx.emit(Opcode.ALOAD);
			ctx.emit(dataSlot);
			ctx.emit(Opcode.ILOAD);
			ctx.emit(idxSlot);
			ctx.emit(Opcode.LALOAD);
			ctx.emit(Opcode.LSTORE);
			ctx.emit(leaf.longSlot);
			// The nil sentinel is not an integer: the fallback reads it back as nil.
			ctx.emit(Opcode.LLOAD);
			ctx.emit(leaf.longSlot);
			JvmEmitHelper.emitRawLong(JvmArrayRuntimeBuilder.NIL_SENTINEL, ctx);
			ctx.emit(Opcode.LCMP);
			bails.add(branch(ctx, Opcode.IFEQ));
		}
		else {
			bails.add(branch(ctx, Opcode.GOTO));
		}
		for (int pos : done) {
			JvmEmitHelper.patchBranch(ctx, pos, ctx.code.size());
		}
	}

	/**
	 * The scratch slots every aref read in one fused method shares: the narrowed index,
	 * the header the ArrayList's slot 0 lands in, and the packed {@code long[]}. Each is
	 * dead the instant the read that filled it is done, and every read stores the same
	 * type into it, so one triple serves the whole method -- keeping a leaf-heavy method
	 * away from the 255-slot ceiling the one-byte load/store operand imposes
	 * ({@code .todo/137}).
	 */
	private record ArefScratch(int idxSlot, int headerSlot, int dataSlot) {
	}

	/** The raw {@code long} slot a resolved aref index reads back from. */
	private static int rawSlotOf(Node index) {
		return switch (index) {
			case ExprLeaf l -> l.longSlot;
			case RawLeaf l -> l.longSlot;
			case RandomLeaf l -> l.longSlot;
			default -> throw new IllegalStateException("not a slot-resolved index: " + index);
		};
	}

	/** The compare methods' tail: 0 or 1 on the operand stack, returned. */
	private static void emitCompareResult(int branchOpcode, JvmLispCompiler.Ctx ctx) {
		int isTrue = branch(ctx, branchOpcode);
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.IRETURN);
		JvmEmitHelper.patchBranch(ctx, isTrue, ctx.code.size());
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.IRETURN);
	}

	private static int branchForMask(int mask) {
		return switch (mask) {
			case 0b010 -> Opcode.IFEQ;
			case 0b001 -> Opcode.IFLT;
			case 0b100 -> Opcode.IFGT;
			case 0b011 -> Opcode.IFLE;
			case 0b110 -> Opcode.IFGE;
			default -> throw new IllegalStateException("unexpected compare mask: " + mask);
		};
	}

	// ----------------------------------------------------------- the double fast path

	/**
	 * Whether this tree admits the IEEE double path. Only {@code + - *} (and the
	 * synthetic compare root) carry over: {@code mod}/{@code rem}/the bitwise operators
	 * are integer-only, and a packed-{@code aref} leaf reads a {@code long[]}. Every
	 * non-constant leaf is guarded as a strict {@code Double} and every integer CONSTANT
	 * widens exactly as {@code _dbl} widens a {@code Long}, so the path computes what the
	 * generic helpers compute for the same operands -- float contagion included, since a
	 * leaf that is not a Double bails.
	 */
	private static boolean doubleEligible(Node root, List<Node> leaves) {
		for (Node leaf : leaves) {
			if (!(leaf instanceof ExprLeaf) && !(leaf instanceof RawLeaf)) {
				return false;
			}
		}
		return doubleOps(root);
	}

	private static boolean doubleOps(Node node) {
		if (!(node instanceof OpNode op)) {
			return true;
		}
		boolean ok = CMP_ROOT.equals(op.op()) || LispNames.ADD.equals(op.op()) || LispNames.SUB.equals(op.op())
				|| LispNames.MUL.equals(op.op());
		if (!ok) {
			return false;
		}
		for (Node arg : op.args()) {
			if (!doubleOps(arg)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Unboxes every leaf into a {@code double} local behind a strict
	 * {@code instanceof Double}; anything else -- a Long, a BigInteger, a ratio, nil, an
	 * unboxed local whose raw slot is authoritative -- branches to the generic fallback.
	 */
	private static void emitDoubleGuards(List<Node> leaves, JvmLispCompiler.Ctx ctx, List<Integer> bails) {
		ClassConstant doubleClass = ctx.cp.addClass(ctx.cp.addUtf8("java/lang/Double"));
		MethodrefConstant doubleValue = ctx.cp.addMethodref(doubleClass,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("doubleValue"), ctx.cp.addUtf8("()D")));
		for (Node leaf : leaves) {
			switch (leaf) {
				case ExprLeaf l -> {
					ctx.emit(Opcode.ALOAD);
					ctx.emit(l.paramSlot);
					ctx.emit(Opcode.INSTANCEOF);
					ctx.emitU2(doubleClass.index());
					bails.add(branch(ctx, Opcode.IFEQ));
					ctx.emit(Opcode.ALOAD);
					ctx.emit(l.paramSlot);
					l.dblSlot = storeDouble(ctx, doubleClass, doubleValue);
				}
				case RawLeaf l -> {
					// The flag set means the raw long slot is authoritative -- an
					// integer, which this path does not mix in.
					ctx.emit(Opcode.ILOAD);
					ctx.emit(l.flagParam);
					bails.add(branch(ctx, Opcode.IFNE));
					ctx.emit(Opcode.ALOAD);
					ctx.emit(l.shadowParam);
					ctx.emit(Opcode.INSTANCEOF);
					ctx.emitU2(doubleClass.index());
					bails.add(branch(ctx, Opcode.IFEQ));
					ctx.emit(Opcode.ALOAD);
					ctx.emit(l.shadowParam);
					l.dblSlot = storeDouble(ctx, doubleClass, doubleValue);
				}
				default -> throw new IllegalStateException("not a double-path leaf: " + leaf);
			}
		}
	}

	/** Unboxes the reference on the stack into a fresh {@code double} local. */
	private static int storeDouble(JvmLispCompiler.Ctx ctx, ClassConstant doubleClass, MethodrefConstant doubleValue) {
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(doubleClass.index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(doubleValue.index());
		int slot = ctx.allocTemp();
		ctx.allocTemp();
		ctx.emit(Opcode.DSTORE);
		ctx.emit(slot);
		return slot;
	}

	/**
	 * The raw {@code double} evaluation over the pre-unboxed leaf locals: the same left
	 * fold the generic helpers perform, with no intermediate box. An integer constant
	 * widens here exactly as {@code _dbl} widens the {@code Long} the generic path would
	 * have seen.
	 */
	private static void emitFastDouble(Node node, JvmLispCompiler.Ctx ctx) {
		switch (node) {
			case ConstLeaf c -> JvmEmitHelper.emitRawDouble(c.value(), ctx);
			case ExprLeaf leaf -> emitDoubleLoad(leaf.dblSlot, ctx);
			case RawLeaf leaf -> emitDoubleLoad(leaf.dblSlot, ctx);
			case ArefLeaf ignored -> throw new IllegalStateException("aref leaf on the double path");
			case RandomLeaf ignored -> throw new IllegalStateException("random leaf on the double path");
			case OpNode op -> {
				emitFastDouble(op.args().get(0), ctx);
				for (int i = 1; i < op.args().size(); i++) {
					emitFastDouble(op.args().get(i), ctx);
					ctx.emit(switch (op.op()) {
						case LispNames.ADD -> Opcode.DADD;
						case LispNames.SUB -> Opcode.DSUB;
						case LispNames.MUL -> Opcode.DMUL;
						default -> throw new IllegalStateException("not a double-path operator: " + op.op());
					});
				}
			}
		}
	}

	private static void emitDoubleLoad(int slot, JvmLispCompiler.Ctx ctx) {
		ctx.emit(Opcode.DLOAD);
		ctx.emit(slot);
	}

	private static void emitBailAndFallback(Pending pending, JvmLispCompiler.Ctx ctx, State state, List<Integer> bails,
			int tryStart, int tryEnd, String className) {
		int handler = ctx.code.size();
		ctx.stack.enterHandler();
		ctx.emit(Opcode.POP);
		int bail = ctx.code.size();
		for (int pos : bails) {
			JvmEmitHelper.patchBranch(ctx, pos, bail);
		}
		if (pending.isCompare()) {
			OpNode root = (OpNode) pending.root();
			emitFallback(root.args().get(0), ctx, className);
			emitFallback(root.args().get(1), ctx, className);
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.numOp(JvmNumericRuntimeBuilder.CMPB).index());
			JvmEmitHelper.emitIntConst(ctx, pending.cmpMask());
			ctx.emit(Opcode.IAND);
			ctx.emit(Opcode.IRETURN);
		}
		else {
			emitFallback(pending.root(), ctx, className);
			ctx.emit(Opcode.ARETURN);
		}
		ClassConstant arithEx = ctx.cp.addClass(ctx.cp.addUtf8("java/lang/ArithmeticException"));
		ctx.exceptionTable.add(new ByteCodeWriter.ExceptionTableEntry(tryStart, tryEnd, handler, arithEx.index()));
	}

	private static MethodrefConstant longIntValue(JvmLispCompiler.Ctx ctx) {
		return ctx.cp.addMethodref(ctx.longClass,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("intValue"), ctx.cp.addUtf8("()I")));
	}

	private static int branch(JvmLispCompiler.Ctx ctx, int opcode) {
		int pos = ctx.code.size();
		ctx.emit(opcode);
		ctx.emitU2(0);
		return pos;
	}

	// ------------------------------------------------------------------ the fast path

	/**
	 * The raw-{@code long} fast path over the pre-unboxed leaf locals: stack-style,
	 * checked through the {@code Math.addExact}/{@code subtractExact}/{@code
	 * multiplyExact} intrinsics and {@code Math.floorMod}/{@code LREM}/{@code _fxAsh},
	 * whose {@code ArithmeticException} the enclosing region routes to the bail.
	 */
	private static void emitFast(Node node, JvmLispCompiler.Ctx ctx, State state) {
		switch (node) {
			case ConstLeaf c -> JvmEmitHelper.emitRawLong(c.value(), ctx);
			case ExprLeaf leaf -> emitLongLoad(leaf.longSlot, ctx);
			case ArefLeaf leaf -> emitLongLoad(leaf.longSlot, ctx);
			case RandomLeaf leaf -> emitLongLoad(leaf.longSlot, ctx);
			case RawLeaf leaf -> emitLongLoad(leaf.longSlot, ctx);
			case OpNode op -> {
				// (mod x 2^k) with a positive power-of-two literal is a plain mask --
				// two's complement makes x & (2^k - 1) the CL (divisor-signed) mod for
				// ANY long x, with no overflow; the masked subtree may compute WRAPPED.
				if (LispNames.MOD.equals(op.op()) && op.args().get(1) instanceof ConstLeaf c && c.value() > 0
						&& Long.bitCount(c.value()) == 1) {
					emitFastWrapped(op.args().get(0), ctx, state);
					JvmEmitHelper.emitRawLong(c.value() - 1, ctx);
					ctx.emit(Opcode.LAND);
					return;
				}
				// (ash x -k) with a literal non-positive count is a plain arithmetic
				// right shift (clamped at 63) -- it cannot overflow.
				if (LispNames.ASH.equals(op.op()) && op.args().get(1) instanceof ConstLeaf c && c.value() <= 0) {
					emitFast(op.args().get(0), ctx, state);
					JvmEmitHelper.emitIntConst(ctx, c.value() <= -63 ? 63 : (int) -c.value());
					ctx.emit(Opcode.LSHR);
					return;
				}
				// (logand X mask) with a non-negative literal: the masked result keeps
				// only low bits, which wrap-around arithmetic computes EXACTLY -- the
				// whole subtree under the mask runs unchecked (mod32+/rol32-shaped code
				// pays no checks at all).
				if (LispNames.LOGAND.equals(op.op()) && op.args().size() == 2) {
					ConstLeaf mask = op.args().get(1) instanceof ConstLeaf m && m.value() >= 0 ? m
							: op.args().get(0) instanceof ConstLeaf m0 && m0.value() >= 0 ? m0 : null;
					if (mask != null) {
						emitFastWrapped(op.args().get(op.args().get(1) == mask ? 0 : 1), ctx, state);
						JvmEmitHelper.emitRawLong(mask.value(), ctx);
						ctx.emit(Opcode.LAND);
						return;
					}
				}
				emitFast(op.args().get(0), ctx, state);
				for (int i = 1; i < op.args().size(); i++) {
					emitFast(op.args().get(i), ctx, state);
					emitFastOp(op.op(), ctx, state);
				}
				if (LispNames.LOGNOT.equals(op.op())) {
					JvmEmitHelper.emitRawLong(-1, ctx);
					ctx.emit(Opcode.LXOR);
				}
			}
		}
	}

	private static void emitLongLoad(int slot, JvmLispCompiler.Ctx ctx) {
		ctx.emit(Opcode.LLOAD);
		ctx.emit(slot);
	}

	/**
	 * Emits a subtree whose consumer only keeps LOW bits (it sits under a literal
	 * {@code logand} mask or a power-of-two {@code mod}): {@code + - *} and
	 * left-{@code ash} by a literal emit as plain wrap-around ops with NO overflow check
	 * -- the low {@code k <= 63} bits of a wrapped result equal the infinite-precision
	 * ones -- and the bitwise ops pass the wrapping license through. Anything whose value
	 * depends on HIGH bits emits through the checked path.
	 */
	private static void emitFastWrapped(Node node, JvmLispCompiler.Ctx ctx, State state) {
		if (!(node instanceof OpNode op)) {
			emitFast(node, ctx, state);
			return;
		}
		switch (op.op()) {
			case LispNames.ADD, LispNames.SUB, LispNames.MUL -> {
				emitFastWrapped(op.args().get(0), ctx, state);
				for (int i = 1; i < op.args().size(); i++) {
					emitFastWrapped(op.args().get(i), ctx, state);
					ctx.emit(switch (op.op()) {
						case LispNames.ADD -> Opcode.LADD;
						case LispNames.SUB -> Opcode.LSUB;
						default -> Opcode.LMUL;
					});
				}
			}
			case LispNames.LOGAND, LispNames.LOGIOR, LispNames.LOGXOR -> {
				emitFastWrapped(op.args().get(0), ctx, state);
				for (int i = 1; i < op.args().size(); i++) {
					emitFastWrapped(op.args().get(i), ctx, state);
					ctx.emit(switch (op.op()) {
						case LispNames.LOGAND -> Opcode.LAND;
						case LispNames.LOGIOR -> Opcode.LOR;
						default -> Opcode.LXOR;
					});
				}
			}
			case LispNames.LOGNOT -> {
				emitFastWrapped(op.args().get(0), ctx, state);
				JvmEmitHelper.emitRawLong(-1, ctx);
				ctx.emit(Opcode.LXOR);
			}
			case LispNames.ASH -> {
				if (op.args().get(1) instanceof ConstLeaf c && c.value() > 0 && c.value() < 64) {
					emitFastWrapped(op.args().get(0), ctx, state);
					JvmEmitHelper.emitIntConst(ctx, (int) c.value());
					ctx.emit(Opcode.LSHL);
				}
				else {
					emitFast(node, ctx, state);
				}
			}
			default -> emitFast(node, ctx, state);
		}
	}

	private static void emitFastOp(String op, JvmLispCompiler.Ctx ctx, State state) {
		switch (op) {
			case LispNames.LOGAND -> ctx.emit(Opcode.LAND);
			case LispNames.LOGIOR -> ctx.emit(Opcode.LOR);
			case LispNames.LOGXOR -> ctx.emit(Opcode.LXOR);
			case LispNames.REM -> ctx.emit(Opcode.LREM);
			case LispNames.ADD -> emitMathCall(ctx, "addExact");
			case LispNames.SUB -> emitMathCall(ctx, "subtractExact");
			case LispNames.MUL -> emitMathCall(ctx, "multiplyExact");
			case LispNames.MOD -> emitMathCall(ctx, "floorMod");
			case LispNames.ASH -> {
				ctx.emit(Opcode.INVOKESTATIC);
				ctx.emitU2(fxAshRef(ctx, state).index());
			}
			default -> throw new IllegalStateException("Not a fusable operator: " + op);
		}
	}

	private static void emitMathCall(JvmLispCompiler.Ctx ctx, String name) {
		MethodrefConstant ref = ctx.cp.addMethodref(ctx.cp.addClass(ctx.cp.addUtf8("java/lang/Math")),
				ctx.cp.addNameAndType(ctx.cp.addUtf8(name), ctx.cp.addUtf8("(JJ)J")));
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ref.index());
	}

	// ------------------------------------------------------------------ the fallback

	/**
	 * The boxed fallback: the same tree, left-folded through the generic runtime helpers
	 * the per-operation compilers call, reading the parameter slots -- so a bailing fast
	 * path costs a recomputation but reproduces the generic result bit for bit (the
	 * operations are pure; the leaves' side effects ran exactly once at the call site).
	 */
	private static void emitFallback(Node node, JvmLispCompiler.Ctx ctx, String className) {
		switch (node) {
			case ConstLeaf c -> JvmEmitHelper.compileLong(c.value(), ctx);
			case ExprLeaf leaf -> {
				ctx.emit(Opcode.ALOAD);
				ctx.emit(leaf.paramSlot);
			}
			// The ordinary rank-1 aref dispatch from the SAME arguments: strings,
			// packed and general arrays all behave exactly as an unfused (aref a i)
			// would, including its error shapes.
			case ArefLeaf leaf -> {
				ctx.emit(Opcode.ALOAD);
				ctx.emit(leaf.arrParam);
				emitFallback(java.util.Objects.requireNonNull(leaf.indexNode), ctx, className);
				ctx.emit(Opcode.INVOKESTATIC);
				ctx.emitU2(aref1Helper(ctx, className).index());
			}
			// The ONE draw the prologue took, re-boxed: raw from the slot, or the
			// boxed value _random answered for a limit the raw path could not take.
			// Never a draw -- this emission repeats for a node used twice.
			case RandomLeaf leaf -> {
				if (leaf.limitExpr == null) {
					ctx.emit(Opcode.LLOAD);
					ctx.emit(leaf.longSlot);
					JvmEmitHelper.boxLong(ctx);
				}
				else {
					ctx.emit(Opcode.ILOAD);
					ctx.emit(leaf.flagSlot);
					int boxed = branch(ctx, Opcode.IFEQ);
					ctx.emit(Opcode.LLOAD);
					ctx.emit(leaf.longSlot);
					JvmEmitHelper.boxLong(ctx);
					int done = branch(ctx, Opcode.GOTO);
					JvmEmitHelper.patchBranch(ctx, boxed, ctx.code.size());
					ctx.emit(Opcode.ALOAD);
					ctx.emit(leaf.boxSlot);
					JvmEmitHelper.patchBranch(ctx, done, ctx.code.size());
				}
			}
			// The snapshot re-boxed: the raw param when the flag is set, else the
			// shadow.
			case RawLeaf leaf -> {
				ctx.emit(Opcode.ILOAD);
				ctx.emit(leaf.flagParam);
				int notRaw = branch(ctx, Opcode.IFEQ);
				ctx.emit(Opcode.LLOAD);
				ctx.emit(leaf.rawParam);
				JvmEmitHelper.boxLong(ctx);
				int done = branch(ctx, Opcode.GOTO);
				JvmEmitHelper.patchBranch(ctx, notRaw, ctx.code.size());
				ctx.emit(Opcode.ALOAD);
				ctx.emit(leaf.shadowParam);
				JvmEmitHelper.patchBranch(ctx, done, ctx.code.size());
			}
			case OpNode op -> {
				emitFallback(op.args().get(0), ctx, className);
				for (int i = 1; i < op.args().size(); i++) {
					emitFallback(op.args().get(i), ctx, className);
					ctx.emit(Opcode.INVOKESTATIC);
					ctx.emitU2(ctx.numOp(fallbackKey(op.op())).index());
				}
				if (LispNames.LOGNOT.equals(op.op())) {
					ctx.emit(Opcode.INVOKESTATIC);
					ctx.emitU2(ctx.numOp(JvmNumericRuntimeBuilder.LOGNOT).index());
				}
			}
		}
	}

	/** The same helper the ordinary rank-1 aref emission calls for this program. */
	private static MethodrefConstant aref1Helper(JvmLispCompiler.Ctx ctx, String className) {
		String name = ctx.usesIntArray ? JvmIntArrayRuntimeBuilder.AREF1
				: ctx.usesFloatArray ? JvmFloatArrayRuntimeBuilder.AREF1 : JvmArrayRuntimeBuilder.AREF1;
		return JvmEmitHelper.selfMethod(ctx, className, name, JvmArrayRuntimeBuilder.AREF1_DESC);
	}

	private static String fallbackKey(String op) {
		return switch (op) {
			case LispNames.ADD -> JvmNumericRuntimeBuilder.ADD;
			case LispNames.SUB -> JvmNumericRuntimeBuilder.SUB;
			case LispNames.MUL -> JvmNumericRuntimeBuilder.MUL;
			case LispNames.MOD -> JvmNumericRuntimeBuilder.MOD;
			case LispNames.REM -> JvmNumericRuntimeBuilder.REM;
			case LispNames.LOGAND -> JvmNumericRuntimeBuilder.LOGAND;
			case LispNames.LOGIOR -> JvmNumericRuntimeBuilder.LOGIOR;
			case LispNames.LOGXOR -> JvmNumericRuntimeBuilder.LOGXOR;
			case LispNames.ASH -> JvmNumericRuntimeBuilder.ASH;
			default -> throw new IllegalStateException("Not a fusable operator: " + op);
		};
	}

	// ------------------------------------------------------------- shared helpers

	/**
	 * {@code _ubRead(Object shadow, long raw, int flag)}: the boxed read of an unboxed
	 * local -- {@code Long.valueOf(raw)} when the flag says the raw slot is
	 * authoritative, else the shadow.
	 */
	static JvmNumericRuntimeBuilder.NumericMethod buildUbRead(ConstantPool cp, MethodrefConstant longValueOf) {
		JvmAsm a = new JvmAsm();
		int useShadow = a.label();
		a.iload(3);
		a.branch(Opcode.IFEQ, useShadow);
		a.lload(1);
		a.invokestatic(longValueOf);
		a.areturn();
		a.bind(useShadow);
		a.aload(0);
		a.areturn();
		return new JvmNumericRuntimeBuilder.NumericMethod(cp.addUtf8("_ubRead"),
				cp.addUtf8("(Ljava/lang/Object;JI)Ljava/lang/Object;"), a.code, 2, 4, List.of());
	}

	/**
	 * {@code _fxAsh(long v, long count)}: the raw checked shift matching {@code _ash}'s
	 * {@code Long} fast path exactly -- the count narrows to an {@code int} first, a
	 * count at or below -64 leaves the sign, a negative count shifts right, and a wide or
	 * overflowing left shift throws {@code ArithmeticException} (the fused region's bail
	 * signal, whose fallback then answers what {@code _ash} answers).
	 */
	static JvmNumericRuntimeBuilder.NumericMethod buildFxAsh(ConstantPool cp) {
		ClassConstant arithEx = cp.addClass(cp.addUtf8("java/lang/ArithmeticException"));
		MethodrefConstant arithExInit = cp.addMethodref(arithEx,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("()V")));
		JvmAsm a = new JvmAsm();
		int rightShift = a.label();
		int leftShift = a.label();
		int overflow = a.label();
		// int c = (int) count;
		a.lload(2);
		a.l2i();
		a.istore(4);
		a.iload(4);
		a.branch(Opcode.IFGT, leftShift);
		// c <= -64: the value shifts down to its sign.
		a.iload(4);
		a.iconst(-64);
		a.branch(Opcode.IF_ICMPGT, rightShift);
		a.lload(0);
		a.iconst(63);
		a.op(Opcode.LSHR);
		a.op(Opcode.LRETURN);
		// -64 < c <= 0: v >> -c.
		a.bind(rightShift);
		a.lload(0);
		a.iconst(0);
		a.iload(4);
		a.op(Opcode.ISUB);
		a.op(Opcode.LSHR);
		a.op(Opcode.LRETURN);
		// c > 0: kept only when the shift round-trips.
		a.bind(leftShift);
		a.iload(4);
		a.iconst(64);
		a.branch(Opcode.IF_ICMPGE, overflow);
		a.lload(0);
		a.iload(4);
		a.op(Opcode.LSHL);
		a.lstore(5);
		a.lload(5);
		a.iload(4);
		a.op(Opcode.LSHR);
		a.lload(0);
		a.op(Opcode.LCMP);
		a.branch(Opcode.IFNE, overflow);
		a.lload(5);
		a.op(Opcode.LRETURN);
		a.bind(overflow);
		a.anew(arithEx);
		a.dup();
		a.invokespecial(arithExInit);
		a.op(Opcode.ATHROW);
		return new JvmNumericRuntimeBuilder.NumericMethod(cp.addUtf8("_fxAsh"), cp.addUtf8("(JJ)J"), a.code, 5, 7,
				List.of());
	}

}

package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.Opcode;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.compiler.ArgumentOrder;
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

	/**
	 * How many leaf checks the boundaries of one site may run between them
	 * ({@link #planOrder}): a tree whose nested operations complete in front of one
	 * observable leaf after another would re-check them at each, so past this the site
	 * declines, and the generic per-operation path keeps the order by construction.
	 */
	private static final int MAX_ORDER_CHECKS = 2 * MAX_EXPR_LEAVES;

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

	/**
	 * An operation over its argument nodes. {@code site} is the source site
	 * ({@link JvmSourceSites}) of the form the operation came from -- 0 without one --
	 * which the fallback marks before the operation's helper call, so a wrong-type
	 * operand reports the operation's own line and function even inside a fused tree or
	 * an inlined defun's body, as the interpreter does.
	 */
	private record OpNode(String op, List<Node> args, int site) implements Node {
	}

	private record ConstLeaf(long value) implements Node {
	}

	/** An opaque expression: evaluated at the call site, guarded in the method. */
	private static final class ExprLeaf implements Node {

		final LispVal expr;

		/** The caller's temporary holding the value, at a site evaluated in order. */
		int callSlot = -1;

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

		/** The caller's temporary holding the array, at a site evaluated in order. */
		int callSlot = -1;

		/**
		 * The source site of the {@code aref} form, 0 without one ({@link OpNode});
		 * cleared when the whole tree reports its caller's site ({@link #methodFor}).
		 */
		int site;

		@Nullable Node indexNode;

		int arrParam = -1;

		int longSlot = -1;

		ArefLeaf(LispVal arrayExpr, int site) {
			this.arrayExpr = arrayExpr;
			this.site = site;
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

		/** The caller's temporary holding the limit, at a site evaluated in order. */
		int callSlot = -1;

		final long limitConst;

		int limitParam = -1;

		int longSlot = -1;

		/** Only for a non-literal limit: 0 means {@link #boxSlot} holds the draw. */
		int flagSlot = -1;

		/** Only for a non-literal limit: {@code _random}'s boxed draw. */
		int boxSlot = -1;

		/**
		 * The source site of the {@code random} form, which a rejected limit's throw
		 * reports ({@link #emitRandomDraw}); 0 without one, and cleared like
		 * {@link ArefLeaf#site}.
		 */
		int site;

		RandomLeaf(@Nullable LispVal limitExpr, long limitConst, int site) {
			this.limitExpr = limitExpr;
			this.limitConst = limitConst;
			this.site = site;
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

		/**
		 * The caller's temporaries holding the snapshot, at a site evaluated in order.
		 */
		int callRaw = -1;

		int callShadow = -1;

		int callFlag = -1;

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
	record RawLocal(int longSlot, int shadowSlot, int flagSlot, @Nullable FieldRefEntry longField,
			@Nullable FieldRefEntry shadowField, @Nullable FieldRefEntry flagField) {

		/** The triple as JVM local slots -- an eligible {@code let} binding. */
		static RawLocal slots(int longSlot, int shadowSlot, int flagSlot) {
			return new RawLocal(longSlot, shadowSlot, flagSlot, null, null, null);
		}

		/**
		 * The triple as static CLASS FIELDS -- an eligible promoted top-level global
		 * ({@code JvmRawGlobals}). The shadow IS the ordinary {@code _g$} field every
		 * other emission already reads, so a store that leaves the raw slot stale is
		 * exactly what the unfused compiler would have written.
		 */
		static RawLocal fields(FieldRefEntry longField, FieldRefEntry shadowField, FieldRefEntry flagField) {
			return new RawLocal(-1, -1, -1, longField, shadowField, flagField);
		}

		/** Whether the triple lives in class fields rather than local slots. */
		boolean isField() {
			return this.longField != null;
		}
	}

	/**
	 * Resolves a bare symbol to the dual representation that reads it HERE, in the same
	 * order {@link JvmExprCompiler#compileSymbolRef} does: an unboxed {@code let} local
	 * first, then -- only when no lexical binding of the name is in scope -- an unboxed
	 * promoted global. Answers null when the name is read some other way.
	 */
	@Nullable static RawLocal resolveRaw(String name, JvmLispCompiler.Ctx ctx) {
		RawLocal local = ctx.rawLocals.get(name);
		if (local != null) {
			return local;
		}
		if (ctx.locals.containsKey(name) || ctx.captures.containsKey(name) || ctx.rawDoubleLocals.containsKey(name)) {
			return null;
		}
		return ctx.rawGlobals.get(name);
	}

	/** Pushes the raw {@code long} half of the triple. */
	private static void emitRawLoad(RawLocal raw, JvmLispCompiler.Ctx ctx) {
		if (raw.isField()) {
			ctx.body.getstatic(java.util.Objects.requireNonNull(raw.longField()));
			return;
		}
		ctx.body.lload(raw.longSlot());
	}

	/** Pushes the boxed shadow half of the triple. */
	private static void emitShadowLoad(RawLocal raw, JvmLispCompiler.Ctx ctx) {
		if (raw.isField()) {
			ctx.body.getstatic(java.util.Objects.requireNonNull(raw.shadowField()));
			return;
		}
		ctx.body.aload(raw.shadowSlot());
	}

	/** Pushes the {@code int} flag half of the triple. */
	private static void emitFlagLoad(RawLocal raw, JvmLispCompiler.Ctx ctx) {
		if (raw.isField()) {
			ctx.body.getstatic(java.util.Objects.requireNonNull(raw.flagField()));
			return;
		}
		ctx.body.iload(raw.flagSlot());
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

		private final Map<String, MethodRefEntry> byKey = new HashMap<>();

		private int nextId;

		private int nextProbeId;

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
	record Pending(MethodRefEntry ref, Utf8Entry nameUtf8, Utf8Entry descUtf8, Node root, List<Node> leaves,
			int cmpMask) {

		boolean isCompare() {
			return this.cmpMask >= 0;
		}

		/** Whether this is a site's probe ({@link #probeFor}), not its fused method. */
		boolean isProbe() {
			return this.cmpMask == PROBE;
		}

	}

	/**
	 * The {@link Pending#cmpMask} of a probe: its root's arguments are what it applies.
	 */
	private static final int PROBE = -2;

	/** The synthetic root a probe's applications hang under. */
	private static final String PROBE_ROOT = "%probe";

	/**
	 * Per-site classification state: the registered leaves, plus what makes raw-local
	 * reads shareable -- a local no leaf of this site can reassign snapshots once and is
	 * read at every occurrence.
	 */
	private static final class Site {

		final List<Node> leaves = new ArrayList<>();

		final Map<String, RawLeaf> sharedRawLeaves = new HashMap<>();

		final Set<String> assignedNames = new HashSet<>();

		/**
		 * What the classified tree does, in the order the interpreter does it: each
		 * leaf's evaluation, and each application -- an operation, an aref read, a
		 * {@code random} draw -- once its operands are evaluated. An inlined body's
		 * applications follow its arguments' evaluation, as a call's do.
		 */
		final List<Event> events = new ArrayList<>();

		/**
		 * Each application's form at the caller's level ({@link #top} when it was built).
		 */
		final Map<Node, LispVal> origins = new java.util.IdentityHashMap<>();

		/** What every classification of this site shares ({@link Ordering}). */
		final Ordering ordering;

		/** The innermost caller-level form being classified. */
		@Nullable LispVal top;

		/**
		 * The owner code ({@link JvmSourceSites#owner}) the forms being classified belong
		 * to: the compiling method's function, and an inlined defun's own while its body
		 * is classified.
		 */
		int owner;

		Site(LispVal expr, JvmLispCompiler.Ctx ctx, Ordering ordering) {
			collectAssignedNames(expr, this.assignedNames);
			this.owner = ctx.siteOwner;
			this.ordering = ordering;
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
		if (JvmLispCompiler.hasComplexOperand(cons.toList())) {
			// A complex literal or complex/conjugate form voids every integer
			// proof: the fused tree would unbox a holder as a long
			// (`.kb/jvm-complex.md`).
			return false;
		}
		Classified classified = classifyOrdered(cons, ctx, site -> classify(cons, ctx, Map.of(), site, 0));
		Node root = classified.root();
		Site site = classified.site();
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
		boolean lineFree = reportsOnlyCallerSite(root, ctx.siteCurrent);
		MethodRefEntry ref = methodFor(root, site.leaves, -1, ctx);
		pushLeaves(classified, lineFree, ctx, className);
		ctx.body.invokestatic(ref);
		return true;
	}

	/**
	 * Compiles a binary numeric comparison ({@code = < > <= >=}) whose operands are
	 * integer expression trees as one outlined raw {@code long} compare, leaving the
	 * BOXED t/nil on the stack (value position). Skips the both-plain-leaves shape to
	 * keep the generic emission unchanged; returns {@code false} (emitting nothing) when
	 * it does not apply.
	 */
	static boolean tryCompileCompareValue(LispCons cons, JvmLispCompiler.Ctx ctx, String className,
			Opcode branchOpcode) {
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
		Opcode branchOpcode = switch (head.name()) {
			case LispNames.EQ -> Opcode.IFEQ;
			case LispNames.LT -> Opcode.IFLT;
			case LispNames.GT -> Opcode.IFGT;
			case LispNames.LE -> Opcode.IFLE;
			case LispNames.GE -> Opcode.IFGE;
			default -> null;
		};
		return branchOpcode != null && emitCompareCall(cons, ctx, className, branchOpcode);
	}

	private static boolean emitCompareCall(LispCons cons, JvmLispCompiler.Ctx ctx, String className,
			Opcode branchOpcode) {
		if (!enabled(ctx)) {
			return false;
		}
		List<LispVal> parts = cons.toList();
		if (JvmLispCompiler.hasComplexOperand(parts)) {
			// Same void integer proof as tryCompile: = compares part-wise and
			// ordering signals, neither of which a raw long compare expresses.
			return false;
		}
		if (JvmLispCompiler.hasDoubleLiteral(parts, ctx)) {
			// The double-literal path owns this shape (IEEE compare over unboxed
			// doubles); fusing it would change nothing for the better.
			return false;
		}
		Classified classified = classifyOrdered(cons, ctx, site -> {
			Node left = classify(parts.get(1), ctx, Map.of(), site, 0);
			Node right = left == null ? null : classify(parts.get(2), ctx, Map.of(), site, 0);
			return right == null ? null : new OpNode(CMP_ROOT, List.of(java.util.Objects.requireNonNull(left), right),
					sourceSite(cons, ctx, site));
		});
		if (!(classified.root() instanceof OpNode root)) {
			return false;
		}
		Site site = classified.site();
		Node left = root.args().get(0);
		Node right = root.args().get(1);
		if (countOps(left) + countOps(right) > MAX_OPS || site.leaves.size() > MAX_EXPR_LEAVES) {
			return false;
		}
		if (left instanceof ExprLeaf && right instanceof ExprLeaf) {
			return false;
		}
		boolean lineFree = reportsOnlyCallerSite(root, ctx.siteCurrent);
		MethodRefEntry ref = methodFor(root, site.leaves, maskFor(branchOpcode), ctx);
		pushLeaves(classified, lineFree, ctx, className);
		ctx.body.invokestatic(ref);
		return true;
	}

	/** The operator a compare method's mask came from (maskFor's inverse). */
	private static String compareOperator(int cmpMask) {
		return switch (cmpMask) {
			case JvmNumericRuntimeBuilder.CMPB_LT -> LispNames.LT;
			case JvmNumericRuntimeBuilder.CMPB_LT | JvmNumericRuntimeBuilder.CMPB_EQ -> LispNames.LE;
			case JvmNumericRuntimeBuilder.CMPB_GT -> LispNames.GT;
			case JvmNumericRuntimeBuilder.CMPB_GT | JvmNumericRuntimeBuilder.CMPB_EQ -> LispNames.GE;
			default -> LispNames.EQ;
		};
	}

	/** The synthetic root op a compare method's two operand trees hang under. */
	private static final String CMP_ROOT = "%cmp";

	private static int maskFor(Opcode branchOpcode) {
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
		if (cons.isProperList() && JvmLispCompiler.hasDoubleLiteral(cons.toList(), ctx)) {
			// Float-contaminated: the value can never land in the raw long slot, so the
			// dual representation would pay its per-site dispatch to always take the
			// shadow store -- and every read of the name would pay _ubRead for a value
			// the raw slot never holds.
			return false;
		}
		return switch (head.name()) {
			case LispNames.ADD, LispNames.SUB, LispNames.MUL, LispNames.MOD, LispNames.REM, LispNames.LOGAND,
					LispNames.LOGIOR, LispNames.LOGXOR, LispNames.LOGNOT, LispNames.ASH, LispNames.ONE_PLUS,
					LispNames.ONE_MINUS, LispNames.LDB, LispNames.AREF, LispNames.MASK_SIGNED_FIELD ->
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
	 * {@code defun} (which lowers to a closure over this binding, and reaches the name
	 * through the global backing store as well). Whether the name is CAPTURED is not
	 * asked here at all: {@link JvmLetCompiler} asks
	 * {@code FreeVarAnalyzer.findCapturedVars} first and never offers a captured name to
	 * the raw path -- one owner, one answer ({@code .kb/core-representation.md}). A false
	 * positive only costs a shadow store; a false negative only costs the fast path.
	 */
	static boolean rawBindingEligible(String name, LispVal init, List<LispVal> bodyForms, JvmLispCompiler.Ctx ctx) {
		if (!enabled(ctx) || ctx.globals.contains(name)) {
			return false;
		}
		if (JvmLispCompiler.containsDouble(init, ctx)) {
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
		if (expr instanceof LispSymbol sym && resolveRaw(sym.name(), ctx) instanceof RawLocal src) {
			// A raw-to-raw copy ((setq a b) with both unboxed) transfers ALL slots:
			// total for every tier, so no guard and no dispatch.
			emitRawLoad(src, ctx);
			emitRawHalfStore(target, ctx);
			emitShadowLoad(src, ctx);
			emitShadowHalfStore(target, ctx);
			emitFlagLoad(src, ctx);
			emitFlagStore(target, ctx);
			return;
		}
		if (enabled(ctx) && !LispMacroExpander.containsComplex(expr)) {
			// A complex literal or complex/conjugate form voids every integer
			// proof (the fused bail would fall back to _add, which signals
			// for a holder instead of answering complex -- `.kb/jvm-complex.md`).
			// The boxed value lands in the shadow instead, which is then
			// authoritative.
			Classified classified = classifyOrdered(expr, ctx, site -> classify(expr, ctx, Map.of(), site, 0));
			Node root = classified.root();
			Site site = classified.site();
			if (root instanceof ConstLeaf c) {
				// A tree folded to a literal: the raw value directly.
				JvmEmitHelper.emitRawLong(c.value(), ctx);
				emitRawSlotStore(target, ctx);
				return;
			}
			if ((root instanceof OpNode || root instanceof ArefLeaf) && countOps(root) <= MAX_OPS
					&& site.leaves.size() <= MAX_EXPR_LEAVES) {
				MethodCode.Label @Nullable [] step = emitRawStepFastPath(root, ctx, target);
				boolean lineFree = reportsOnlyCallerSite(root, ctx.siteCurrent);
				MethodRefEntry ref = methodFor(root, site.leaves, -1, ctx);
				if (step != null) {
					ctx.body.labelBinding(step[0]);
				}
				pushLeaves(classified, lineFree, ctx, className);
				ctx.body.invokestatic(ref);
				// Dispatch on the VALUE's type, not on which path computed it: a Long
				// is the raw representation whichever path answered it.
				int tmp = ctx.allocTemp();
				ctx.body.astore(tmp).aload(tmp).instanceOf(ctx.longClass);
				MethodCode.Label notLong = ctx.body.newLabel();
				ctx.body.ifeq(notLong);
				ctx.body.aload(tmp);
				JvmEmitHelper.unboxLong(ctx);
				emitRawSlotStore(target, ctx);
				MethodCode.Label done = ctx.body.newLabel();
				ctx.body.goto_(done);
				ctx.body.labelBinding(notLong);
				ctx.body.aload(tmp);
				emitShadowSlotStore(target, ctx);
				ctx.body.labelBinding(done);
				if (step != null) {
					ctx.body.labelBinding(step[1]);
				}
				return;
			}
		}
		// Not an integer tree at all: the boxed value lands in the shadow, which is
		// then authoritative -- lists, floats, nil, anything.
		JvmExprCompiler.compileExpr(expr, ctx, className);
		emitShadowSlotStore(target, ctx);
	}

	/**
	 * The counted-loop STEP -- {@code (+ i c)} / {@code (- i c)} / {@code (1+ i)} over a
	 * dual-representation local or promoted global, assigned straight back into one --
	 * emitted inline as raw {@code long} arithmetic ahead of the outlined method, which
	 * stays as the fallback for the only two cases the inline form cannot answer: a raw
	 * slot the flag says is not authoritative, and an addition that would overflow into a
	 * bignum.
	 *
	 * <p>
	 * What this saves is not the call. The outlined method boxes at its root and this
	 * site unboxes it back one instruction later, so every step of every {@code dotimes}
	 * / {@code do} / {@code loop for} allocated a {@code Long} that died immediately. C2
	 * scalar-replaces that box once it compiles the loop -- but a loop that BUILDS a data
	 * structure typically runs far too few iterations to be compiled at all, and then the
	 * dead counter boxes are real objects INTERLEAVED with the cells the loop allocates:
	 * {@code (loop for i from 1 to 1000 collect i)} laid its list out over 64 bytes per
	 * element instead of 48, a third more cache footprint for every walk of it
	 * afterwards. Measured on {@code .todo/517}'s {@code nth} row (10^9 {@code cdr} steps
	 * over that list), the interleave -- not the allocation -- is what the walk pays for.
	 *
	 * <p>
	 * The overflow guard is {@code Math.addExact}'s condition spelled out for a constant
	 * addend, so the fallback sees exactly the cases the outlined method's
	 * {@code addExact} would have thrown on.
	 * @return the label to bind at the fallback's first instruction, followed by the one
	 * to bind past it, or {@code null} when the shape does not apply
	 */
	private static MethodCode.Label @Nullable [] emitRawStepFastPath(Node root, JvmLispCompiler.Ctx ctx,
			RawLocal target) {
		if (!(root instanceof OpNode op) || op.args().size() != 2) {
			return null;
		}
		boolean sub = LispNames.SUB.equals(op.op());
		if (!sub && !LispNames.ADD.equals(op.op())) {
			return null;
		}
		RawLeaf leaf;
		long addend;
		if (op.args().get(0) instanceof RawLeaf r && op.args().get(1) instanceof ConstLeaf c) {
			leaf = r;
			addend = sub ? -c.value() : c.value();
			// (- i most-negative-fixnum) has no negation; leave it to the fallback.
			if (sub && c.value() == Long.MIN_VALUE) {
				return null;
			}
		}
		else if (!sub && op.args().get(0) instanceof ConstLeaf c && op.args().get(1) instanceof RawLeaf r) {
			leaf = r;
			addend = c.value();
		}
		else {
			return null;
		}
		if (addend == 0) {
			return null;
		}
		MethodCode.Label fallback = ctx.body.newLabel();
		MethodCode.Label joins = ctx.body.newLabel();
		emitFlagLoad(leaf.src, ctx);
		ctx.body.ifeq(fallback);
		emitRawLoad(leaf.src, ctx);
		JvmEmitHelper.emitRawLong(addend > 0 ? Long.MAX_VALUE - addend : Long.MIN_VALUE - addend, ctx);
		ctx.body.lcmp();
		if (addend > 0) {
			ctx.body.ifgt(fallback);
		}
		else {
			ctx.body.iflt(fallback);
		}
		emitRawLoad(leaf.src, ctx);
		JvmEmitHelper.emitRawLong(addend, ctx);
		ctx.body.ladd();
		emitRawSlotStore(target, ctx);
		ctx.body.goto_(joins);
		return new MethodCode.Label[] { fallback, joins };
	}

	/** Raw {@code long} on the stack -> the raw slot; the flag marks it authoritative. */
	private static void emitRawSlotStore(RawLocal target, JvmLispCompiler.Ctx ctx) {
		emitRawHalfStore(target, ctx);
		ctx.body.iconst_1();
		emitFlagStore(target, ctx);
	}

	/** Boxed value on the stack -> the shadow slot; the flag marks the raw slot stale. */
	private static void emitShadowSlotStore(RawLocal target, JvmLispCompiler.Ctx ctx) {
		emitShadowHalfStore(target, ctx);
		ctx.body.iconst_0();
		emitFlagStore(target, ctx);
	}

	/** Raw {@code long} on the stack -> the raw half; the flag is NOT touched. */
	private static void emitRawHalfStore(RawLocal target, JvmLispCompiler.Ctx ctx) {
		if (target.isField()) {
			ctx.body.putstatic(java.util.Objects.requireNonNull(target.longField()));
			return;
		}
		ctx.body.lstore(target.longSlot());
	}

	/** Boxed value on the stack -> the shadow half; the flag is NOT touched. */
	private static void emitShadowHalfStore(RawLocal target, JvmLispCompiler.Ctx ctx) {
		if (target.isField()) {
			ctx.body.putstatic(java.util.Objects.requireNonNull(target.shadowField()));
			return;
		}
		ctx.body.astore(target.shadowSlot());
	}

	/** {@code int} on the stack -> the flag half. */
	private static void emitFlagStore(RawLocal target, JvmLispCompiler.Ctx ctx) {
		if (target.isField()) {
			ctx.body.putstatic(java.util.Objects.requireNonNull(target.flagField()));
			return;
		}
		ctx.body.istore(target.flagSlot());
	}

	/**
	 * Reads an unboxed local as an ordinary boxed value, through the shared
	 * {@code _ubRead} helper: the shadow unless the flag says the raw slot is
	 * authoritative, else the raw {@code long} boxed.
	 */
	static void emitRawLocalBoxedRead(RawLocal raw, JvmLispCompiler.Ctx ctx) {
		State state = java.util.Objects.requireNonNull(ctx.fusedState);
		emitShadowLoad(raw, ctx);
		emitRawLoad(raw, ctx);
		emitFlagLoad(raw, ctx);
		ctx.body.invokestatic(ubReadRef(ctx, state));
	}

	private static MethodRefEntry ubReadRef(JvmLispCompiler.Ctx ctx, State state) {
		state.usesUbRead = true;
		return JvmEmitHelper.selfMethod(ctx, state.className, "_ubRead", "(Ljava/lang/Object;JI)Ljava/lang/Object;");
	}

	private static MethodRefEntry fxAshRef(JvmLispCompiler.Ctx ctx, State state) {
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
		if (!env.isEmpty() || !(expr instanceof LispCons)) {
			return classifyHere(expr, ctx, env, site, depth);
		}
		if (site.ordering.opaque.contains(expr)) {
			// A form whose application must happen where it stands (OrderPlan): the
			// caller evaluates it, in order, like any other leaf.
			return registerLeaf(new ExprLeaf(expr), site);
		}
		@Nullable LispVal outerTop = site.top;
		site.top = expr;
		try {
			return classifyHere(expr, ctx, env, site, depth);
		}
		finally {
			site.top = outerTop;
		}
	}

	@Nullable private static Node classifyHere(LispVal expr, JvmLispCompiler.Ctx ctx, Map<String, Node> env, Site site,
			int depth) {
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
			RawLocal raw = resolveRaw(sym.name(), ctx);
			if (raw != null) {
				if (!site.assignedNames.contains(sym.name())) {
					// No leaf in this site can reassign the local, so every occurrence
					// shares ONE snapshot instead of paying its own.
					RawLeaf shared = site.sharedRawLeaves.get(sym.name());
					if (shared == null) {
						shared = new RawLeaf(raw);
						site.sharedRawLeaves.put(sym.name(), shared);
						registerLeaf(shared, site);
					}
					return shared;
				}
				return registerLeaf(new RawLeaf(raw), site);
			}
		}
		if (!(expr instanceof LispCons cons) || !cons.isProperList() || !(cons.car() instanceof LispSymbol sym)) {
			return registerLeaf(new ExprLeaf(expr), site);
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
			return arefLeaf(parts.get(1), parts.get(2), sourceSite(cons, ctx, site), ctx, site, depth);
		}
		if (LispNames.RANDOM.equals(op) && arity == 1 && env.isEmpty()) {
			RandomLeaf leaf = randomLeaf(parts, ctx, sourceSite(cons, ctx, site));
			if (leaf != null) {
				return applied(registerLeaf(leaf, site), site);
			}
		}
		if (LispNames.LDB.equals(op) && arity == 2) {
			// (ldb (byte s p) x) with a literal byte spec lowers to its pure
			// logand/ash expansion, which classifies as an ordinary subtree.
			if (parts.get(1) instanceof LispCons spec && spec.car() instanceof LispSymbol specHead
					&& LispNames.BYTE.equals(specHead.name()) && spec.cdr() instanceof LispCons sCell
					&& sCell.car() instanceof LispInteger && sCell.cdr() instanceof LispCons pCell
					&& pCell.car() instanceof LispInteger && pCell.cdr() instanceof am.ik.rontolisp.LispNil) {
				// One expansion per form, so a classification repeated with a form made
				// opaque (OrderPlan) meets the same conses again.
				LispVal expanded = site.ordering.expansions.computeIfAbsent(cons, form -> SourceProvenance
					.inherit((LispCons) form, LispMacroExpander.expandLdb((LispCons) form)));
				return classify(expanded, ctx, env, site, depth);
			}
			return env.isEmpty() ? registerLeaf(new ExprLeaf(expr), site) : null;
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
						env, site, depth, site.owner);
				if (substituted != null) {
					return substituted;
				}
			}
		}
		JvmLispCompiler.DefunDecl inlinable = ctx.inlinableDefuns.get(op);
		if (inlinable != null && arity == inlinable.paramNames().size() && depth < MAX_INLINE_DEPTH) {
			LispVal body = java.util.Objects.requireNonNull(singleBodyExpr(inlinable.bodyExprs()));
			Node substituted = substituteCall(inlinable.paramNames(), body, parts.subList(1, parts.size()), ctx, env,
					site, depth, inlinedOwner(inlinable, ctx));
			if (substituted != null) {
				return substituted;
			}
			// The body did not classify under this env (a parameter-shaped aref, say):
			// fall through to the ordinary leaf treatment of the call itself.
		}
		if (LispNames.MASK_SIGNED_FIELD.equals(op)) {
			return classifyMaskSignedField(cons, ctx, env, site, depth);
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
			return env.isEmpty() ? registerLeaf(new ExprLeaf(expr), site) : null;
		}
		if (JvmLispCompiler.hasDoubleLiteral(parts, ctx)) {
			// The double-literal routing predicate the per-op compilers read: such a
			// node takes the unboxed-double path today and keeps it (as a leaf here).
			return env.isEmpty() ? registerLeaf(new ExprLeaf(expr), site) : null;
		}
		for (int i = 1; i < parts.size(); i++) {
			// An immediate big-integer or ratio literal makes the fast path pointless
			// (the guard would fail every time); the generic compiler owns those.
			if (parts.get(i) instanceof am.ik.rontolisp.LispBigInteger
					|| parts.get(i) instanceof am.ik.rontolisp.LispRatio) {
				return env.isEmpty() ? registerLeaf(new ExprLeaf(expr), site) : null;
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
		int sourceSite = sourceSite(cons, ctx, site);
		return applied(switch (op) {
			case LispNames.ONE_PLUS -> makeOp(LispNames.ADD, List.of(args.get(0), new ConstLeaf(1)), sourceSite);
			case LispNames.ONE_MINUS -> makeOp(LispNames.SUB, List.of(args.get(0), new ConstLeaf(1)), sourceSite);
			default -> makeOp(op, args, sourceSite);
		}, site);
	}

	/**
	 * {@code (%mask-signed-field size x)} with a literal size from 1 to 64 is an
	 * operation node over {@code (size, x)}: the low {@code size} bits of the result
	 * depend only on the low bits of {@code x}, so the fast path computes {@code x}
	 * WRAPPED and sign-extends ({@link #emitFast}). Any other size is a leaf, which the
	 * lowering owns.
	 */
	@Nullable private static Node classifyMaskSignedField(LispCons cons, JvmLispCompiler.Ctx ctx, Map<String, Node> env,
			Site site, int depth) {
		int size = LispMacroExpander.maskSignedFieldSize(cons);
		if (size < 1 || size > 64) {
			return env.isEmpty() ? registerLeaf(new ExprLeaf(cons), site) : null;
		}
		Node arg = classify(cons.toList().get(2), ctx, env, site, depth);
		if (arg == null) {
			return null;
		}
		return applied(
				makeOp(LispNames.MASK_SIGNED_FIELD, List.of(new ConstLeaf(size), arg), sourceSite(cons, ctx, site)),
				site);
	}

	/**
	 * The signed value of the low {@code size} bits of {@code value},
	 * {@code 1 <= size <= 64}.
	 */
	private static long signExtend(long value, int size) {
		int shift = 64 - size;
		return (value << shift) >> shift;
	}

	/**
	 * The source site of a form classified into the tree, owned by the function the form
	 * belongs to ({@link Site#owner}); 0 when the compile records no positions or the
	 * form has none.
	 */
	private static int sourceSite(LispCons form, JvmLispCompiler.Ctx ctx, Site site) {
		JvmSourceSites table = ctx.sites;
		return table == null ? 0 : table.site(form, site.owner);
	}

	/**
	 * The owner an inlined defun's body is classified under: the defun itself when it is
	 * the program's own code -- the interpreter runs its body as its own function -- and
	 * 0 for a library defun, which a report never names.
	 */
	private static int inlinedOwner(JvmLispCompiler.DefunDecl inlinable, JvmLispCompiler.Ctx ctx) {
		JvmSourceSites table = ctx.sites;
		return table == null || !JvmSourceSites.sourced(inlinable.bodyExprs()) ? 0
				: table.owner(JvmSourceSites.reportedName(inlinable.name()));
	}

	/**
	 * Builds an operation node, folding it to a constant when every argument is a literal
	 * and the exact result fits a {@code long}. Folding never changes a result: it
	 * computes exactly what the fast path would (and bails to the ordinary node on
	 * overflow or a zero divisor, so promotion/error behavior is preserved).
	 */
	private static Node makeOp(String op, List<Node> args, int sourceSite) {
		for (Node arg : args) {
			if (!(arg instanceof ConstLeaf)) {
				return new OpNode(op, args, sourceSite);
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
					case LispNames.MASK_SIGNED_FIELD -> signExtend(v, (int) acc);
					default -> throw new ArithmeticException("not foldable");
				};
			}
			return new ConstLeaf(acc);
		}
		catch (ArithmeticException overflowOrZeroDivide) {
			return new OpNode(op, args, sourceSite);
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
			Map<String, Node> env, Site site, int depth, int bodyOwner) {
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
					int callerOwner = site.owner;
					site.owner = bodyOwner;
					int arefSite = sourceSite(bodyCons, ctx, site);
					site.owner = callerOwner;
					return arefLeaf(arr, idx, arefSite, ctx, site, depth);
				}
			}
		}
		int mark = leaves.size();
		int eventMark = site.events.size();
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
			// The arguments were the caller's forms; the body is the callee's.
			int callerOwner = site.owner;
			site.owner = bodyOwner;
			try {
				substituted = classify(body, ctx, callEnv, site, depth + 1);
			}
			finally {
				site.owner = callerOwner;
			}
		}
		if (substituted == null) {
			leaves.subList(mark, leaves.size()).clear();
			site.events.subList(eventMark, site.events.size()).clear();
		}
		return substituted;
	}

	/**
	 * Builds and registers a rank-1 aref leaf. The leaf registers BEFORE its index, so
	 * the call site pushes the array first and the index's own leaves after it -- the
	 * generic {@code (aref a i)} argument order.
	 */
	private static Node arefLeaf(LispVal arrayExpr, LispVal indexExpr, int sourceSite, JvmLispCompiler.Ctx ctx,
			Site site, int depth) {
		ArefLeaf leaf = new ArefLeaf(arrayExpr, sourceSite);
		registerLeaf(leaf, site);
		leaf.indexNode = arefIndexNode(indexExpr, ctx, site, depth);
		// The read applies once the array and the index are evaluated.
		return applied(leaf, site);
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
			return registerLeaf(new ExprLeaf(indexExpr), site);
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
	@Nullable private static RandomLeaf randomLeaf(List<LispVal> parts, JvmLispCompiler.Ctx ctx, int sourceSite) {
		if (!enabled(ctx) || JvmLispCompiler.hasDoubleLiteral(parts, ctx)) {
			return null;
		}
		LispVal limit = parts.get(1);
		if (limit instanceof LispInteger lit) {
			if (lit.value() <= 0) {
				// _random's domain violation (.todo/981): bail out of fusion so the
				// unfused path's call reaches the checked, throwing helper instead of
				// baking a bad constant into the draw.
				return null;
			}
			return new RandomLeaf(null, lit.value(), sourceSite);
		}
		if (limit instanceof am.ik.rontolisp.LispBigInteger || limit instanceof am.ik.rontolisp.LispRatio) {
			return null;
		}
		return new RandomLeaf(limit, 0, sourceSite);
	}

	private static Node registerLeaf(Node leaf, Site site) {
		site.leaves.add(leaf);
		site.events.add(new Event(leaf, false));
		return leaf;
	}

	/**
	 * Records an application -- an operation, an aref read, a draw -- at this point of
	 * the interpreter's order, with the caller-level form it can be made opaque as. A
	 * folded constant applies nothing.
	 */
	private static Node applied(Node node, Site site) {
		if (node instanceof OpNode || node instanceof ArefLeaf || node instanceof RandomLeaf) {
			site.events.add(new Event(node, true));
			site.origins.put(node, java.util.Objects.requireNonNull(site.top));
		}
		return node;
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
		// A masked signed field's size is a parameter of the operation, not an operand:
		// over a lone leaf the fused site would only re-box what the lowering answers.
		return root instanceof OpNode op && !LispNames.MASK_SIGNED_FIELD.equals(op.op())
				&& op.args().stream().anyMatch(arg -> arg instanceof ConstLeaf);
	}

	// -------------------------------------------------------- the outlined method

	/**
	 * The method reference for this tree shape, minting (and queueing for the fused pass)
	 * a new {@code _fx$N} only when no structurally identical site exists yet.
	 */
	private static MethodRefEntry methodFor(Node root, List<Node> leaves, int cmpMask, JvmLispCompiler.Ctx ctx) {
		if (reportsOnlyCallerSite(root, ctx.siteCurrent)) {
			// Every form in the tree reports the site the call itself is at -- the
			// common one-line tree -- so the method needs no line numbers of its own: it
			// stays transparent to the uncaught report and shared by every structurally
			// identical site, as it was before sites existed. Only a tree that spans
			// lines, or an inlined defun's body, pays for a method of its own.
			root = withoutSites(root);
		}
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
		MethodRefEntry existing = state.byKey.get(key);
		if (existing != null) {
			return existing;
		}
		String name = "_fx$" + state.nextId++;
		Utf8Entry nameUtf8 = ctx.cp.utf8Entry(name);
		Utf8Entry descUtf8 = ctx.cp.utf8Entry(desc.toString());
		MethodRefEntry ref = ctx.cp.methodRef(ctx.cp.classEntry(state.className), nameUtf8, descUtf8);
		state.byKey.put(key, ref);
		state.pending.add(new Pending(ref, nameUtf8, descUtf8, root, leaves, cmpMask));
		return ref;
	}

	/**
	 * Whether every operation of the tree reports {@code callerSite} (or no site at all):
	 * then a failure inside the fused method is reported exactly by the call it is made
	 * from.
	 */
	private static boolean reportsOnlyCallerSite(Node node, int callerSite) {
		return switch (node) {
			case OpNode op -> (op.site() == 0 || op.site() == callerSite)
					&& op.args().stream().allMatch(arg -> reportsOnlyCallerSite(arg, callerSite));
			case ArefLeaf leaf -> (leaf.site == 0 || leaf.site == callerSite)
					&& reportsOnlyCallerSite(java.util.Objects.requireNonNull(leaf.indexNode), callerSite);
			case RandomLeaf leaf -> leaf.site == 0 || leaf.site == callerSite;
			default -> true;
		};
	}

	/**
	 * The tree with its sites cleared: an ordinary, shareable, line-free fused method.
	 */
	private static Node withoutSites(Node node) {
		return switch (node) {
			case OpNode op -> op.site() == 0 && op.args().stream().allMatch(arg -> withoutSites(arg) == arg) ? op
					: new OpNode(op.op(), op.args().stream().map(JvmIntFusionCompiler::withoutSites).toList(), 0);
			case ArefLeaf leaf -> {
				leaf.site = 0;
				leaf.indexNode = withoutSites(java.util.Objects.requireNonNull(leaf.indexNode));
				yield leaf;
			}
			case RandomLeaf leaf -> {
				leaf.site = 0;
				yield leaf;
			}
			default -> node;
		};
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
				if (op.site() != 0) {
					// Sites differ, methods differ: a fused method's line numbers are its
					// forms' (JvmSourceSites), so it is shared only by sites of one form.
					sb.append('@').append(op.site());
				}
				for (Node arg : op.args()) {
					sb.append(' ');
					appendKey(arg, leaves, sb);
				}
				sb.append(')');
			}
			case ExprLeaf leaf -> sb.append('e').append(leafIndex(leaf, leaves));
			case ArefLeaf leaf -> {
				sb.append('a').append(leafIndex(leaf, leaves));
				if (leaf.site != 0) {
					sb.append('@').append(leaf.site);
				}
				sb.append('[');
				appendKey(java.util.Objects.requireNonNull(leaf.indexNode), leaves, sb);
				sb.append(']');
			}
			case RandomLeaf leaf -> sb.append('n')
				.append(leafIndex(leaf, leaves))
				.append(leaf.limitExpr == null ? "#" + leaf.limitConst : "")
				.append(leaf.site == 0 ? "" : "@" + leaf.site);
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
					emitRawLoad(leaf.src, ctx);
					emitShadowLoad(leaf.src, ctx);
					emitFlagLoad(leaf.src, ctx);
				}
				default -> throw new IllegalStateException("not a registered leaf: " + node);
			}
		}
	}

	// ------------------------------------------------------------ evaluation order

	/**
	 * What every classification of one site shares while {@link #classifyOrdered} repeats
	 * it: the caller-level forms kept as ordinary leaves, and each expansion made, so a
	 * repeat meets the same conses.
	 */
	private static final class Ordering {

		final Set<LispVal> opaque = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

		final Map<LispVal, LispVal> expansions = new java.util.IdentityHashMap<>();

	}

	/** One step of the interpreter's order: a leaf's evaluation, or an application. */
	private record Event(Node node, boolean applies) {
	}

	/** A site classified so that its call keeps the interpreter's order. */
	private record Classified(Site site, @Nullable Node root, OrderPlan plan) {
	}

	/**
	 * The checks a site's call runs between its leaves (`.kb/jvm-int-fusion.md`, "The
	 * interpreter's order"), and the caller-level forms the next classification must keep
	 * opaque.
	 */
	private record OrderPlan(Map<Node, Boundary> boundaries, Set<LispVal> demote, int checks) {
	}

	/**
	 * The check before a leaf whose evaluation can be observed, while applications are
	 * pending in front of it. Passing it proves that the applications may wait for the
	 * fused method; failing it calls the probe, which applies them through the generic
	 * helpers -- signalling where the interpreter signals -- before the leaf runs.
	 */
	private record Boundary(List<Node> probeRoots, Guard guard) {
	}

	private sealed interface Guard permits PendingGuard, LeafGuard {

	}

	/**
	 * The pending applications cannot signal: every leaf they read is a {@code Long},
	 * every divisor among them non-zero and every shift count no larger than an
	 * {@code int}; {@code never} when a literal operand makes one always signal.
	 */
	private record PendingGuard(List<Node> integers, List<Node> nonZero, List<Node> counts,
			boolean never) implements Guard {
	}

	/**
	 * The leaf cannot signal and changes nothing: it is integer arithmetic over these
	 * quiet variables ({@link ArgumentOrder#integerArithmeticVariables}), each of which
	 * holds a {@code Long}. The pending applications then stay pending.
	 */
	private record LeafGuard(List<String> variables) implements Guard {
	}

	/**
	 * Classifies a site until its plan asks for nothing more to be kept opaque: an
	 * application that must happen where it stands and that no check can stand in for
	 * becomes an ordinary leaf of the next classification.
	 */
	private static Classified classifyOrdered(LispVal form, JvmLispCompiler.Ctx ctx,
			java.util.function.Function<Site, @Nullable Node> classifier) {
		Ordering ordering = new Ordering();
		while (true) {
			Site site = new Site(form, ctx, ordering);
			Node root = classifier.apply(site);
			OrderPlan plan = root == null ? new OrderPlan(Map.of(), Set.of(), 0) : planOrder(site, ctx);
			if (plan.demote().isEmpty()) {
				return plan.checks() > MAX_ORDER_CHECKS ? new Classified(site, null, plan)
						: new Classified(site, root, plan);
			}
			ordering.opaque.addAll(plan.demote());
		}
	}

	/**
	 * Walks the site's events in the interpreter's order and places a {@link Boundary}
	 * before every leaf whose evaluation can be observed while applications are pending:
	 * the fused call evaluates every leaf before it applies anything, which is the
	 * interpreter's order only where nothing in between can tell.
	 */
	private static OrderPlan planOrder(Site site, JvmLispCompiler.Ctx ctx) {
		Map<Node, Boundary> boundaries = new java.util.IdentityHashMap<>();
		Set<LispVal> demote = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		List<Node> pending = new ArrayList<>();
		int checks = 0;
		for (Event event : site.events) {
			if (event.applies()) {
				pending.add(event.node());
				continue;
			}
			LispVal evaluated = evaluatedForm(event.node());
			if (evaluated == null || pending.isEmpty()
					|| ArgumentOrder.isQuiet(evaluated, name -> JvmArithCompiler.isQuietVariable(name, ctx))) {
				continue;
			}
			List<Node> roots = maximal(pending);
			PendingGuard guard = pendingGuard(roots);
			if (guard != null) {
				boundaries.put(event.node(), new Boundary(roots, guard));
				checks += guard.integers().size() + guard.nonZero().size() + guard.counts().size();
				pending.clear();
				continue;
			}
			List<String> variables = ArgumentOrder.integerArithmeticVariables(evaluated,
					name -> JvmArithCompiler.isQuietVariable(name, ctx));
			if (variables != null && pending.stream().noneMatch(RandomLeaf.class::isInstance)) {
				boundaries.put(event.node(), new Boundary(roots, new LeafGuard(variables)));
				checks += variables.size();
				continue;
			}
			// No check stands in for these: an aref read sees what the leaf may store, a
			// draw is never repeated, and a computed divisor or shift count is not a
			// leaf to test. Each is applied where it stands, by the ordinary emission.
			for (Node item : pending) {
				if (item instanceof RandomLeaf || variables == null && !guardable(item)) {
					demote.add(java.util.Objects.requireNonNull(site.origins.get(item)));
				}
			}
		}
		return new OrderPlan(boundaries, demote, checks);
	}

	/** What evaluating the leaf runs at the call site, or null when nothing does. */
	private static @Nullable LispVal evaluatedForm(Node leaf) {
		return switch (leaf) {
			case ExprLeaf l -> l.expr;
			case ArefLeaf l -> l.arrayExpr;
			case RandomLeaf l -> l.limitExpr;
			default -> null;
		};
	}

	/** The pending applications no other pending one contains. */
	private static List<Node> maximal(List<Node> pending) {
		Set<Node> inside = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		for (Node item : pending) {
			for (Node child : children(item)) {
				collectSubtree(child, inside);
			}
		}
		List<Node> roots = new ArrayList<>();
		for (Node item : pending) {
			if (!inside.contains(item)) {
				roots.add(item);
			}
		}
		return roots;
	}

	private static List<Node> children(Node node) {
		return switch (node) {
			case OpNode op -> op.args();
			case ArefLeaf leaf -> List.of(java.util.Objects.requireNonNull(leaf.indexNode));
			default -> List.of();
		};
	}

	private static void collectSubtree(Node node, Set<Node> out) {
		if (out.add(node)) {
			for (Node child : children(node)) {
				collectSubtree(child, out);
			}
		}
	}

	/**
	 * Every node under {@code roots}, each once, in the order a walk of the trees meets
	 * it: what the emitted checks follow, so the same program always compiles to the same
	 * bytes (`.kb/emitted-output-determinism.md`).
	 */
	private static List<Node> inTreeOrder(List<Node> roots) {
		Set<Node> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		List<Node> nodes = new ArrayList<>();
		for (Node root : roots) {
			walkInTreeOrder(root, seen, nodes);
		}
		return nodes;
	}

	private static void walkInTreeOrder(Node node, Set<Node> seen, List<Node> out) {
		if (seen.add(node)) {
			out.add(node);
			for (Node child : children(node)) {
				walkInTreeOrder(child, seen, out);
			}
		}
	}

	/**
	 * Whether an application's signals are decided by the leaves it reads: an operation
	 * whose divisor or shift count, if it has one, is a literal or a leaf. An aref read
	 * and a draw are not.
	 */
	private static boolean guardable(Node item) {
		if (!(item instanceof OpNode op)) {
			return false;
		}
		if (LispNames.MOD.equals(op.op()) || LispNames.REM.equals(op.op()) || LispNames.ASH.equals(op.op())) {
			Node operand = op.args().get(1);
			return operand instanceof ConstLeaf || operand instanceof ExprLeaf || operand instanceof RawLeaf;
		}
		return true;
	}

	/**
	 * The check that the applications under {@code roots} cannot signal, or null when one
	 * of them is not {@link #guardable}.
	 */
	private static @Nullable PendingGuard pendingGuard(List<Node> roots) {
		List<Node> nodes = inTreeOrder(roots);
		List<Node> integers = new ArrayList<>();
		List<Node> nonZero = new ArrayList<>();
		List<Node> counts = new ArrayList<>();
		boolean never = false;
		for (Node node : nodes) {
			switch (node) {
				case ExprLeaf ignored -> integers.add(node);
				case RawLeaf ignored -> integers.add(node);
				case ArefLeaf ignored -> {
					return null;
				}
				case RandomLeaf ignored -> {
					return null;
				}
				case OpNode op -> {
					if (!guardable(op)) {
						return null;
					}
					boolean divides = LispNames.MOD.equals(op.op()) || LispNames.REM.equals(op.op());
					if (divides || LispNames.ASH.equals(op.op())) {
						Node operand = op.args().get(1);
						if (operand instanceof ConstLeaf c) {
							never |= divides ? c.value() == 0 : c.value() > Integer.MAX_VALUE;
						}
						else {
							(divides ? nonZero : counts).add(operand);
						}
					}
				}
				default -> {
				}
			}
		}
		return new PendingGuard(integers, nonZero, counts, never);
	}

	/**
	 * Evaluates the site's leaves for its call: pushed one after the other as today when
	 * the plan has no boundary -- the emission is then what it always was -- and through
	 * temporaries otherwise, each boundary's check run before its leaf.
	 */
	private static void pushLeaves(Classified classified, boolean lineFree, JvmLispCompiler.Ctx ctx, String className) {
		List<Node> leaves = classified.site().leaves;
		Map<Node, Boundary> boundaries = classified.plan().boundaries();
		if (boundaries.isEmpty()) {
			pushLeaves(leaves, ctx, className);
			return;
		}
		for (Node leaf : leaves) {
			Boundary boundary = boundaries.get(leaf);
			if (boundary != null) {
				emitBoundary(boundary, leaves, lineFree, ctx, className);
			}
			evaluateLeaf(leaf, ctx, className);
		}
		for (Node leaf : leaves) {
			pushLeafTemps(leaf, ctx);
		}
	}

	/** One leaf evaluated into the caller's temporaries, in its place in the order. */
	private static void evaluateLeaf(Node node, JvmLispCompiler.Ctx ctx, String className) {
		switch (node) {
			case ExprLeaf leaf -> {
				JvmExprCompiler.compileExpr(leaf.expr, ctx, className);
				leaf.callSlot = ctx.allocTemp();
				ctx.body.astore(leaf.callSlot);
			}
			case ArefLeaf leaf -> {
				JvmExprCompiler.compileExpr(leaf.arrayExpr, ctx, className);
				leaf.callSlot = ctx.allocTemp();
				ctx.body.astore(leaf.callSlot);
			}
			case RandomLeaf leaf -> {
				if (leaf.limitExpr != null) {
					JvmExprCompiler.compileExpr(leaf.limitExpr, ctx, className);
					leaf.callSlot = ctx.allocTemp();
					ctx.body.astore(leaf.callSlot);
				}
			}
			case RawLeaf leaf -> {
				leaf.callRaw = ctx.allocTemp();
				ctx.allocTemp();
				emitRawLoad(leaf.src, ctx);
				ctx.body.lstore(leaf.callRaw);
				leaf.callShadow = ctx.allocTemp();
				emitShadowLoad(leaf.src, ctx);
				ctx.body.astore(leaf.callShadow);
				leaf.callFlag = ctx.allocTemp();
				emitFlagLoad(leaf.src, ctx);
				ctx.body.istore(leaf.callFlag);
			}
			default -> throw new IllegalStateException("not a registered leaf: " + node);
		}
	}

	/** A leaf's temporaries as the fused method's (or a probe's) arguments. */
	private static void pushLeafTemps(Node node, JvmLispCompiler.Ctx ctx) {
		switch (node) {
			case ExprLeaf leaf -> ctx.body.aload(leaf.callSlot);
			case ArefLeaf leaf -> ctx.body.aload(leaf.callSlot);
			case RandomLeaf leaf -> {
				if (leaf.limitExpr != null) {
					ctx.body.aload(leaf.callSlot);
				}
			}
			case RawLeaf leaf -> ctx.body.lload(leaf.callRaw).aload(leaf.callShadow).iload(leaf.callFlag);
			default -> throw new IllegalStateException("not a registered leaf: " + node);
		}
	}

	/** The boundary's check, and the probe call it falls into when the check fails. */
	private static void emitBoundary(Boundary boundary, List<Node> siteLeaves, boolean lineFree,
			JvmLispCompiler.Ctx ctx, String className) {
		MethodCode.Label checked = ctx.body.newLabel();
		if (!(boundary.guard() instanceof PendingGuard pending && pending.never())) {
			MethodCode.Label fails = ctx.body.newLabel();
			emitGuard(boundary.guard(), fails, ctx, className);
			ctx.body.goto_(checked);
			ctx.body.labelBinding(fails);
		}
		Probe probe = probeFor(boundary.probeRoots(), siteLeaves, lineFree, ctx);
		for (Node leaf : probe.leaves()) {
			pushLeafTemps(leaf, ctx);
		}
		ctx.body.invokestatic(probe.ref());
		ctx.body.labelBinding(checked);
	}

	private static void emitGuard(Guard guard, MethodCode.Label fails, JvmLispCompiler.Ctx ctx, String className) {
		switch (guard) {
			case PendingGuard pending -> {
				for (Node leaf : pending.integers()) {
					emitIsLong(leaf, fails, ctx);
				}
				for (Node leaf : pending.nonZero()) {
					emitLongValue(leaf, ctx);
					ctx.body.lconst_0().lcmp().ifeq(fails);
				}
				for (Node leaf : pending.counts()) {
					emitLongValue(leaf, ctx);
					JvmEmitHelper.emitRawLong(Integer.MAX_VALUE, ctx);
					ctx.body.lcmp().ifgt(fails);
				}
			}
			case LeafGuard leafGuard -> {
				for (String name : leafGuard.variables()) {
					RawLocal raw = resolveRaw(name, ctx);
					if (raw != null) {
						MethodCode.Label isLong = ctx.body.newLabel();
						emitFlagLoad(raw, ctx);
						ctx.body.ifne(isLong);
						emitShadowLoad(raw, ctx);
						ctx.body.instanceOf(ctx.longClass).ifeq(fails);
						ctx.body.labelBinding(isLong);
					}
					else {
						JvmExprCompiler.compileSymbolRef(new LispSymbol(name), ctx);
						ctx.body.instanceOf(ctx.longClass).ifeq(fails);
					}
				}
			}
		}
	}

	/**
	 * Branches to {@code fails} unless the leaf's value, as evaluated, is a {@code Long}.
	 */
	private static void emitIsLong(Node node, MethodCode.Label fails, JvmLispCompiler.Ctx ctx) {
		if (node instanceof RawLeaf leaf) {
			MethodCode.Label isLong = ctx.body.newLabel();
			ctx.body.iload(leaf.callFlag).ifne(isLong);
			ctx.body.aload(leaf.callShadow).instanceOf(ctx.longClass).ifeq(fails);
			ctx.body.labelBinding(isLong);
			return;
		}
		ctx.body.aload(((ExprLeaf) node).callSlot).instanceOf(ctx.longClass).ifeq(fails);
	}

	/** The raw {@code long} of a leaf {@link #emitIsLong} has passed. */
	private static void emitLongValue(Node node, JvmLispCompiler.Ctx ctx) {
		if (node instanceof RawLeaf leaf) {
			MethodCode.Label boxed = ctx.body.newLabel();
			MethodCode.Label done = ctx.body.newLabel();
			ctx.body.iload(leaf.callFlag).ifeq(boxed);
			ctx.body.lload(leaf.callRaw).goto_(done);
			ctx.body.labelBinding(boxed);
			ctx.body.aload(leaf.callShadow);
			JvmEmitHelper.unboxLong(ctx);
			ctx.body.labelBinding(done);
			return;
		}
		ctx.body.aload(((ExprLeaf) node).callSlot);
		JvmEmitHelper.unboxLong(ctx);
	}

	/** A probe's method, and the site's leaves it takes, in its parameter order. */
	private record Probe(MethodRefEntry ref, List<Node> leaves) {
	}

	/**
	 * The probe applying {@code roots} through the generic helpers, minting it once per
	 * structure as {@link #methodFor} mints a fused method: {@code _fxp$N}, void, over
	 * the leaves the roots read, in the site's order.
	 */
	private static Probe probeFor(List<Node> roots, List<Node> siteLeaves, boolean lineFree, JvmLispCompiler.Ctx ctx) {
		List<Node> applied = lineFree ? roots.stream().map(JvmIntFusionCompiler::withoutSites).toList() : roots;
		Set<Node> reads = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		for (Node root : applied) {
			collectSubtree(root, reads);
		}
		List<Node> leaves = new ArrayList<>();
		for (Node leaf : siteLeaves) {
			if (reads.contains(leaf)) {
				leaves.add(leaf);
			}
		}
		State state = java.util.Objects.requireNonNull(ctx.fusedState);
		StringBuilder desc = new StringBuilder("(");
		for (Node leaf : leaves) {
			desc.append(leaf instanceof RawLeaf ? "JLjava/lang/Object;I" : "Ljava/lang/Object;");
		}
		desc.append(")V");
		StringBuilder key = new StringBuilder("probe|").append(desc);
		for (Node root : applied) {
			key.append('|');
			appendKey(root, leaves, key);
		}
		MethodRefEntry existing = state.byKey.get(key.toString());
		if (existing != null) {
			return new Probe(existing, leaves);
		}
		Utf8Entry nameUtf8 = ctx.cp.utf8Entry("_fxp$" + state.nextProbeId++);
		Utf8Entry descUtf8 = ctx.cp.utf8Entry(desc.toString());
		MethodRefEntry ref = ctx.cp.methodRef(ctx.cp.classEntry(state.className), nameUtf8, descUtf8);
		state.byKey.put(key.toString(), ref);
		state.pending.add(new Pending(ref, nameUtf8, descUtf8, new OpNode(PROBE_ROOT, applied, 0), leaves, PROBE));
		return new Probe(ref, leaves);
	}

	/**
	 * A probe's body: each of its applications through the generic helpers, under its own
	 * operator and site, its value dropped. One that signals signals here, before the
	 * caller evaluates the leaf the probe stands in front of.
	 */
	private static void emitProbeBody(Pending pending, JvmLispCompiler.Ctx ctx, String className) {
		int slot = assignParameters(pending.leaves());
		ctx.nextLocal = slot;
		ctx.maxLocals = Math.max(ctx.maxLocals, slot);
		for (Node root : ((OpNode) pending.root()).args()) {
			emitFallback(root, ctx, className);
			ctx.body.pop();
		}
		ctx.body.return_();
	}

	// ------------------------------------------------------------- method body pass

	/**
	 * Emits one pending fused method's body into a fresh context (the compiler's fused
	 * pass, after every program body is compiled): guards + unboxes each leaf once, the
	 * raw fast path under an {@code ArithmeticException} region whose handler is the
	 * bail, and the generic-helper fallback.
	 */
	static void emitMethodBody(Pending pending, JvmLispCompiler.Ctx ctx, String className) {
		if (pending.isProbe()) {
			emitProbeBody(pending, ctx, className);
			return;
		}
		State state = java.util.Objects.requireNonNull(ctx.fusedState);
		int slot = assignParameters(pending.leaves());
		ctx.nextLocal = slot;
		ctx.maxLocals = Math.max(ctx.maxLocals, slot);
		MethodCode.Label bails = ctx.body.newLabel();
		ClassEntry longArrayClass = ctx.cp.classEntry("[J");
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
				ctx.body.iconst_0().istore(bailFlag);
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
				// ctx.operator names the wrapper this numOp call resolves to
				// (.todo/981): the fusion planner reaches this leaf structurally, never
				// through JvmExprCompiler.compileCons's own (random ...) dispatch, so
				// nothing else sets it to RANDOM here.
				String outerOperator = ctx.operator;
				ctx.operator = LispNames.RANDOM;
				try {
					emitRandomDraw(l, ctx, ctx.numOp(JvmNumericRuntimeBuilder.RANDOM), limitScratch, bailFlag);
				}
				finally {
					ctx.operator = outerOperator;
				}
			}
		}
		if (bailFlag >= 0) {
			ctx.body.iload(bailFlag).ifne(bails);
		}
		for (Node leaf : pending.leaves()) {
			switch (leaf) {
				case ExprLeaf l -> {
					ctx.body.aload(l.paramSlot).instanceOf(ctx.longClass).ifeq(bails);
					ctx.body.aload(l.paramSlot);
					JvmEmitHelper.unboxLong(ctx);
					l.longSlot = ctx.allocTemp();
					ctx.allocTemp();
					ctx.body.lstore(l.longSlot);
				}
				case RawLeaf l -> {
					// Flag set: the raw param already holds the value. A Long shadow:
					// unbox into the raw param's slot (same numeric). Anything else:
					// bail.
					ctx.body.iload(l.flagParam);
					MethodCode.Label isRaw = ctx.body.newLabel();
					ctx.body.ifne(isRaw);
					ctx.body.aload(l.shadowParam).instanceOf(ctx.longClass).ifeq(bails);
					ctx.body.aload(l.shadowParam);
					JvmEmitHelper.unboxLong(ctx);
					ctx.body.lstore(l.rawParam);
					ctx.body.labelBinding(isRaw);
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
		MethodCode.Label tryStart = ctx.body.newBoundLabel();
		if (pending.isCompare()) {
			OpNode root = (OpNode) pending.root();
			emitFast(root.args().get(0), ctx, state);
			emitFast(root.args().get(1), ctx, state);
			ctx.body.lcmp();
			emitCompareResult(branchForMask(pending.cmpMask()), ctx);
		}
		else {
			emitFast(pending.root(), ctx, state);
			JvmEmitHelper.boxLong(ctx);
			ctx.body.areturn();
		}
		MethodCode.Label tryEnd = ctx.body.newBoundLabel();
		// The IEEE double fast path: the same tree over leaves that are all Doubles,
		// tried when the Long guards fail. It sits OUTSIDE the checked region -- an
		// overflow means the exact integer result did not fit, which the fallback owns,
		// not the doubles.
		MethodCode.Label fallbackBails = bails;
		if (doubleEligible(pending.root(), pending.leaves()) && ctx.nextLocal + 2 * pending.leaves().size() <= 250) {
			ctx.body.labelBinding(bails);
			MethodCode.Label doubleBails = ctx.body.newLabel();
			emitDoubleGuards(pending.leaves(), ctx, doubleBails);
			if (pending.isCompare()) {
				OpNode root = (OpNode) pending.root();
				emitFastDouble(root.args().get(0), ctx);
				emitFastDouble(root.args().get(1), ctx);
				Opcode branchOpcode = branchForMask(pending.cmpMask());
				// javac's NaN rule, which is exactly the bitmask _cmpb answers: DCMPG
				// for < and <= (unordered falls out as +1, failing IFLT/IFLE), DCMPL
				// for the rest (unordered falls out as -1, failing IFEQ/IFGT/IFGE).
				if (branchOpcode == Opcode.IFLT || branchOpcode == Opcode.IFLE) {
					ctx.body.dcmpg();
				}
				else {
					ctx.body.dcmpl();
				}
				emitCompareResult(branchOpcode, ctx);
			}
			else {
				emitFastDouble(pending.root(), ctx);
				JvmEmitHelper.boxDouble(ctx);
				ctx.body.areturn();
			}
			fallbackBails = doubleBails;
		}
		emitBailAndFallback(pending, ctx, state, fallbackBails, tryStart, tryEnd, className);
	}

	/** Parameter slots in leaf order; answers the first free slot. */
	private static int assignParameters(List<Node> leaves) {
		int slot = 0;
		for (Node leaf : leaves) {
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
		return slot;
	}

	/**
	 * The prologue's {@code random} draw -- exactly one per leaf, on every path. A
	 * {@code Long} limit (and a literal one, which needs no argument at all) computes the
	 * same expression {@code _random} evaluates for it,
	 * {@code (long) (ThreadLocalRandom.current().nextDouble() * limit)}, straight into a
	 * raw slot, with the box on both ends gone -- unless it is non-positive, which
	 * {@code _random} rejects too (.todo/981): that joins the not-a-Long case below
	 * instead of drawing. Any other limit (a float reaching {@code random} through a
	 * variable) takes its ONE draw from {@code _random} into the boxed slot and raises
	 * the bail flag, so the tree falls back with the value already drawn -- or, for a
	 * rejected limit, with {@code _random}'s throw instead.
	 */
	private static void emitRandomDraw(RandomLeaf leaf, JvmLispCompiler.Ctx ctx, MethodRefEntry randomHelper,
			int limitScratch, int bailFlag) {
		if (leaf.limitExpr == null) {
			emitDrawTimesDouble(ctx, -1, leaf.limitConst);
			ctx.body.lstore(leaf.longSlot);
			return;
		}
		ctx.body.aload(leaf.limitParam).instanceOf(ctx.longClass);
		MethodCode.Label notLong = ctx.body.newLabel();
		ctx.body.ifeq(notLong);
		ctx.body.aload(leaf.limitParam);
		JvmEmitHelper.unboxLong(ctx);
		ctx.body.lstore(limitScratch);
		// A non-positive Long limit is _random's domain violation too (.todo/981): join
		// the not-a-Long trampoline below instead of drawing, so the boxed helper call
		// throws (its own check runs before any draw, so this never draws twice).
		ctx.body.lload(limitScratch).lconst_0().lcmp();
		MethodCode.Label notPositive = ctx.body.newLabel();
		ctx.body.ifle(notPositive);
		emitDrawTimesDouble(ctx, limitScratch, 0);
		ctx.body.lstore(leaf.longSlot).iconst_1().istore(leaf.flagSlot).aconst_null();
		ctx.body.astore(leaf.boxSlot);
		MethodCode.Label drawn = ctx.body.newLabel();
		ctx.body.goto_(drawn);
		ctx.body.labelBinding(notLong);
		ctx.body.labelBinding(notPositive);
		// _random may reject the limit: its throw reports the random form, whatever line
		// the tree around it started on.
		int outerSite = ctx.siteCurrent;
		ctx.restoreSite(leaf.site);
		ctx.body.aload(leaf.limitParam).invokestatic(randomHelper);
		ctx.restoreSite(outerSite);
		ctx.body.astore(leaf.boxSlot);
		JvmEmitHelper.emitRawLong(0, ctx);
		ctx.body.lstore(leaf.longSlot).iconst_0().istore(leaf.flagSlot).iconst_1().istore(bailFlag);
		ctx.body.labelBinding(drawn);
	}

	/**
	 * {@code (long) (ThreadLocalRandom.current().nextDouble() * limit)} -- {@code
	 * _random}'s own Long-limit expression, over a raw slot or a constant.
	 */
	private static void emitDrawTimesDouble(JvmLispCompiler.Ctx ctx, int limitSlot, long limitConst) {
		ctx.body.invokestatic(ctx.mathOp(JvmMathFnCompiler.TLR_CURRENT));
		ctx.body.invokevirtual(ctx.mathOp(JvmMathFnCompiler.TLR_NEXT_DOUBLE));
		if (limitSlot >= 0) {
			ctx.body.lload(limitSlot).l2d();
		}
		else {
			JvmEmitHelper.emitRawDouble(limitConst, ctx);
		}
		ctx.body.dmul().d2l();
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
	private static void emitArefRead(ArefLeaf leaf, JvmLispCompiler.Ctx ctx, MethodCode.Label bails,
			ClassEntry longArrayClass, ArefScratch scratch) {
		// idx = (int) <index>, an index past the int range bailing: _aref1 checks the
		// whole value against the bound, so no truncation may read an element.
		int idxSlot = scratch.idxSlot();
		Node index = java.util.Objects.requireNonNull(leaf.indexNode);
		if (index instanceof ConstLeaf c) {
			if (c.value() != (int) c.value()) {
				ctx.body.goto_(bails);
			}
			JvmEmitHelper.emitIntConst(ctx, (int) c.value());
			ctx.body.istore(idxSlot);
		}
		else {
			emitLongLoad(rawSlotOf(index), ctx);
			ctx.body.l2i().istore(idxSlot);
			emitLongLoad(rawSlotOf(index), ctx);
			ctx.body.iload(idxSlot).i2l().lcmp().ifne(bails);
		}
		leaf.longSlot = ctx.allocTemp();
		ctx.allocTemp();
		MethodCode.Label done = ctx.body.newLabel();
		if (ctx.usesIntArray) {
			// An (unsigned-byte 8) vector, byte[]{8, e0, ...}: element e & 0xFF. Where a
			// quantized matrix (also a byte[]) can exist, the tag in slot 0 tells them
			// apart, and the matrix bails to _aref1 like any other shape.
			ClassEntry byteArrayClass = ctx.cp.classEntry("[B");
			MethodCode.Label notOctets = ctx.body.newLabel();
			ctx.body.aload(leaf.arrParam).instanceOf(byteArrayClass).ifeq(notOctets);
			if (ctx.usesQuantized) {
				ctx.body.aload(leaf.arrParam).checkcast(byteArrayClass).iconst_0().baload();
				ctx.body.loadConstant(JvmIntArrayRuntimeBuilder.OCTET_TAG).if_icmpne(notOctets);
			}
			ctx.body.iload(idxSlot).iflt(bails);
			ctx.body.iload(idxSlot).aload(leaf.arrParam).checkcast(byteArrayClass);
			ctx.body.arraylength().iconst_1().isub().if_icmpge(bails);
			ctx.body.aload(leaf.arrParam).checkcast(byteArrayClass).iconst_1();
			ctx.body.iload(idxSlot).iadd().baload().loadConstant(0xFF).iand().i2l();
			ctx.body.lstore(leaf.longSlot).goto_(done);
			ctx.body.labelBinding(notOctets);
			ctx.body.aload(leaf.arrParam).instanceOf(longArrayClass);
			MethodCode.Label notPackedVector = ctx.body.newLabel();
			ctx.body.ifeq(notPackedVector);
			ctx.body.iload(idxSlot).iflt(bails);
			ctx.body.iload(idxSlot).aload(leaf.arrParam).checkcast(longArrayClass);
			ctx.body.arraylength().iconst_1().isub().if_icmpge(bails);
			ctx.body.aload(leaf.arrParam).checkcast(longArrayClass).iconst_1();
			ctx.body.iload(idxSlot).iadd().laload().lstore(leaf.longSlot).goto_(done);
			ctx.body.labelBinding(notPackedVector);
		}
		if (ctx.usesArrays) {
			ClassEntry arrayListClass = ctx.cp.classEntry("java/util/ArrayList");
			ClassEntry objectArrayClass = ctx.cp.classEntry("[Ljava/lang/Object;");
			MethodRefEntry alSize = ctx.cp.methodRef(arrayListClass, "size", "()I");
			MethodRefEntry alGet = ctx.cp.methodRef(arrayListClass, "get", "(I)Ljava/lang/Object;");
			int headerSlot = scratch.headerSlot();
			int dataSlot = scratch.dataSlot();
			ctx.body.aload(leaf.arrParam).instanceOf(arrayListClass).ifeq(bails);
			// The same "is this an array?" shape test _arrayp makes, so get(0) on an
			// ArrayList that is not one cannot throw past the bail.
			ctx.body.aload(leaf.arrParam).checkcast(arrayListClass);
			ctx.body.invokevirtual(alSize).ifeq(bails);
			ctx.body.aload(leaf.arrParam).checkcast(arrayListClass).iconst_0();
			ctx.body.invokevirtual(alGet).astore(headerSlot).aload(headerSlot);
			ctx.body.instanceOf(objectArrayClass).ifeq(bails);
			// Header length 6 IS the packed shape: 4 is a character vector, 5 a
			// displaced array, 3 the boxed general array -- all of them _aref1's.
			ctx.body.aload(headerSlot).checkcast(objectArrayClass).arraylength();
			JvmEmitHelper.emitIntConst(ctx, 6);
			ctx.body.if_icmpne(bails);
			ctx.body.aload(headerSlot).checkcast(objectArrayClass).iconst_5().aaload();
			ctx.body.checkcast(longArrayClass).astore(dataSlot).iload(idxSlot).iflt(bails);
			ctx.body.iload(idxSlot).aload(dataSlot).arraylength().if_icmpge(bails);
			ctx.body.aload(dataSlot).iload(idxSlot).laload().lstore(leaf.longSlot);
			// The nil sentinel is not an integer: the fallback reads it back as nil.
			ctx.body.lload(leaf.longSlot);
			JvmEmitHelper.emitRawLong(JvmArrayRuntimeBuilder.NIL_SENTINEL, ctx);
			ctx.body.lcmp().ifeq(bails);
		}
		else {
			ctx.body.goto_(bails);
		}
		ctx.body.labelBinding(done);
	}

	/**
	 * The scratch slots every aref read in one fused method shares: the narrowed index,
	 * the header the ArrayList's slot 0 lands in, and the packed {@code long[]}. Each is
	 * dead the instant the read that filled it is done, and every read stores the same
	 * type into it, so one triple serves the whole method -- keeping a leaf-heavy method
	 * away from the slot number past which every load and store costs a {@code wide}
	 * prefix ({@code .todo/137}).
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
	private static void emitCompareResult(Opcode branchOpcode, JvmLispCompiler.Ctx ctx) {
		MethodCode.Label isTrue = ctx.body.newLabel();
		ctx.body.branch(branchOpcode, isTrue);
		ctx.body.iconst_0().ireturn();
		ctx.body.labelBinding(isTrue);
		ctx.body.iconst_1().ireturn();
	}

	private static Opcode branchForMask(int mask) {
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
	private static void emitDoubleGuards(List<Node> leaves, JvmLispCompiler.Ctx ctx, MethodCode.Label bails) {
		ClassEntry doubleClass = ctx.cp.classEntry("java/lang/Double");
		MethodRefEntry doubleValue = ctx.cp.methodRef(doubleClass, "doubleValue", "()D");
		for (Node leaf : leaves) {
			switch (leaf) {
				case ExprLeaf l -> {
					ctx.body.aload(l.paramSlot).instanceOf(doubleClass).ifeq(bails);
					ctx.body.aload(l.paramSlot);
					l.dblSlot = storeDouble(ctx, doubleClass, doubleValue);
				}
				case RawLeaf l -> {
					// The flag set means the raw long slot is authoritative -- an
					// integer, which this path does not mix in.
					ctx.body.iload(l.flagParam).ifne(bails);
					ctx.body.aload(l.shadowParam).instanceOf(doubleClass).ifeq(bails);
					ctx.body.aload(l.shadowParam);
					l.dblSlot = storeDouble(ctx, doubleClass, doubleValue);
				}
				default -> throw new IllegalStateException("not a double-path leaf: " + leaf);
			}
		}
	}

	/** Unboxes the reference on the stack into a fresh {@code double} local. */
	private static int storeDouble(JvmLispCompiler.Ctx ctx, ClassEntry doubleClass, MethodRefEntry doubleValue) {
		ctx.body.checkcast(doubleClass).invokevirtual(doubleValue);
		int slot = ctx.allocTemp();
		ctx.allocTemp();
		ctx.body.dstore(slot);
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
				// The integer constants a node starts with fold exactly, as the generic
				// helpers fold them before they meet the first Double, and only their
				// value widens (`.kb/jvm-double-arithmetic.md`, "The exact prefix").
				int from = 0;
				while (from < op.args().size() && exactConstant(op.args().get(from)) != null) {
					from++;
				}
				if (from >= 2) {
					JvmEmitHelper.emitRawDouble(java.util.Objects
						.requireNonNull(exactConstant(new OpNode(op.op(), op.args().subList(0, from), op.site())))
						.doubleValue(), ctx);
				}
				else {
					from = 1;
					emitFastDouble(op.args().get(0), ctx);
				}
				for (int i = from; i < op.args().size(); i++) {
					emitFastDouble(op.args().get(i), ctx);
					switch (op.op()) {
						case LispNames.ADD -> ctx.body.dadd();
						case LispNames.SUB -> ctx.body.dsub();
						case LispNames.MUL -> ctx.body.dmul();
						default -> throw new IllegalStateException("not a double-path operator: " + op.op());
					}
				}
			}
		}
	}

	/**
	 * The exact value of a double-path node built from integer constants alone -- a
	 * constant, or a {@code + - *} node over such nodes that did not fold to a
	 * {@code long} -- or null for a node that reads a leaf.
	 */
	private static @Nullable BigInteger exactConstant(Node node) {
		if (node instanceof ConstLeaf c) {
			return BigInteger.valueOf(c.value());
		}
		if (!(node instanceof OpNode op) || op.args().size() < 2) {
			return null;
		}
		BigInteger acc = exactConstant(op.args().get(0));
		for (int i = 1; acc != null && i < op.args().size(); i++) {
			BigInteger next = exactConstant(op.args().get(i));
			acc = next == null ? null : switch (op.op()) {
				case LispNames.ADD -> acc.add(next);
				case LispNames.SUB -> acc.subtract(next);
				case LispNames.MUL -> acc.multiply(next);
				default -> null;
			};
		}
		return acc;
	}

	private static void emitDoubleLoad(int slot, JvmLispCompiler.Ctx ctx) {
		ctx.body.dload(slot);
	}

	private static void emitBailAndFallback(Pending pending, JvmLispCompiler.Ctx ctx, State state,
			MethodCode.Label bails, MethodCode.Label tryStart, MethodCode.Label tryEnd, String className) {
		MethodCode.Label handler = ctx.body.newBoundLabel();
		ctx.stack.enterHandler();
		ctx.body.pop();
		ctx.body.labelBinding(bails);
		if (pending.isCompare()) {
			OpNode root = (OpNode) pending.root();
			emitFallback(root.args().get(0), ctx, className);
			emitFallback(root.args().get(1), ctx, className);
			ctx.restoreSite(root.site());
			// An ordering of a program that may observe a complex signals for one
			// (JvmComparisonCompiler.orderingOrEquality), so its bail must too.
			String operator = compareOperator(pending.cmpMask());
			if (ctx.usesComplex && !LispNames.EQ.equals(operator)) {
				@Nullable String outer = ctx.operator;
				ctx.operator = operator;
				try {
					ctx.body.invokestatic(JvmComplexCompiler.complexOp(ctx, className, JvmComplexRuntimeBuilder.CCPMB));
				}
				finally {
					ctx.operator = outer;
				}
			}
			else {
				ctx.body.invokestatic(numOpFor(operator, JvmNumericRuntimeBuilder.CMPB, ctx));
			}
			JvmEmitHelper.emitIntConst(ctx, pending.cmpMask());
			ctx.body.iand().ireturn();
		}
		else {
			emitFallback(pending.root(), ctx, className);
			ctx.body.areturn();
		}
		ClassEntry arithEx = ctx.cp.classEntry("java/lang/ArithmeticException");
		ctx.body.exceptionCatch(tryStart, tryEnd, handler, arithEx);
	}

	private static MethodRefEntry longIntValue(JvmLispCompiler.Ctx ctx) {
		return ctx.cp.methodRef(ctx.longClass, "intValue", "()I");
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
				// (%mask-signed-field k x) keeps the low k <= 64 bits, which the WRAPPED
				// subtree computes exactly; a narrower field sign-extends from bit k-1.
				if (LispNames.MASK_SIGNED_FIELD.equals(op.op())) {
					int size = (int) ((ConstLeaf) op.args().get(0)).value();
					emitFastWrapped(op.args().get(1), ctx, state);
					if (size < 64) {
						JvmEmitHelper.emitIntConst(ctx, 64 - size);
						ctx.body.lshl();
						JvmEmitHelper.emitIntConst(ctx, 64 - size);
						ctx.body.lshr();
					}
					return;
				}
				// (mod x 2^k) with a positive power-of-two literal is a plain mask --
				// two's complement makes x & (2^k - 1) the CL (divisor-signed) mod for
				// ANY long x, with no overflow; the masked subtree may compute WRAPPED.
				if (LispNames.MOD.equals(op.op()) && op.args().get(1) instanceof ConstLeaf c && c.value() > 0
						&& Long.bitCount(c.value()) == 1) {
					emitFastWrapped(op.args().get(0), ctx, state);
					JvmEmitHelper.emitRawLong(c.value() - 1, ctx);
					ctx.body.land();
					return;
				}
				// (ash x -k) with a literal non-positive count is a plain arithmetic
				// right shift (clamped at 63) -- it cannot overflow.
				if (LispNames.ASH.equals(op.op()) && op.args().get(1) instanceof ConstLeaf c && c.value() <= 0) {
					emitFast(op.args().get(0), ctx, state);
					JvmEmitHelper.emitIntConst(ctx, c.value() <= -63 ? 63 : (int) -c.value());
					ctx.body.lshr();
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
						ctx.body.land();
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
					ctx.body.lxor();
				}
			}
		}
	}

	private static void emitLongLoad(int slot, JvmLispCompiler.Ctx ctx) {
		ctx.body.lload(slot);
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
					switch (op.op()) {
						case LispNames.ADD -> ctx.body.ladd();
						case LispNames.SUB -> ctx.body.lsub();
						default -> ctx.body.lmul();
					}
				}
			}
			case LispNames.LOGAND, LispNames.LOGIOR, LispNames.LOGXOR -> {
				emitFastWrapped(op.args().get(0), ctx, state);
				for (int i = 1; i < op.args().size(); i++) {
					emitFastWrapped(op.args().get(i), ctx, state);
					switch (op.op()) {
						case LispNames.LOGAND -> ctx.body.land();
						case LispNames.LOGIOR -> ctx.body.lor();
						default -> ctx.body.lxor();
					}
				}
			}
			case LispNames.LOGNOT -> {
				emitFastWrapped(op.args().get(0), ctx, state);
				JvmEmitHelper.emitRawLong(-1, ctx);
				ctx.body.lxor();
			}
			case LispNames.ASH -> {
				if (op.args().get(1) instanceof ConstLeaf c && c.value() > 0 && c.value() < 64) {
					emitFastWrapped(op.args().get(0), ctx, state);
					JvmEmitHelper.emitIntConst(ctx, (int) c.value());
					ctx.body.lshl();
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
			case LispNames.LOGAND -> ctx.body.land();
			case LispNames.LOGIOR -> ctx.body.lor();
			case LispNames.LOGXOR -> ctx.body.lxor();
			case LispNames.REM -> ctx.body.lrem();
			case LispNames.ADD -> emitMathCall(ctx, "addExact");
			case LispNames.SUB -> emitMathCall(ctx, "subtractExact");
			case LispNames.MUL -> emitMathCall(ctx, "multiplyExact");
			case LispNames.MOD -> emitMathCall(ctx, "floorMod");
			case LispNames.ASH -> {
				ctx.body.invokestatic(fxAshRef(ctx, state));
			}
			default -> throw new IllegalStateException("Not a fusable operator: " + op);
		}
	}

	private static void emitMathCall(JvmLispCompiler.Ctx ctx, String name) {
		MethodRefEntry ref = ctx.cp.methodRef(ctx.cp.classEntry("java/lang/Math"), name, "(JJ)J");
		ctx.body.invokestatic(ref);
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
				ctx.body.aload(leaf.paramSlot);
			}
			// The ordinary rank-1 aref dispatch from the SAME arguments: strings,
			// packed and general arrays all behave exactly as an unfused (aref a i)
			// would, including its error shapes.
			case ArefLeaf leaf -> {
				ctx.body.aload(leaf.arrParam);
				emitFallback(java.util.Objects.requireNonNull(leaf.indexNode), ctx, className);
				ctx.restoreSite(leaf.site);
				ctx.body.invokestatic(namedAref1Helper(ctx, className));
			}
			// The ONE draw the prologue took, re-boxed: raw from the slot, or the
			// boxed value _random answered for a limit the raw path could not take.
			// Never a draw -- this emission repeats for a node used twice.
			case RandomLeaf leaf -> {
				if (leaf.limitExpr == null) {
					ctx.body.lload(leaf.longSlot);
					JvmEmitHelper.boxLong(ctx);
				}
				else {
					ctx.body.iload(leaf.flagSlot);
					MethodCode.Label boxed = ctx.body.newLabel();
					ctx.body.ifeq(boxed);
					ctx.body.lload(leaf.longSlot);
					JvmEmitHelper.boxLong(ctx);
					MethodCode.Label done = ctx.body.newLabel();
					ctx.body.goto_(done);
					ctx.body.labelBinding(boxed);
					ctx.body.aload(leaf.boxSlot);
					ctx.body.labelBinding(done);
				}
			}
			// The snapshot re-boxed: the raw param when the flag is set, else the
			// shadow.
			case RawLeaf leaf -> {
				ctx.body.iload(leaf.flagParam);
				MethodCode.Label notRaw = ctx.body.newLabel();
				ctx.body.ifeq(notRaw);
				ctx.body.lload(leaf.rawParam);
				JvmEmitHelper.boxLong(ctx);
				MethodCode.Label done = ctx.body.newLabel();
				ctx.body.goto_(done);
				ctx.body.labelBinding(notRaw);
				ctx.body.aload(leaf.shadowParam);
				ctx.body.labelBinding(done);
			}
			case OpNode op when LispNames.MASK_SIGNED_FIELD.equals(op.op()) -> {
				// The lowering's arm (LispMacroExpander.expandMaskSignedField) through
				// the generic helpers: flip the sign bit, keep the field, take the sign
				// bit's weight back off. A non-integer fails in the logxor, as it does
				// there.
				int size = (int) ((ConstLeaf) op.args().get(0)).value();
				long negativeSignBit = -(1L << (size - 1));
				emitFallback(op.args().get(1), ctx, className);
				JvmEmitHelper.compileLong(negativeSignBit, ctx);
				ctx.restoreSite(op.site());
				ctx.body.invokestatic(numOpFor(LispNames.LOGXOR, JvmNumericRuntimeBuilder.LOGXOR, ctx));
				if (size == 64) {
					JvmEmitHelper.compileBigInteger(
							java.math.BigInteger.ONE.shiftLeft(64).subtract(java.math.BigInteger.ONE), ctx);
				}
				else {
					JvmEmitHelper.compileLong((1L << size) - 1, ctx);
				}
				ctx.body.invokestatic(numOpFor(LispNames.LOGAND, JvmNumericRuntimeBuilder.LOGAND, ctx));
				JvmEmitHelper.compileLong(negativeSignBit, ctx);
				ctx.body.invokestatic(numOpFor(LispNames.ADD, JvmNumericRuntimeBuilder.ADD, ctx));
			}
			case OpNode op -> {
				emitFallback(op.args().get(0), ctx, className);
				for (int i = 1; i < op.args().size(); i++) {
					emitFallback(op.args().get(i), ctx, className);
					// A wrong-type operand fails in this call: it reports this node's
					// form.
					ctx.restoreSite(op.site());
					ctx.body.invokestatic(numOpFor(op.op(), fallbackKey(op.op()), ctx));
				}
				if (LispNames.LOGNOT.equals(op.op())) {
					ctx.restoreSite(op.site());
					ctx.body.invokestatic(numOpFor(op.op(), JvmNumericRuntimeBuilder.LOGNOT, ctx));
				}
			}
		}
	}

	/**
	 * {@link #aref1Helper} under {@code AREF}'s wrapper, as the ordinary emission calls
	 * it: the fallback is compiled away from the form, so an out-of-range subscript names
	 * the access whatever operator the tree node is ({@link JvmOperandTypeRuntime}).
	 */
	private static MethodRefEntry namedAref1Helper(JvmLispCompiler.Ctx ctx, String className) {
		MethodRefEntry ref = aref1Helper(ctx, className);
		@Nullable String outer = ctx.operator;
		ctx.operator = LispNames.AREF;
		try {
			return ctx.wrapForOperator(aref1HelperName(ctx), JvmArrayRuntimeBuilder.AREF1_DESC, ref);
		}
		finally {
			ctx.operator = outer;
		}
	}

	/** The same helper the ordinary rank-1 aref emission calls for this program. */
	private static MethodRefEntry aref1Helper(JvmLispCompiler.Ctx ctx, String className) {
		return JvmEmitHelper.selfMethod(ctx, className, aref1HelperName(ctx), JvmArrayRuntimeBuilder.AREF1_DESC);
	}

	private static String aref1HelperName(JvmLispCompiler.Ctx ctx) {
		return ctx.usesIntArray ? JvmIntArrayRuntimeBuilder.AREF1
				: ctx.usesFloatArray ? JvmFloatArrayRuntimeBuilder.AREF1 : JvmArrayRuntimeBuilder.AREF1;
	}

	/**
	 * A fallback helper under the tree node's own operator: the outlined method is
	 * compiled away from the form, so the node names a wrong-type operand's report
	 * ({@link JvmOperandTypeRuntime}).
	 */
	private static MethodRefEntry numOpFor(String operator, String key, JvmLispCompiler.Ctx ctx) {
		@Nullable String outer = ctx.operator;
		ctx.operator = operator;
		try {
			return ctx.numOp(key);
		}
		finally {
			ctx.operator = outer;
		}
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
	static JvmNumericRuntimeBuilder.NumericMethod buildUbRead(ConstantPool cp, MethodRefEntry longValueOf) {
		MethodCode a = new MethodCode();
		MethodCode.Label useShadow = a.newLabel();
		a.iload(3);
		a.ifeq(useShadow);
		a.lload(1);
		a.invokestatic(longValueOf);
		a.areturn();
		a.labelBinding(useShadow);
		a.aload(0);
		a.areturn();
		return new JvmNumericRuntimeBuilder.NumericMethod(cp.utf8Entry("_ubRead"),
				cp.utf8Entry("(Ljava/lang/Object;JI)Ljava/lang/Object;"), a);
	}

	/**
	 * {@code _fxAsh(long v, long count)}: the raw checked shift matching {@code _ash}'s
	 * {@code Long} fast path exactly -- the count is compared as a {@code long} first
	 * (narrowing first would wrap a huge negative count positive), a count at or below
	 * -64 leaves the sign, a negative count shifts right, and a wide or overflowing left
	 * shift throws {@code ArithmeticException} (the fused region's bail signal, whose
	 * fallback then answers what {@code _ash} answers).
	 */
	static JvmNumericRuntimeBuilder.NumericMethod buildFxAsh(ConstantPool cp) {
		ClassEntry arithEx = cp.classEntry("java/lang/ArithmeticException");
		MethodRefEntry arithExInit = cp.methodRef(arithEx, "<init>", "()V");
		MethodCode a = new MethodCode();
		MethodCode.Label hugeNeg = a.newLabel();
		MethodCode.Label rightShift = a.newLabel();
		MethodCode.Label leftShift = a.newLabel();
		MethodCode.Label overflow = a.newLabel();
		// count < -64: the value shifts down to its sign.
		a.lload(2);
		a.loadConstant(-64);
		a.i2l();
		a.lcmp();
		a.iflt(hugeNeg);
		// count > 63: no shift of a full-width value stays in range (v == 0 bails
		// too -- the fallback answers it exactly).
		a.lload(2);
		a.loadConstant(63);
		a.i2l();
		a.lcmp();
		a.ifgt(overflow);
		// int c = (int) count -- exact: the count is within [-64, 63].
		a.lload(2);
		a.l2i();
		a.istore(4);
		a.iload(4);
		a.ifgt(leftShift);
		// c <= -64: the value shifts down to its sign.
		a.iload(4);
		a.loadConstant(-64);
		a.if_icmpgt(rightShift);
		a.lload(0);
		a.loadConstant(63);
		a.lshr();
		a.lreturn();
		// -64 < c <= 0: v >> -c.
		a.labelBinding(rightShift);
		a.lload(0);
		a.loadConstant(0);
		a.iload(4);
		a.isub();
		a.lshr();
		a.lreturn();
		// c > 0: kept only when the shift round-trips.
		a.labelBinding(leftShift);
		a.iload(4);
		a.loadConstant(64);
		a.if_icmpge(overflow);
		a.lload(0);
		a.iload(4);
		a.lshl();
		a.lstore(5);
		a.lload(5);
		a.iload(4);
		a.lshr();
		a.lload(0);
		a.lcmp();
		a.ifne(overflow);
		a.lload(5);
		a.lreturn();
		a.labelBinding(overflow);
		a.new_(arithEx);
		a.dup();
		a.invokespecial(arithExInit);
		a.athrow();
		a.labelBinding(hugeNeg);
		a.lload(0);
		a.loadConstant(63);
		a.lshr();
		a.lreturn();
		return new JvmNumericRuntimeBuilder.NumericMethod(cp.utf8Entry("_fxAsh"), cp.utf8Entry("(JJ)J"), a);
	}

}

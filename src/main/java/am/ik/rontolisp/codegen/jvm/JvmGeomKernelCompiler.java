package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;

/**
 * Compiles the four {@code geom:} members a model FILE spends its whole load time in --
 * {@code geom:read-obj}, {@code geom:mesh}, {@code geom:wireframe} and the
 * {@code geom::%vertex-extremes} behind {@code geom:bounds} and
 * {@code geom::%model-extent} -- to calls into the embedded {@link JvmGeomTemplate}
 * bridge, with the spliced {@code geom.lisp} defun as the fallback. It is the
 * {@code geom:} sibling of {@link JvmLinalgKernelCompiler} and emits the same shape: the
 * argument forms evaluated ONCE into temps, one bridge attempt, an {@code IFNONNULL} to a
 * common end, and the defun over the same temps below it.
 *
 * <h2>Why a call site rather than a flag</h2>
 *
 * The interpreter puts a Java native over these four the moment {@code geom.lisp} loads
 * ({@code eval/GeomKernels}, {@code .kb/geom.md}), which made it 5.7x FASTER than the
 * same program compiled to a {@code .class} -- the compiled backend still ran
 * {@code %scan-number}'s character loop and {@code %facet-normal}'s Newell sum a few
 * hundred million times. This closes that, and it is not behind a flag for the same
 * reason the interpreter's natives are not: nothing here reassociates, every arithmetic
 * step is the defun's step transcribed, so there is no input on which the bridge changes
 * an answer and a flag would only be a way to get the slow one. {@code ci-spec.yaml}'s
 * {@code geom-read-model-cross-backend} pins that against the interpreter and both WASM
 * backends and must never need a new expectation.
 *
 * <h2>What a program that reads no model pays</h2>
 *
 * Nothing, unless it CALLS one of the four: {@code JvmLispCompiler} gates the whole
 * bridge on {@link #programUsesAnyKernel}, a scan of the already-pruned program for those
 * four qualified names. A program with no {@code geom:} in it is emitted byte for byte as
 * before; a {@code geom} program that measures anything does reach {@code geom:mesh}
 * (through {@code volume} / {@code surface-area} / {@code centroid}) and pays the
 * bridge's bytes, which is the honest reading of "the call site is there".
 */
final class JvmGeomKernelCompiler {

	private JvmGeomKernelCompiler() {
	}

	static final String READ_OBJ = PackageRegistry.qualify(LispNames.GEOM_PKG, "READ-OBJ");

	static final String MESH = PackageRegistry.qualify(LispNames.GEOM_PKG, "MESH");

	static final String WIREFRAME = PackageRegistry.qualify(LispNames.GEOM_PKG, "WIREFRAME");

	static final String VERTEX_EXTREMES = PackageRegistry.qualifyInternal(LispNames.GEOM_PKG, "%VERTEX-EXTREMES");

	/**
	 * The half of {@code geom::%build-solid} past the packing, which the accelerated
	 * {@code read-obj} hands its packed vertex array and index loops to: the colour
	 * default, the identity transform and the {@code make-instance} still live in Lisp.
	 */
	static final String SOLID_OF_VERTICES = PackageRegistry.qualifyInternal(LispNames.GEOM_PKG, "%SOLID-OF-VERTICES");

	/** {@code geom:read-obj}'s own keyword tail, which it passes straight through. */
	private static final String COLOR_KEYWORD = ":COLOR";

	private static final String LABEL_KEYWORD = ":LABEL";

	/** The accelerated members, in a stable order, mapped to their bridge method. */
	private static final Map<String, String> KERNELS = new LinkedHashMap<>();

	private static final Map<String, Integer> ARITY = new LinkedHashMap<>();

	static {
		KERNELS.put(READ_OBJ, "geomReadObj");
		KERNELS.put(MESH, "geomMesh");
		KERNELS.put(WIREFRAME, "geomWireframe");
		KERNELS.put(VERTEX_EXTREMES, "geomVertexExtremes");
		ARITY.put(READ_OBJ, 1);
		ARITY.put(MESH, 1);
		ARITY.put(WIREFRAME, 1);
		ARITY.put(VERTEX_EXTREMES, 3);
	}

	/** The accelerated member names (qualified), in a stable order. */
	static List<String> members() {
		return List.copyOf(KERNELS.keySet());
	}

	/**
	 * The members whose presence ARMS the bridge -- three of the four.
	 *
	 * <p>
	 * {@code geom::%vertex-extremes} is accelerated but does not arm anything, and the
	 * reason is a measurement rather than a preference: {@code LibraryDefunPruner} keys a
	 * definition by NAME, and {@code geom:bounds} is both a {@code defclass} (an unkeyed
	 * root) and a {@code defun}, so the class keeps the function, the function keeps
	 * {@code geom::%solid-bounds} and that keeps {@code geom::%vertex-extremes} -- in
	 * EVERY program that splices geom, including {@code (print (geom:vec3 1 2 3))}. A
	 * gate naming it would therefore be a gate on the splice, which is exactly what this
	 * must not be. The other three have no class twin, so their presence in the pruned
	 * program is a call site; a program that reaches {@code %vertex-extremes} at file
	 * scale reaches one of them too (it read the model, and it meshes or draws it).
	 * @return the qualified names the emit gate scans for
	 */
	static List<String> gateMembers() {
		return List.of(READ_OBJ, MESH, WIREFRAME);
	}

	/** The bridge method backing the given qualified member. */
	static String bridgeMethod(String qualified) {
		return Objects.requireNonNull(KERNELS.get(qualified));
	}

	/** The number of Object parameters the member's bridge method takes. */
	static int arity(String qualified) {
		return Objects.requireNonNull(ARITY.get(qualified));
	}

	/**
	 * The member's canonical qualified spelling: a {@code %}-prefixed member is an
	 * internal symbol and carries the double colon ({@code geom::%vertex-extremes}),
	 * which is how the spliced defun is keyed in {@code ctx.functions} and how the
	 * program references it.
	 * @param member the bare member name
	 * @return the qualified spelling
	 */
	static String qualifiedName(String member) {
		return member.startsWith("%") ? PackageRegistry.qualifyInternal(LispNames.GEOM_PKG, member)
				: PackageRegistry.qualify(LispNames.GEOM_PKG, member);
	}

	/**
	 * Whether the given qualified {@code geom:} name is one this compiler accelerates.
	 */
	static boolean handles(String qualified) {
		return KERNELS.containsKey(qualified);
	}

	/**
	 * Whether this compiler claims the call site of the given qualified member -- because
	 * the bridge was emitted for this program.
	 */
	static boolean claims(String qualified, JvmLispCompiler.Ctx ctx) {
		return ctx.geomOps != null && handles(qualified);
	}

	/**
	 * Emits the accelerated call site, or delegates to the ordinary direct call when the
	 * shape is one the bridge cannot take.
	 * @param qualified the qualified member name
	 * @param cons the call form
	 * @param ctx the compilation context
	 * @param className the internal name of the class being emitted
	 */
	static void compile(String qualified, LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		Map<String, MethodrefConstant> ops = Objects.requireNonNull(ctx.geomOps);
		List<LispVal> args = cons.toList();
		int supplied = args.size() - 1;
		JvmLispCompiler.FunctionInfo defun = ctx.functions.get(qualified);
		if (defun == null) {
			JvmFunctionCallCompiler.compileDefault(qualified, cons, ctx, className);
			return;
		}
		if (READ_OBJ.equals(qualified)) {
			compileReadObj(cons, args, supplied, defun, ops, ctx, className);
			return;
		}
		int arity = arity(qualified);
		if (supplied != arity || defun.variadic() || defun.paramCount() != arity) {
			// A call that is not the member's own shape, or a defun whose lambda list no
			// longer matches: the ordinary direct-call path handles both.
			JvmFunctionCallCompiler.compileDefault(qualified, cons, ctx, className);
			return;
		}
		emitInit(ctx, ops);
		int[] slots = compileArgsIntoTemps(args, supplied, ctx, className);
		List<Integer> taken = new ArrayList<>();
		emitAttempt(ctx, ops, qualified, slots, arity, taken);
		loadAll(ctx, slots, arity);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(defun.methodref().index());
		for (int branchPos : taken) {
			JvmEmitHelper.patchBranch(ctx, branchPos, ctx.code.size());
		}
	}

	/**
	 * {@code geom:read-obj} is the one member whose accelerated answer is not the
	 * member's answer: the bridge scans the file into the packed vertex array and the
	 * index loops, and the SOLID is still built by the Lisp
	 * {@code geom::%solid-of-vertices}, which takes the very {@code :color} /
	 * {@code :label} tail {@code read-obj} declares. So the emitted shape is
	 *
	 * <pre>
	 *   _geomInit(); p = &lt;path&gt;; k1 = &lt;:color&gt;; v1 = ...;   // once, into temps
	 *   r = Bridge.geomReadObj(p);
	 *   if (r != null) result = %solid-of-vertices(r[0], r[1], rest);
	 *   else           result = geom:read-obj(p, rest);
	 * </pre>
	 *
	 * The keyword tail is required to be the literal {@code :color} / {@code :label}
	 * pairs the reader declares; anything else declines at COMPILE time, so the defun's
	 * own lambda list still signals about it.
	 */
	private static void compileReadObj(LispCons cons, List<LispVal> args, int supplied,
			JvmLispCompiler.FunctionInfo defun, Map<String, MethodrefConstant> ops, JvmLispCompiler.Ctx ctx,
			String className) {
		JvmLispCompiler.FunctionInfo builder = ctx.functions.get(SOLID_OF_VERTICES);
		if (builder == null || !builder.variadic() || builder.paramCount() != 3 || !defun.variadic()
				|| defun.paramCount() != 2 || !keywordTail(args)) {
			JvmFunctionCallCompiler.compileDefault(READ_OBJ, cons, ctx, className);
			return;
		}
		emitInit(ctx, ops);
		int[] slots = compileArgsIntoTemps(args, supplied, ctx, className);
		// The keyword tail as one cons list, built once and handed to whichever of the
		// two variadic defuns runs: both declare exactly (&key color label).
		int restSlot = emitRestList(ctx, slots, 1, supplied);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(Objects.requireNonNull(ops.get(AVAILABLE_KEY)).index());
		int skipPos = ctx.code.size();
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(slots[0]);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(Objects.requireNonNull(ops.get(READ_OBJ)).index());
		int scanSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(scanSlot);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(scanSlot);
		int declinedPos = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0);
		// %solid-of-vertices(scan[0], scan[1], rest)
		emitScanElement(ctx, scanSlot, 0);
		emitScanElement(ctx, scanSlot, 1);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(restSlot);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(builder.methodref().index());
		int takenPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		JvmEmitHelper.patchBranch(ctx, skipPos, ctx.code.size());
		JvmEmitHelper.patchBranch(ctx, declinedPos, ctx.code.size());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(slots[0]);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(restSlot);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(defun.methodref().index());
		JvmEmitHelper.patchBranch(ctx, takenPos, ctx.code.size());
	}

	/** {@code (Object[]) scan}{@code [index]}, as one expression on the stack. */
	private static void emitScanElement(JvmLispCompiler.Ctx ctx, int scanSlot, int index) {
		ctx.emit(Opcode.ALOAD);
		ctx.emit(scanSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		JvmEmitHelper.emitIntConst(ctx, index);
		ctx.emit(Opcode.AALOAD);
	}

	/**
	 * Whether the arguments past the path are exactly the {@code :color} / {@code :label}
	 * keyword pairs {@code geom:read-obj} declares -- literal keywords, so the tail can
	 * be handed to {@code geom::%solid-of-vertices} unexamined at runtime.
	 */
	private static boolean keywordTail(List<LispVal> args) {
		if ((args.size() - 2) % 2 != 0) {
			return false;
		}
		for (int i = 2; i < args.size(); i += 2) {
			if (!(args.get(i) instanceof LispSymbol key)
					|| !(COLOR_KEYWORD.equals(key.name()) || LABEL_KEYWORD.equals(key.name()))) {
				return false;
			}
		}
		return true;
	}

	/** Evaluates each argument exactly once, into a temp every branch reads. */
	private static int[] compileArgsIntoTemps(List<LispVal> args, int supplied, JvmLispCompiler.Ctx ctx,
			String className) {
		int[] slots = new int[supplied];
		for (int i = 0; i < supplied; i++) {
			JvmExprCompiler.compileExpr(args.get(i + 1), ctx, className);
			slots[i] = ctx.allocTemp();
			ctx.emit(Opcode.ASTORE);
			ctx.emit(slots[i]);
		}
		return slots;
	}

	/**
	 * Links the temps {@code [from, to)} into a cons list, newest link first (an empty
	 * rest list is compiled nil, null) -- {@link JvmLinalgKernelCompiler}'s variadic
	 * tail, kept in a local so both branches read the same one.
	 */
	private static int emitRestList(JvmLispCompiler.Ctx ctx, int[] slots, int from, int to) {
		int restSlot = ctx.allocTemp();
		ctx.emit(Opcode.ACONST_NULL);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(restSlot);
		for (int k = to - 1; k >= from; k--) {
			ctx.emit(Opcode.ICONST_2);
			ctx.emit(Opcode.ANEWARRAY);
			ctx.emitU2(ctx.objectClass.index());
			ctx.emit(Opcode.DUP);
			ctx.emit(Opcode.ICONST_0);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(slots[k]);
			ctx.emit(Opcode.AASTORE);
			ctx.emit(Opcode.DUP);
			ctx.emit(Opcode.ICONST_1);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(restSlot);
			ctx.emit(Opcode.AASTORE);
			ctx.emit(Opcode.ASTORE);
			ctx.emit(restSlot);
		}
		return restSlot;
	}

	/** The {@code ops} key of the availability accessor. */
	private static final String AVAILABLE_KEY = JvmGeomRuntimeBuilder.AVAILABLE;

	private static void emitInit(JvmLispCompiler.Ctx ctx, Map<String, MethodrefConstant> ops) {
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(Objects.requireNonNull(ops.get("init")).index());
	}

	/**
	 * The one link of the chain, guarded by {@code _geomReady()}: a JRE that could not
	 * define the bridge never resolves a method reference into it and lands exactly where
	 * a declined kernel would, on the spliced defun.
	 */
	private static void emitAttempt(JvmLispCompiler.Ctx ctx, Map<String, MethodrefConstant> ops, String kernelKey,
			int[] slots, int arity, List<Integer> takenBranches) {
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(Objects.requireNonNull(ops.get(AVAILABLE_KEY)).index());
		int skipPos = ctx.code.size();
		ctx.emit(Opcode.IFEQ);
		ctx.emitU2(0);
		loadAll(ctx, slots, arity);
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(Objects.requireNonNull(ops.get(kernelKey)).index());
		// if (result != null) goto end; else fall through to the defun.
		ctx.emit(Opcode.DUP);
		takenBranches.add(ctx.code.size());
		ctx.emit(Opcode.IFNONNULL);
		ctx.emitU2(0);
		ctx.emit(Opcode.POP);
		JvmEmitHelper.patchBranch(ctx, skipPos, ctx.code.size());
	}

	private static void loadAll(JvmLispCompiler.Ctx ctx, int[] slots, int count) {
		for (int i = 0; i < count; i++) {
			ctx.emit(Opcode.ALOAD);
			ctx.emit(slots[i]);
		}
	}

}

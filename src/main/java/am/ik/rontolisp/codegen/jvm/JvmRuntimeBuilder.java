package am.ik.rontolisp.codegen.jvm;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.RenderCycleGuard;

/**
 * Builds JVM bytecode for runtime helper methods: dispatch, _lispToString, and
 * _consToString.
 */
final class JvmRuntimeBuilder {

	private JvmRuntimeBuilder() {
	}

	/**
	 * The renderers' depth cap, shared with the interpreter so the truncated rendering is
	 * byte-identical on every backend.
	 */
	private static final int RENDER_DEPTH_CAP = RenderCycleGuard.MAX_RENDER_DEPTH;

	/**
	 * The byte budget of one {@code _invoke_<arity>} dispatch segment. The case bodies
	 * grow linearly with the number of callables of that arity (every variadic function
	 * matches every arity above its required count), so one method holding them all
	 * crossed the JVM's 64 KB method-code limit at cl-postgres scale (66 KB of arity-9
	 * cases).
	 * <p>
	 * The value is set below HotSpot's {@code HugeMethodLimit} (8000 bytecodes), NOT just
	 * below the class-file limit: {@code -XX:+DontCompileHugeMethods} is on by default,
	 * so a dispatcher past that size is never JIT-compiled and every indirect call
	 * through it runs in the bytecode interpreter. That cliff -- not the branch count --
	 * is what made a hot loop several times slower merely because the program also loaded
	 * a class-defining library: one more library pushed the shared dispatcher past 8000
	 * bytecodes. Measured on PBKDF2-SHA256 inside the cl-postgres stack, crossing it cost
	 * 2.8x (`-XX:-DontCompileHugeMethods` recovered exactly that).
	 * <p>
	 * It also keeps every branch inside a segment -- the search tree's forward jumps and
	 * the default arm at its end -- well within the signed 16-bit branch offset.
	 */
	private static final int DISPATCH_SEGMENT_BUDGET = 6_000;

	/**
	 * Bytes reserved per case on top of its body for the search tree that reaches it: one
	 * leaf comparison plus the amortized share of the internal nodes above it.
	 */
	private static final int DISPATCH_CASE_OVERHEAD = 24;

	static List<JvmLispCompiler.DispatchMethod> buildDispatchMethods(int arity,
			Map<String, JvmLispCompiler.FunctionInfo> functions, List<JvmLispCompiler.LambdaInfo> lambdaDecls,
			List<JvmLispCompiler.FunctionInfo> lambdaFuncInfos, ConstantPool cp, ClassConstant thisClass,
			ClassConstant objectArrayClass, ClassConstant integerClass, MethodrefConstant integerValue,
			ClassConstant objectClass, ClassConstant stringClass,
			@org.jspecify.annotations.Nullable MethodrefConstant applyRef,
			@org.jspecify.annotations.Nullable MethodrefConstant lookupRef,
			@org.jspecify.annotations.Nullable Set<Integer> dispatchable) {
		return buildDispatchMethods(arity, functions, lambdaDecls, lambdaFuncInfos, cp, thisClass, objectArrayClass,
				integerClass, integerValue, objectClass, stringClass, applyRef, lookupRef, false, dispatchable);
	}

	/**
	 * As above, with {@code spread} selecting the SPREAD dispatcher {@code _invoke_v}:
	 * one method over EVERY callable, taking the argument list as a single cons list
	 * instead of one parameter per argument.
	 *
	 * <p>
	 * It exists because {@code _apply} cannot be expressed with the per-arity
	 * dispatchers. Those take one JVM parameter per Lisp argument, so they stop at
	 * {@link JvmEvalRuntimeBuilder#MAX_CALLABLE_ARITY} -- and an {@code apply} whose
	 * designator is COMPUTED has to go through them, so
	 * {@code (apply (scheme-constructor s) :scheme s :userinfo u ... )} (quri's
	 * {@code uri}: fourteen arguments into a {@code &key} constructor) silently answered
	 * nil. A spread dispatcher has no such ceiling: each case car/cdr-walks its required
	 * parameters out of the list and hands a variadic target the remaining TAIL verbatim,
	 * which is the callee's physical rest parameter. It is also cheaper than raising the
	 * per-arity ceiling would be -- one case per function, not one per (function, arity)
	 * pair, since a variadic function matches every arity at or above its required count.
	 * @param arity ignored when {@code spread} is true
	 * @param spread whether to build the spread dispatcher instead of an arity one
	 * @return the dispatcher method(s): one, or a router plus segments
	 */
	static List<JvmLispCompiler.DispatchMethod> buildDispatchMethods(int arity,
			Map<String, JvmLispCompiler.FunctionInfo> functions, List<JvmLispCompiler.LambdaInfo> lambdaDecls,
			List<JvmLispCompiler.FunctionInfo> lambdaFuncInfos, ConstantPool cp, ClassConstant thisClass,
			ClassConstant objectArrayClass, ClassConstant integerClass, MethodrefConstant integerValue,
			ClassConstant objectClass, ClassConstant stringClass,
			@org.jspecify.annotations.Nullable MethodrefConstant applyRef,
			@org.jspecify.annotations.Nullable MethodrefConstant lookupRef, boolean spread,
			@org.jspecify.annotations.Nullable Set<Integer> dispatchable) {
		// Descriptor: (Object funcval, Object a0, ..., Object aN-1) -> Object, or
		// (Object funcval, Object argList) -> Object for the spread dispatcher.
		int dispatchArgs = spread ? 1 : arity;
		String desc = "(" + "Ljava/lang/Object;".repeat(dispatchArgs + 1) + ")Ljava/lang/Object;";
		Utf8Constant descUtf8 = cp.addUtf8(desc);
		// Params: slot 0=funcval, slot 1..arity=args
		// Extra locals: fvSlot=arity+1 (Object[] fv), idSlot=arity+2 (int id),
		// restSlot=arity+3 (arg list for the _apply fallback)
		int fvSlot = dispatchArgs + 1;
		int idSlot = dispatchArgs + 2;
		int restSlot = dispatchArgs + 3;
		int maxLocals = dispatchArgs + 4;
		// The matching callables: named functions plus lambdas (whose closure env is
		// passed as the first argument). A variadic function (physical params =
		// required + rest list) matches every dispatch arity >= required; its case
		// links the surplus args into a cons list. Each case body is rendered ONCE,
		// branch-free and ending in areturn, so it can be spliced anywhere; funcIds
		// are globally unique (one shared counter over defuns and lambdas), so
		// sorting by id gives the search tree below a total order to bisect.
		List<Case> cases = new ArrayList<>();
		for (Map.Entry<String, JvmLispCompiler.FunctionInfo> entry : functions.entrySet()) {
			JvmLispCompiler.FunctionInfo fi = entry.getValue();
			if (fi.isClosure()) {
				continue;
			}
			// A funcId the program never turns into a function VALUE is only ever
			// called directly, so a case for it would do nothing except keep the
			// method reachable for JvmClassShaker (JvmLispCompiler.dispatchableFuncIds).
			if (dispatchable != null && !dispatchable.contains(fi.funcId())) {
				continue;
			}
			// The spread dispatcher takes EVERY callable: its case reads the parameters
			// out of the list, so no arity has to match and no ceiling applies.
			if (spread) {
				cases.add(renderSpreadCase(fi, -1, objectArrayClass));
			}
			else if (dispatchMatches(fi.paramCount(), fi.variadic(), arity)) {
				cases.add(renderCase(fi, arity, restSlot, -1, objectClass));
			}
		}
		for (int i = 0; i < lambdaDecls.size(); i++) {
			JvmLispCompiler.LambdaInfo lambda = lambdaDecls.get(i);
			if (dispatchable != null && !dispatchable.contains(lambda.funcId())) {
				continue;
			}
			if (spread) {
				cases.add(renderSpreadCase(lambdaFuncInfos.get(i), fvSlot, objectArrayClass));
			}
			else if (dispatchMatches(lambda.paramNames().size(), lambda.variadic(), arity)) {
				cases.add(renderCase(lambdaFuncInfos.get(i), arity, restSlot, fvSlot, objectClass));
			}
		}
		cases.sort(Comparator.comparingInt(Case::funcId));
		// Split the id-sorted cases into segments small enough to stay JIT-compilable
		// (see DISPATCH_SEGMENT_BUDGET). With more than one segment, _invoke_<arity>
		// becomes a router that binary-searches the segment boundaries and tail-calls
		// the one segment that can hold the id.
		List<int[]> ranges = partitionCases(cases);
		boolean routed = ranges.size() > 1;
		List<MethodrefConstant> segmentRefs = new ArrayList<>();
		if (routed) {
			for (int k = 0; k < ranges.size(); k++) {
				segmentRefs.add(cp.addMethodref(thisClass,
						cp.addNameAndType(cp.addUtf8(dispatcherName(arity, spread) + "$" + k), descUtf8)));
			}
		}
		List<JvmLispCompiler.DispatchMethod> segments = new ArrayList<>();
		for (int segment = 0; segment <= (routed ? ranges.size() : 0); segment++) {
			String name = segment == 0 ? dispatcherName(arity, spread)
					: dispatcherName(arity, spread) + "$" + (segment - 1);
			Utf8Constant nameUtf8 = cp.addUtf8(name);
			List<Integer> code = new ArrayList<>();
			if (segment == 0 && lookupRef != null) {
				// A String funcval is a SYMBOL used as a function designator (the
				// interpreter's late binding): resolve it through _lookup, whose
				// Object[]{funcId, arity} result carries the id in slot 0 exactly like
				// a function value. An unknown name answers null like the default arm.
				// A chained segment receives the already-resolved fv.
				code.add(Opcode.ALOAD_0);
				code.add(Opcode.INSTANCEOF);
				emitU2(code, stringClass.index());
				int ifNotStringPos = code.size();
				code.add(Opcode.IFEQ);
				emitU2(code, 0);
				code.add(Opcode.ALOAD_0);
				code.add(Opcode.INVOKESTATIC);
				emitU2(code, lookupRef.index());
				code.add(Opcode.ASTORE);
				code.add(fvSlot);
				code.add(Opcode.ALOAD);
				code.add(fvSlot);
				int ifResolvedPos = code.size();
				code.add(Opcode.IFNONNULL);
				emitU2(code, 0);
				// throw new RuntimeException("The function " + name + " is
				// undefined") -- the interpreter's late-binding failure, catchable by
				// handler-case like any signalled error.
				ClassConstant runtimeEx = cp.addClass(cp.addUtf8("java/lang/RuntimeException"));
				MethodrefConstant exCtor = cp.addMethodref(runtimeEx,
						cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
				MethodrefConstant stringConcat = cp.addMethodref(stringClass,
						cp.addNameAndType(cp.addUtf8("concat"), cp.addUtf8("(Ljava/lang/String;)Ljava/lang/String;")));
				code.add(Opcode.NEW);
				emitU2(code, runtimeEx.index());
				code.add(Opcode.DUP);
				code.add(Opcode.LDC_W);
				emitU2(code, cp.addString("The function ").index());
				code.add(Opcode.ALOAD_0);
				code.add(Opcode.CHECKCAST);
				emitU2(code, stringClass.index());
				code.add(Opcode.INVOKEVIRTUAL);
				emitU2(code, stringConcat.index());
				code.add(Opcode.LDC_W);
				emitU2(code, cp.addString(" is undefined").index());
				code.add(Opcode.INVOKEVIRTUAL);
				emitU2(code, stringConcat.index());
				code.add(Opcode.INVOKESPECIAL);
				emitU2(code, exCtor.index());
				code.add(Opcode.ATHROW);
				patchBranch(code, ifNotStringPos, code.size());
				// Object[] fv = (Object[]) funcval;
				code.add(Opcode.ALOAD_0);
				code.add(Opcode.CHECKCAST);
				emitU2(code, objectArrayClass.index());
				code.add(Opcode.ASTORE);
				code.add(fvSlot);
				patchBranch(code, ifResolvedPos, code.size());
			}
			else {
				// Object[] fv = (Object[]) funcval;
				code.add(Opcode.ALOAD_0);
				code.add(Opcode.CHECKCAST);
				emitU2(code, objectArrayClass.index());
				code.add(Opcode.ASTORE);
				code.add(fvSlot);
			}
			// int id = ((Integer) fv[0]).intValue();
			code.add(Opcode.ALOAD);
			code.add(fvSlot);
			code.add(Opcode.ICONST_0);
			code.add(Opcode.AALOAD);
			code.add(Opcode.CHECKCAST);
			emitU2(code, integerClass.index());
			code.add(Opcode.INVOKEVIRTUAL);
			emitU2(code, integerValue.index());
			code.add(Opcode.ISTORE);
			code.add(idSlot);
			// Interpreted closure (funcId == -1, created by the eval runtime's
			// lambda): delegate to _apply with the arguments collected into a cons
			// list. Segment 0 only: a chained segment sees the same id.
			if (segment == 0 && applyRef != null && !spread) {
				code.add(Opcode.ILOAD);
				code.add(idSlot);
				code.add(Opcode.ICONST_M1);
				int ifPos = code.size();
				code.add(Opcode.IF_ICMPNE);
				emitU2(code, 0);
				code.add(Opcode.ACONST_NULL);
				code.add(Opcode.ASTORE);
				code.add(restSlot);
				for (int j = arity - 1; j >= 0; j--) {
					code.add(Opcode.ICONST_2);
					code.add(Opcode.ANEWARRAY);
					emitU2(code, objectClass.index());
					code.add(Opcode.DUP);
					code.add(Opcode.ICONST_0);
					code.add(Opcode.ALOAD);
					code.add(j + 1);
					code.add(Opcode.AASTORE);
					code.add(Opcode.DUP);
					code.add(Opcode.ICONST_1);
					code.add(Opcode.ALOAD);
					code.add(restSlot);
					code.add(Opcode.AASTORE);
					code.add(Opcode.ASTORE);
					code.add(restSlot);
				}
				code.add(Opcode.ALOAD_0);
				code.add(Opcode.ALOAD);
				code.add(restSlot);
				code.add(Opcode.INVOKESTATIC);
				emitU2(code, applyRef.index());
				code.add(Opcode.ARETURN);
				patchBranch(code, ifPos, code.size());
			}
			if (routed && segment == 0) {
				// The router: bisect the segment boundaries and pass the RESOLVED fv on
				// (so a String-designator lookup happens once) with the arguments
				// unchanged. An id below the first segment's range, or above the last
				// one's, lands in a real segment whose tree answers null for it.
				emitSegmentRouter(code, cases, ranges, 0, ranges.size() - 1, idSlot, fvSlot, dispatchArgs, segmentRefs);
			}
			else {
				int[] range = routed ? ranges.get(segment - 1) : new int[] { 0, cases.size() - 1 };
				List<Integer> defaultJumps = new ArrayList<>();
				emitDispatchTree(code, cases, range[0], range[1], idSlot, defaultJumps);
				// Default: an id no case of this arity claims.
				int defaultPos = code.size();
				for (int jump : defaultJumps) {
					patchBranch(code, jump, defaultPos);
				}
				code.add(Opcode.ACONST_NULL);
				code.add(Opcode.ARETURN);
			}
			segments.add(new JvmLispCompiler.DispatchMethod(nameUtf8, descUtf8, code, maxLocals));
		}
		return segments;
	}

	/**
	 * Splits the id-sorted cases into contiguous runs whose emitted code stays inside
	 * {@link #DISPATCH_SEGMENT_BUDGET}. Returns {@code {firstIndex, lastIndex}} pairs; an
	 * empty case list yields a single empty-but-valid range.
	 */
	private static List<int[]> partitionCases(List<Case> cases) {
		List<int[]> ranges = new ArrayList<>();
		if (cases.isEmpty()) {
			return List.of(new int[] { 0, -1 });
		}
		int first = 0;
		int used = 0;
		for (int i = 0; i < cases.size(); i++) {
			int cost = cases.get(i).body().size() + DISPATCH_CASE_OVERHEAD;
			if (i > first && used + cost > DISPATCH_SEGMENT_BUDGET) {
				ranges.add(new int[] { first, i - 1 });
				first = i;
				used = 0;
			}
			used += cost;
		}
		ranges.add(new int[] { first, cases.size() - 1 });
		return ranges;
	}

	/**
	 * Emits the router's search tree over {@code ranges[lo..hi]}: each internal node
	 * compares the id against the largest id its left half holds, each leaf tail-calls
	 * that segment.
	 */
	private static void emitSegmentRouter(List<Integer> code, List<Case> cases, List<int[]> ranges, int lo, int hi,
			int idSlot, int fvSlot, int arity, List<MethodrefConstant> segmentRefs) {
		if (lo == hi) {
			code.add(Opcode.ALOAD);
			code.add(fvSlot);
			for (int i = 0; i < arity; i++) {
				code.add(Opcode.ALOAD);
				code.add(i + 1);
			}
			code.add(Opcode.INVOKESTATIC);
			emitU2(code, segmentRefs.get(lo).index());
			code.add(Opcode.ARETURN);
			return;
		}
		int mid = (lo + hi) >>> 1;
		code.add(Opcode.ILOAD);
		code.add(idSlot);
		emitIntConstStatic(code, cases.get(ranges.get(mid)[1]).funcId());
		int ifRight = code.size();
		code.add(Opcode.IF_ICMPGT);
		emitU2(code, 0);
		emitSegmentRouter(code, cases, ranges, lo, mid, idSlot, fvSlot, arity, segmentRefs);
		patchBranch(code, ifRight, code.size());
		emitSegmentRouter(code, cases, ranges, mid + 1, hi, idSlot, fvSlot, arity, segmentRefs);
	}

	private static boolean dispatchMatches(int paramCount, boolean variadic, int arity) {
		return variadic ? arity >= paramCount - 1 : paramCount == arity;
	}

	/**
	 * One dispatch target: the funcId to match and the branch-free body that calls it,
	 * ending in {@code areturn}. The body contains no branches, so it can be spliced at
	 * any position in the search tree without re-patching.
	 */
	private record Case(int funcId, List<Integer> body) {
	}

	/**
	 * Emits the search tree over {@code cases[lo..hi]} (sorted by funcId): each internal
	 * node compares the id against the midpoint and jumps to the right half, each leaf
	 * compares for equality and splices the case body. A leaf's mismatch branch position
	 * is collected in {@code defaultJumps} for the caller to patch to the default arm.
	 * This is what keeps an indirect call's cost logarithmic in the number of callables
	 * of that arity rather than linear in it: the previous if-else chain walked an
	 * average of half the callables per call, so merely loading another class-defining
	 * library taxed every hot indirect call in the program.
	 */
	private static void emitDispatchTree(List<Integer> code, List<Case> cases, int lo, int hi, int idSlot,
			List<Integer> defaultJumps) {
		if (lo > hi) {
			return;
		}
		if (lo == hi) {
			code.add(Opcode.ILOAD);
			code.add(idSlot);
			emitIntConstStatic(code, cases.get(lo).funcId());
			defaultJumps.add(code.size());
			code.add(Opcode.IF_ICMPNE);
			emitU2(code, 0);
			code.addAll(cases.get(lo).body());
			return;
		}
		int mid = (lo + hi) >>> 1;
		code.add(Opcode.ILOAD);
		code.add(idSlot);
		emitIntConstStatic(code, cases.get(mid).funcId());
		int ifRight = code.size();
		code.add(Opcode.IF_ICMPGT);
		emitU2(code, 0);
		emitDispatchTree(code, cases, lo, mid, idSlot, defaultJumps);
		patchBranch(code, ifRight, code.size());
		emitDispatchTree(code, cases, mid + 1, hi, idSlot, defaultJumps);
	}

	// Renders one dispatch case body: "...; return f(...)". For a variadic target the
	// args beyond the required count are linked into a cons list (built in restSlot)
	// passed as the trailing rest parameter; fvSlot >= 0 marks a closure whose env array
	// is passed first.
	/** The dispatcher method name: per-arity, or the single spread one. */
	static String dispatcherName(int arity, boolean spread) {
		return spread ? "_invoke_v" : "_invoke_" + arity;
	}

	/**
	 * One case of the spread dispatcher {@code _invoke_v(funcval, argList)}: reads the
	 * target's required parameters out of the argument list (slot 1) and hands a variadic
	 * target the remaining tail, which IS its physical rest parameter. Needs no scratch
	 * local -- each parameter is re-walked from the head of the list, which costs a few
	 * instructions per parameter and keeps the case body self-contained so it can be
	 * spliced into any segment.
	 */
	private static Case renderSpreadCase(JvmLispCompiler.FunctionInfo fi, int fvSlot, ClassConstant objectArrayClass) {
		List<Integer> code = new ArrayList<>();
		int required = fi.variadic() ? fi.paramCount() - 1 : fi.paramCount();
		if (fvSlot >= 0) {
			code.add(Opcode.ALOAD);
			code.add(fvSlot);
		}
		for (int i = 0; i < required; i++) {
			code.add(Opcode.ALOAD_1);
			for (int step = 0; step < i; step++) {
				emitCell(code, objectArrayClass, 1);
			}
			emitCell(code, objectArrayClass, 0);
		}
		if (fi.variadic()) {
			code.add(Opcode.ALOAD_1);
			for (int step = 0; step < required; step++) {
				emitCell(code, objectArrayClass, 1);
			}
		}
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, fi.methodref().index());
		code.add(Opcode.ARETURN);
		return new Case(fi.funcId(), code);
	}

	// Replaces the cons on the stack with its car (field 0) or cdr (field 1); nil passes
	// through, like the car/cdr built-ins, so a short argument list binds the missing
	// parameters to nil instead of trapping.
	private static void emitCell(List<Integer> code, ClassConstant objectArrayClass, int field) {
		code.add(Opcode.DUP);
		int ifNullPos = code.size();
		code.add(Opcode.IFNULL);
		emitU2(code, 0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, objectArrayClass.index());
		code.add(field == 0 ? Opcode.ICONST_0 : Opcode.ICONST_1);
		code.add(Opcode.AALOAD);
		patchBranch(code, ifNullPos, code.size());
	}

	private static Case renderCase(JvmLispCompiler.FunctionInfo fi, int arity, int restSlot, int fvSlot,
			ClassConstant objectClass) {
		List<Integer> code = new ArrayList<>();
		int required = fi.variadic() ? fi.paramCount() - 1 : fi.paramCount();
		if (fi.variadic()) {
			// rest = null; for (j = arity-1 .. required) rest = new Object[]{a_j, rest}
			code.add(Opcode.ACONST_NULL);
			code.add(Opcode.ASTORE);
			code.add(restSlot);
			for (int j = arity - 1; j >= required; j--) {
				code.add(Opcode.ICONST_2);
				code.add(Opcode.ANEWARRAY);
				emitU2(code, objectClass.index());
				code.add(Opcode.DUP);
				code.add(Opcode.ICONST_0);
				code.add(Opcode.ALOAD);
				code.add(j + 1);
				code.add(Opcode.AASTORE);
				code.add(Opcode.DUP);
				code.add(Opcode.ICONST_1);
				code.add(Opcode.ALOAD);
				code.add(restSlot);
				code.add(Opcode.AASTORE);
				code.add(Opcode.ASTORE);
				code.add(restSlot);
			}
		}
		if (fvSlot >= 0) {
			code.add(Opcode.ALOAD);
			code.add(fvSlot);
		}
		for (int i = 0; i < required; i++) {
			code.add(Opcode.ALOAD);
			code.add(i + 1);
		}
		if (fi.variadic()) {
			code.add(Opcode.ALOAD);
			code.add(restSlot);
		}
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, fi.methodref().index());
		code.add(Opcode.ARETURN);
		return new Case(fi.funcId(), code);
	}

	/**
	 * Builds bytecode for _lispToString. Handles Long, Double, String, BigInteger[]
	 * (ratio), Object[] (cons or function), and fallback toString.
	 */
	static List<Integer> buildLispToStringBody(ClassConstant longClass, ClassConstant doubleClass,
			ClassConstant stringClass, ClassConstant objectArrayClass, ClassConstant integerClass,
			MethodrefConstant longToString, MethodrefConstant doubleToString, FloatPrint floatPrint,
			MethodrefConstant objectToString, MethodrefConstant consToStringMethod, ConstantPool.StringConstant nilStr,
			ConstantPool.StringConstant funcStr, ClassConstant ratioArrayClass, MethodrefConstant stringConcat,
			ConstantPool.StringConstant slashStr, ClassConstant charBoxClass, MethodrefConstant charPrin1Method,
			@org.jspecify.annotations.Nullable ClassConstant arrayListClass,
			@org.jspecify.annotations.Nullable MethodrefConstant arrayToStringMethod,
			@org.jspecify.annotations.Nullable MethodrefConstant strvMethod,
			@org.jspecify.annotations.Nullable JavaPrint javaPrint,
			@org.jspecify.annotations.Nullable ObjcPrint objcPrint,
			@org.jspecify.annotations.Nullable ObjcPrint ffiPrint,
			@org.jspecify.annotations.Nullable FuturePrint futurePrint,
			@org.jspecify.annotations.Nullable PackedPrint packedPrint,
			@org.jspecify.annotations.Nullable PackedIntPrint packedIntPrint,
			@org.jspecify.annotations.Nullable InstPrint instPrint, MethodrefConstant strEscMethod,
			@org.jspecify.annotations.Nullable HashPrint hashPrint) {
		List<Integer> code = new ArrayList<>();
		// if (val == null) return "nil";
		code.add(Opcode.ALOAD_0);
		int ifNonnullPos = code.size();
		code.add(Opcode.IFNONNULL);
		emitU2(code, 0);
		emitLdc(code, nilStr.index());
		code.add(Opcode.ARETURN);

		// if (val instanceof Long) return ((Long)val).toString();
		patchBranch(code, ifNonnullPos, code.size());
		// if (val instanceof CompletableFuture) return "#<FUTURE>"; (only when the
		// program can create futures)
		emitFutureBranch(code, futurePrint);
		// if (val instanceof double[]) return
		// _arrayToString(_fvToGeneral(val)).replaceFirst(...#d...); and
		// if (val instanceof long[]) return _arrayToString(_ivToGeneral(val)); and
		// if (val instanceof ArrayList) return _arrayToString(val); (only when arrays
		// used; a mutable character vector instead renders via _strv, quote-framed like
		// the String branch)
		emitArrayBranch(code, arrayListClass, arrayToStringMethod, packedPrint, packedIntPrint, strvMethod, stringClass,
				null, null, strEscMethod);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, longClass.index());
		int ifNotLongPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, longClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, longToString.index());
		code.add(Opcode.ARETURN);

		// if (val instanceof Double) return ((Double)val).toString().replace("E", "e");
		// (the FloatText lowercase-marker spelling, identical on every backend)
		patchBranch(code, ifNotLongPos, code.size());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, doubleClass.index());
		int ifNotDoublePos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, doubleClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, doubleToString.index());
		emitLdc(code, floatPrint.upperE().index());
		emitLdc(code, floatPrint.lowerE().index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, floatPrint.stringReplace().index());
		code.add(Opcode.ARETURN);

		// if (val instanceof Float) return ((Float)val).toString().replace("E", "e");
		// A Float box exists only transiently while a packed single-float array prints
		// its elements at their f32 width (_fvToGeneralPrint); no Lisp value holds one.
		patchBranch(code, ifNotDoublePos, code.size());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, floatPrint.floatClass().index());
		int ifNotFloatPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, floatPrint.floatClass().index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, floatPrint.floatToString().index());
		emitLdc(code, floatPrint.upperE().index());
		emitLdc(code, floatPrint.lowerE().index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, floatPrint.stringReplace().index());
		code.add(Opcode.ARETURN);

		// if (val instanceof String) return _strEsc((String)val);
		//
		// The quote-framed content still needs its embedded " and \ escaped before it can
		// be read back; _strEsc passes a bare symbol name through untouched (todo 216).
		patchBranch(code, ifNotFloatPos, code.size());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, stringClass.index());
		int ifNotStringPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, stringClass.index());
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, strEscMethod.index());
		code.add(Opcode.ARETURN);

		// if (val instanceof int[]) return _charPrin1(((int[])val)[0]);
		//
		// A CHARACTER on the JVM compile path is a length-1 int[] whose sole element is
		// the Unicode code point (see JvmEmitHelper.boxCodePoint/unboxCodePoint). The
		// discriminator is INSTANCEOF [I -- disjoint from Object[] (functions/cons),
		// BigInteger[] (ratios) and the packed double[]/float[] arrays -- so no earlier
		// branch consumes it.
		patchBranch(code, ifNotStringPos, code.size());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, charBoxClass.index());
		int ifNotCharPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, charBoxClass.index());
		code.add(Opcode.ICONST_0);
		code.add(Opcode.IALOAD);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, charPrin1Method.index());
		code.add(Opcode.ARETURN);

		// if (val instanceof BigInteger[]) -> "num/den" (must precede the Object[]
		// check: a ratio is also an Object[])
		patchBranch(code, ifNotCharPos, code.size());
		int ifNotRatioPos = emitRatioToString(code, ratioArrayClass, objectToString, stringConcat, slashStr);

		// if (val instanceof Object[])
		patchBranch(code, ifNotRatioPos, code.size());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, objectArrayClass.index());
		int ifNotArrayPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		// Cast to Object[] and store in slot 1
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, objectArrayClass.index());
		code.add(Opcode.ASTORE_1);
		// Check if arr.length > 0 && arr[0] instanceof Integer -> function value
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ARRAYLENGTH);
		int ifEmptyPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ICONST_0);
		code.add(Opcode.AALOAD);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, integerClass.index());
		int ifNotFuncPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		// It's a function value
		emitLdc(code, funcStr.index());
		code.add(Opcode.ARETURN);
		// Not a function: an instance (arr[0] is its String[] layout), else a cons list.
		// The empty-array escape jumps PAST the instance test, which probes arr[0].
		patchBranch(code, ifNotFuncPos, code.size());
		int ifNotInstPos = emitInstanceBranch(code, instPrint, false);
		if (ifNotInstPos >= 0) {
			patchBranch(code, ifNotInstPos, code.size());
		}
		patchBranch(code, ifEmptyPos, code.size());
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, consToStringMethod.index());
		code.add(Opcode.ARETURN);

		// "#<HASH-TABLE>" for a hash table, "#<java class>" for a wrapped host object
		// (java: interop), then val.toString()
		patchBranch(code, ifNotArrayPos, code.size());
		emitHashTableBranch(code, hashPrint);
		emitDefaultTail(code, objectToString, javaPrint, objcPrint, ffiPrint);

		return code;
	}

	/**
	 * Builds {@code _strEsc(String s) -> String}: the {@code *print-escape*} = {@code t}
	 * rendering of one quote-framed string value.
	 *
	 * <p>
	 * The JVM compile path stores a string as its content framed in {@code "} characters
	 * and a symbol as its bare name, so the leading {@code "} is the discriminator: a
	 * value that does not start with one is a symbol and is returned verbatim. For a real
	 * string every {@code "} and {@code \} of the CONTENT is preceded by a {@code \}
	 * (CLHS 22.1.3.4 -- the two syntax types the reader would otherwise choke on; a
	 * newline stays literal), so {@code (read-from-string (prin1-to-string s))} is
	 * {@code s} again. The escape set is the one {@code LispString.escape} applies on the
	 * interpreter.
	 *
	 * <p>
	 * The scan that decides whether anything needs escaping returns the argument
	 * unchanged in the common case; that fast path matters because {@code _lispToString}
	 * is also the JVM hash-table runtime's key function, not just the printer.
	 */
	static List<Integer> buildStrEscBody(ConstantPool cp, MethodrefConstant stringLength,
			MethodrefConstant stringCharAt, MethodrefConstant stringIndexOf, MethodrefConstant stringIndexOfFrom,
			MethodrefConstant stringSubstring, MethodrefConstant stringReplace, MethodrefConstant stringConcat) {
		JvmAsm a = new JvmAsm();
		int slotS = 0, slotN = 1;
		int framed = a.label();
		int returnAsIs = a.label();
		int escape = a.label();
		// int n = s.length(); if (n < 2) return s;
		a.aload(slotS);
		a.invokevirtual(stringLength);
		a.istore(slotN);
		a.iload(slotN);
		a.iconst(2);
		a.branch(Opcode.IF_ICMPGE, framed);
		a.branch(Opcode.GOTO, returnAsIs);
		// if (s.charAt(0) != '"') return s; -- a symbol name, printed verbatim
		a.bind(framed);
		a.aload(slotS);
		a.iconst(0);
		a.invokevirtual(stringCharAt);
		a.iconst('"');
		a.branch(Opcode.IF_ICMPNE, returnAsIs);
		// Nothing to escape when the content holds no '\' and the only '"' at or after
		// index 1 is the closing frame: if (s.indexOf('\\') >= 0) goto escape;
		a.aload(slotS);
		a.iconst('\\');
		a.invokevirtual(stringIndexOf);
		a.branch(Opcode.IFGE, escape);
		// if (s.indexOf('"', 1) != n - 1) goto escape;
		a.aload(slotS);
		a.iconst('"');
		a.iconst(1);
		a.invokevirtual(stringIndexOfFrom);
		a.iload(slotN);
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.branch(Opcode.IF_ICMPNE, escape);
		a.bind(returnAsIs);
		a.aload(slotS);
		a.areturn();
		// return "\"" + s.substring(1, n - 1).replace("\\", "\\\\").replace("\"", "\\\"")
		// + "\"" -- the backslash first, or the backslashes this very step introduces
		// would be escaped again.
		a.bind(escape);
		a.ldcString(cp.addString("\""));
		a.aload(slotS);
		a.iconst(1);
		a.iload(slotN);
		a.iconst(1);
		a.op(Opcode.ISUB);
		a.invokevirtual(stringSubstring);
		a.ldcString(cp.addString("\\"));
		a.ldcString(cp.addString("\\\\"));
		a.invokevirtual(stringReplace);
		a.ldcString(cp.addString("\""));
		a.ldcString(cp.addString("\\\""));
		a.invokevirtual(stringReplace);
		a.invokevirtual(stringConcat);
		a.ldcString(cp.addString("\""));
		a.invokevirtual(stringConcat);
		a.areturn();
		return a.finish();
	}

	/**
	 * Builds {@code _charPrin1(int codePoint) -> String}: the readable {@code #\name}
	 * form of a character (a standard name for the common non-graphic characters,
	 * otherwise the bare glyph). Used by {@code _lispToString} (prin1) to print a
	 * CHARACTER ({@code int[]}). Takes an {@code int} code point (not a {@code char}) so
	 * a supplementary code point survives — the glyph fallback calls
	 * {@link Character#toString(int)} which handles surrogate expansion.
	 */
	static List<Integer> buildCharPrin1Body(ConstantPool cp, MethodrefConstant stringConcat,
			MethodrefConstant characterToString) {
		JvmAsm a = new JvmAsm();
		emitCharNameCase(a, cp, ' ', "#\\Space");
		emitCharNameCase(a, cp, '\n', "#\\Newline");
		emitCharNameCase(a, cp, '\t', "#\\Tab");
		emitCharNameCase(a, cp, '\r', "#\\Return");
		emitCharNameCase(a, cp, '\f', "#\\Page");
		emitCharNameCase(a, cp, '\b', "#\\Backspace");
		emitCharNameCase(a, cp, 0, "#\\Nul");
		emitCharNameCase(a, cp, 127, "#\\Rubout");
		// default: "#\".concat(Character.toString(codePoint))
		a.ldcString(cp.addString("#\\"));
		a.iload(0);
		a.invokestatic(characterToString);
		a.invokevirtual(stringConcat);
		a.areturn();
		return a.finish();
	}

	private static void emitCharNameCase(JvmAsm a, ConstantPool cp, int ch, String result) {
		int next = a.label();
		a.iload(0);
		a.iconst(ch);
		a.branch(Opcode.IF_ICMPNE, next);
		a.ldcString(cp.addString(result));
		a.areturn();
		a.bind(next);
	}

	// Emits the ratio branch of _lispToString/_lispToDisplayString: if the value in
	// slot 0 is a BigInteger[], returns numerator + "/" + denominator. Returns the
	// branch position to patch to the next type check.
	private static int emitRatioToString(List<Integer> code, ClassConstant ratioArrayClass,
			MethodrefConstant objectToString, MethodrefConstant stringConcat, ConstantPool.StringConstant slashStr) {
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, ratioArrayClass.index());
		int ifNotRatioPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, ratioArrayClass.index());
		code.add(Opcode.ASTORE_1);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ICONST_0);
		code.add(Opcode.AALOAD);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, objectToString.index());
		emitLdc(code, slashStr.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, stringConcat.index());
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ICONST_1);
		code.add(Opcode.AALOAD);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, objectToString.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, stringConcat.index());
		code.add(Opcode.ARETURN);
		return ifNotRatioPos;
	}

	static List<Integer> buildConsToStringBody(ClassConstant objectArrayClass, ClassConstant stringBuilderClass,
			MethodrefConstant sbInitStr, MethodrefConstant sbAppendStr, MethodrefConstant sbToString,
			MethodrefConstant lispToStringMethod, ConstantPool.StringConstant openParenStr,
			ConstantPool.StringConstant closeParenStr, ConstantPool.StringConstant spaceStr,
			ConstantPool.StringConstant dotStr, ClassConstant ratioArrayClass, RenderGuardRefs guard) {
		List<Integer> code = new ArrayList<>();
		// The cycle guard (the shared RenderGuardRefs discipline, kept in step by
		// JvmLispCompilerTest.compileAndRunPrintOfACyclicConsIsFinite): a chain whose
		// HEAD is already on the current rendering path -- a car reaching back to a
		// list still being rendered -- or the frame past the 256-frame depth cap
		// returns "#", the *print-level* cutoff marker. Locals here: 0 = arg, 1 = sb,
		// 2 = current (Floyd's slow first), 3 = first flag (guard scratch first),
		// 4 = cell, 5 = the chain's cycle-start cell or null, 6 = its seen flag,
		// 7 = Floyd's fast cursor.
		emitRenderGuardEnter(code, guard);
		// The cdr chain is walked ITERATIVELY below, so the path guard alone cannot see
		// a chain that cycles into itself: Floyd's cycle detection finds the cell where
		// the cycle begins (into local 5; null for a terminating chain) before anything
		// is rendered, and the loop prints the SECOND arrival at that cell as the
		// improper tail " . #" -- every element exactly once, then the marker. The
		// chain-cell test mirrors the loop's own (an Object[] that is not a ratio), so
		// the two walks agree on where the chain ends.
		code.add(Opcode.ACONST_NULL);
		code.add(Opcode.ASTORE);
		code.add(5);
		code.add(Opcode.ICONST_0);
		code.add(Opcode.ISTORE);
		code.add(6);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.ASTORE_2);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.ASTORE);
		code.add(7);
		int floydLoop = code.size();
		List<Integer> floydDonePatches = new ArrayList<>();
		emitConsCellCheck(code, objectArrayClass, ratioArrayClass, 7, floydDonePatches);
		emitCdrStep(code, objectArrayClass, 7);
		emitConsCellCheck(code, objectArrayClass, ratioArrayClass, 7, floydDonePatches);
		emitCdrStep(code, objectArrayClass, 7);
		emitCdrStep(code, objectArrayClass, 2);
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.ALOAD);
		code.add(7);
		int floydMissPos = code.size();
		code.add(Opcode.IF_ACMPNE);
		emitU2(code, 0);
		patchBranch(code, floydMissPos, floydLoop);
		// A cycle: walk head and the meeting point in step to the cycle-start cell.
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.ASTORE_2);
		int startLoop = code.size();
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.ALOAD);
		code.add(7);
		int startFoundPos = code.size();
		code.add(Opcode.IF_ACMPEQ);
		emitU2(code, 0);
		emitCdrStep(code, objectArrayClass, 2);
		emitCdrStep(code, objectArrayClass, 7);
		int startAgainPos = code.size();
		code.add(Opcode.GOTO);
		emitU2(code, 0);
		patchBranch(code, startAgainPos, startLoop);
		patchBranch(code, startFoundPos, code.size());
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.ASTORE);
		code.add(5);
		for (int patchPos : floydDonePatches) {
			patchBranch(code, patchPos, code.size());
		}
		code.add(Opcode.NEW);
		emitU2(code, stringBuilderClass.index());
		code.add(Opcode.DUP);
		emitLdc(code, openParenStr.index());
		code.add(Opcode.INVOKESPECIAL);
		emitU2(code, sbInitStr.index());
		code.add(Opcode.ASTORE_1);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.ASTORE_2);
		code.add(Opcode.ICONST_1);
		code.add(Opcode.ISTORE_3);
		int loopStart = code.size();
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, objectArrayClass.index());
		int ifNotArrayPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		// A ratio (BigInteger[]) is also an Object[]; treat it as an improper tail
		// (e.g. (1 . 1/2)) rather than walking into it as a cons cell.
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, ratioArrayClass.index());
		int ifRatioTailPos = code.size();
		code.add(Opcode.IFNE);
		emitU2(code, 0);
		// if (current == stop) { if (seen) { sb.append(" . ").append("#"); close; }
		// seen = 1; } -- the chain's cycle-start cell renders once, and its second
		// arrival becomes the improper tail marker.
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.ALOAD);
		code.add(5);
		int notStopPos = code.size();
		code.add(Opcode.IF_ACMPNE);
		emitU2(code, 0);
		code.add(Opcode.ILOAD);
		code.add(6);
		int stopUnseenPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_1);
		emitLdc(code, dotStr.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, sbAppendStr.index());
		code.add(Opcode.POP);
		code.add(Opcode.ALOAD_1);
		emitLdc(code, guard.depthMarkerStr().index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, sbAppendStr.index());
		code.add(Opcode.POP);
		int stopClosePos = code.size();
		code.add(Opcode.GOTO);
		emitU2(code, 0);
		patchBranch(code, stopUnseenPos, code.size());
		code.add(Opcode.ICONST_1);
		code.add(Opcode.ISTORE);
		code.add(6);
		patchBranch(code, notStopPos, code.size());
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.CHECKCAST);
		emitU2(code, objectArrayClass.index());
		code.add(Opcode.ASTORE);
		code.add(4);
		code.add(Opcode.ILOAD_3);
		int ifFirstPos = code.size();
		code.add(Opcode.IFNE);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_1);
		emitLdc(code, spaceStr.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, sbAppendStr.index());
		code.add(Opcode.POP);
		patchBranch(code, ifFirstPos, code.size());
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ALOAD);
		code.add(4);
		code.add(Opcode.ICONST_0);
		code.add(Opcode.AALOAD);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, lispToStringMethod.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, sbAppendStr.index());
		code.add(Opcode.POP);
		code.add(Opcode.ALOAD);
		code.add(4);
		code.add(Opcode.ICONST_1);
		code.add(Opcode.AALOAD);
		code.add(Opcode.ASTORE_2);
		code.add(Opcode.ICONST_0);
		code.add(Opcode.ISTORE_3);
		int gotoPos = code.size();
		code.add(Opcode.GOTO);
		emitU2(code, 0);
		patchBranch(code, gotoPos, loopStart);
		patchBranch(code, ifNotArrayPos, code.size());
		patchBranch(code, ifRatioTailPos, code.size());
		code.add(Opcode.ALOAD_2);
		int ifNullPos = code.size();
		code.add(Opcode.IFNULL);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_1);
		emitLdc(code, dotStr.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, sbAppendStr.index());
		code.add(Opcode.POP);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, lispToStringMethod.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, sbAppendStr.index());
		code.add(Opcode.POP);
		patchBranch(code, ifNullPos, code.size());
		patchBranch(code, stopClosePos, code.size());
		code.add(Opcode.ALOAD_1);
		emitLdc(code, closeParenStr.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, sbAppendStr.index());
		code.add(Opcode.POP);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, sbToString.index());
		emitRenderGuardExitAndReturn(code, guard);
		return code;
	}

	// Emits "if (local is not a cons cell) goto <patched later>": an Object[] that is
	// not a ratio (BigInteger[]) -- the same test the render loop's chain walk applies,
	// so Floyd's walk and the render walk agree on where a chain ends.
	private static void emitConsCellCheck(List<Integer> code, ClassConstant objectArrayClass,
			ClassConstant ratioArrayClass, int local, List<Integer> notConsPatches) {
		code.add(Opcode.ALOAD);
		code.add(local);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, objectArrayClass.index());
		notConsPatches.add(code.size());
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD);
		code.add(local);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, ratioArrayClass.index());
		notConsPatches.add(code.size());
		code.add(Opcode.IFNE);
		emitU2(code, 0);
	}

	// Emits "local = ((Object[]) local)[1]" -- one cdr step of a chain walk. The cast
	// cannot fail: every cell stepped through has passed emitConsCellCheck (Floyd's
	// slow cursor and the cycle-start walk only revisit cells the fast cursor checked).
	private static void emitCdrStep(List<Integer> code, ClassConstant objectArrayClass, int local) {
		code.add(Opcode.ALOAD);
		code.add(local);
		code.add(Opcode.CHECKCAST);
		emitU2(code, objectArrayClass.index());
		code.add(Opcode.ICONST_1);
		code.add(Opcode.AALOAD);
		code.add(Opcode.ASTORE);
		code.add(local);
	}

	/**
	 * Builds bytecode for _lispToDisplayString. Same as _lispToString but strips quotes
	 * from strings (charAt(0)=='"' -> substring(1, length-1)) and renders a symbol as its
	 * NAME alone -- no package qualifier, no keyword/gensym marker.
	 */
	static List<Integer> buildLispToDisplayStringBody(ClassConstant longClass, ClassConstant doubleClass,
			ClassConstant stringClass, ClassConstant objectArrayClass, ClassConstant integerClass,
			MethodrefConstant longToString, MethodrefConstant doubleToString, FloatPrint floatPrint,
			MethodrefConstant objectToString, MethodrefConstant consToDisplayStringMethod,
			ConstantPool.StringConstant nilStr, ConstantPool.StringConstant funcStr, MethodrefConstant stringCharAt,
			MethodrefConstant stringLength, MethodrefConstant stringSubstring, MethodrefConstant stringLastIndexOf,
			ClassConstant ratioArrayClass, MethodrefConstant stringConcat, ConstantPool.StringConstant slashStr,
			ClassConstant charBoxClass, MethodrefConstant characterToString,
			@org.jspecify.annotations.Nullable ClassConstant arrayListClass,
			@org.jspecify.annotations.Nullable MethodrefConstant arrayToDisplayStringMethod,
			@org.jspecify.annotations.Nullable MethodrefConstant strvMethod,
			@org.jspecify.annotations.Nullable JavaPrint javaPrint,
			@org.jspecify.annotations.Nullable ObjcPrint objcPrint,
			@org.jspecify.annotations.Nullable ObjcPrint ffiPrint,
			@org.jspecify.annotations.Nullable FuturePrint futurePrint,
			@org.jspecify.annotations.Nullable PackedPrint packedPrint,
			@org.jspecify.annotations.Nullable PackedIntPrint packedIntPrint,
			@org.jspecify.annotations.Nullable InstPrint instPrint,
			@org.jspecify.annotations.Nullable HashPrint hashPrint) {
		List<Integer> code = new ArrayList<>();
		// if (val == null) return "nil";
		code.add(Opcode.ALOAD_0);
		int ifNonnullPos = code.size();
		code.add(Opcode.IFNONNULL);
		emitU2(code, 0);
		emitLdc(code, nilStr.index());
		code.add(Opcode.ARETURN);

		// if (val instanceof Long) return ((Long)val).toString();
		patchBranch(code, ifNonnullPos, code.size());
		// if (val instanceof CompletableFuture) return "#<FUTURE>"; (futures only)
		emitFutureBranch(code, futurePrint);
		// if (val instanceof double[]) return
		// _arrayToDisplayString(_fvToGeneral(val)).replaceFirst(...#d...); and
		// if (val instanceof long[]) return _arrayToDisplayString(_ivToGeneral(val)); and
		// if (val instanceof ArrayList) return _arrayToDisplayString(val); (arrays only;
		// a mutable character vector instead renders via _strv with the surrounding
		// quotes stripped, like the String branch)
		emitArrayBranch(code, arrayListClass, arrayToDisplayStringMethod, packedPrint, packedIntPrint, strvMethod,
				stringClass, stringLength, stringSubstring, null);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, longClass.index());
		int ifNotLongPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, longClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, longToString.index());
		code.add(Opcode.ARETURN);

		// if (val instanceof Double) return ((Double)val).toString().replace("E", "e");
		// (the FloatText lowercase-marker spelling, identical on every backend)
		patchBranch(code, ifNotLongPos, code.size());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, doubleClass.index());
		int ifNotDoublePos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, doubleClass.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, doubleToString.index());
		emitLdc(code, floatPrint.upperE().index());
		emitLdc(code, floatPrint.lowerE().index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, floatPrint.stringReplace().index());
		code.add(Opcode.ARETURN);

		// if (val instanceof Float) return ((Float)val).toString().replace("E", "e");
		// A Float box exists only transiently while a packed single-float array prints
		// its elements at their f32 width (_fvToGeneralPrint); no Lisp value holds one.
		patchBranch(code, ifNotDoublePos, code.size());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, floatPrint.floatClass().index());
		int ifNotFloatPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, floatPrint.floatClass().index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, floatPrint.floatToString().index());
		emitLdc(code, floatPrint.upperE().index());
		emitLdc(code, floatPrint.lowerE().index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, floatPrint.stringReplace().index());
		code.add(Opcode.ARETURN);

		// if (val instanceof String) -> strip quotes if leading '"'
		patchBranch(code, ifNotFloatPos, code.size());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, stringClass.index());
		int ifNotStringPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, stringClass.index());
		code.add(Opcode.ASTORE_1); // store string in slot 1
		// check charAt(0) == '"'
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ICONST_0);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, stringCharAt.index());
		emitIntConstStatic(code, 34); // '"' = 34
		int ifNotQuotePos = code.size();
		code.add(Opcode.IF_ICMPNE);
		emitU2(code, 0);
		// It's a quoted string: return substring(1, length-1)
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ICONST_1);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, stringLength.index());
		code.add(Opcode.ICONST_1);
		code.add(Opcode.ISUB);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, stringSubstring.index());
		code.add(Opcode.ARETURN);
		// Not a quoted string: a symbol. Its display spelling is the symbol NAME with
		// no package qualifier and no marker (CLHS 22.1.3.3: with *print-escape* false
		// only the characters of the name are output) -- so QURI:URI princes as URI, a
		// keyword :KW as KW and a gensym #:G1 as G1. All three are "everything after the
		// last colon": return s.substring(s.lastIndexOf(':') + 1, s.length()). prin1
		// keeps the spelling verbatim (_lispToString).
		patchBranch(code, ifNotQuotePos, code.size());
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ALOAD_1);
		emitIntConstStatic(code, ':');
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, stringLastIndexOf.index());
		code.add(Opcode.ICONST_1);
		code.add(Opcode.IADD);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, stringLength.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, stringSubstring.index());
		code.add(Opcode.ARETURN);

		// if (val instanceof int[]) return Character.toString(((int[])val)[0]);
		//
		// A CHARACTER is a length-1 int[]{codePoint}. Character.toString(int) expands a
		// supplementary code point to its surrogate pair so a #\U+1F600 princes as its
		// glyph, not as a lone surrogate.
		patchBranch(code, ifNotStringPos, code.size());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, charBoxClass.index());
		int ifNotCharPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, charBoxClass.index());
		code.add(Opcode.ICONST_0);
		code.add(Opcode.IALOAD);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, characterToString.index());
		code.add(Opcode.ARETURN);

		// if (val instanceof BigInteger[]) -> "num/den" (must precede the Object[]
		// check: a ratio is also an Object[])
		patchBranch(code, ifNotCharPos, code.size());
		int ifNotRatioPos = emitRatioToString(code, ratioArrayClass, objectToString, stringConcat, slashStr);

		// if (val instanceof Object[])
		patchBranch(code, ifNotRatioPos, code.size());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, objectArrayClass.index());
		int ifNotArrayPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, objectArrayClass.index());
		code.add(Opcode.ASTORE_1);
		// Check if arr.length > 0 && arr[0] instanceof Integer -> function value
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ARRAYLENGTH);
		int ifEmptyPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ICONST_0);
		code.add(Opcode.AALOAD);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, integerClass.index());
		int ifNotFuncPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		emitLdc(code, funcStr.index());
		code.add(Opcode.ARETURN);
		patchBranch(code, ifNotFuncPos, code.size());
		int ifNotInstPos = emitInstanceBranch(code, instPrint, true);
		if (ifNotInstPos >= 0) {
			patchBranch(code, ifNotInstPos, code.size());
		}
		patchBranch(code, ifEmptyPos, code.size());
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, consToDisplayStringMethod.index());
		code.add(Opcode.ARETURN);

		// "#<HASH-TABLE>" for a hash table, "#<java class>" for a wrapped host object
		// (java: interop), then val.toString()
		patchBranch(code, ifNotArrayPos, code.size());
		emitHashTableBranch(code, hashPrint);
		emitDefaultTail(code, objectToString, javaPrint, objcPrint, ffiPrint);

		return code;
	}

	/**
	 * Builds bytecode for _consToDisplayString. Same as _consToString but calls
	 * _lispToDisplayString recursively.
	 */
	static List<Integer> buildConsToDisplayStringBody(ClassConstant objectArrayClass, ClassConstant stringBuilderClass,
			MethodrefConstant sbInitStr, MethodrefConstant sbAppendStr, MethodrefConstant sbToString,
			MethodrefConstant lispToDisplayStringMethod, ConstantPool.StringConstant openParenStr,
			ConstantPool.StringConstant closeParenStr, ConstantPool.StringConstant spaceStr,
			ConstantPool.StringConstant dotStr, ClassConstant ratioArrayClass, RenderGuardRefs guard) {
		return buildConsToStringBody(objectArrayClass, stringBuilderClass, sbInitStr, sbAppendStr, sbToString,
				lispToDisplayStringMethod, openParenStr, closeParenStr, spaceStr, dotStr, ratioArrayClass, guard);
	}

	/**
	 * Builds bytecode for _append(Object a, Object b). If a is null, returns b.
	 * Otherwise, creates new Object[]{a[0], _append(a[1], b)}.
	 */
	static List<Integer> buildAppendBody(ClassConstant objectArrayClass, ClassConstant objectClass,
			MethodrefConstant appendMethod) {
		List<Integer> code = new ArrayList<>();
		// if (a == null) return b;
		code.add(Opcode.ALOAD_0);
		int ifNonnullPos = code.size();
		code.add(Opcode.IFNONNULL);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ARETURN);
		// a is non-null: cast to Object[]
		patchBranch(code, ifNonnullPos, code.size());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, objectArrayClass.index());
		code.add(Opcode.ASTORE_2);
		// new Object[2]
		code.add(Opcode.ICONST_2);
		code.add(Opcode.ANEWARRAY);
		emitU2(code, objectClass.index());
		// arr[0] = a[0]
		code.add(Opcode.DUP);
		code.add(Opcode.ICONST_0);
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.ICONST_0);
		code.add(Opcode.AALOAD);
		code.add(Opcode.AASTORE);
		// arr[1] = _append(a[1], b)
		code.add(Opcode.DUP);
		code.add(Opcode.ICONST_1);
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.ICONST_1);
		code.add(Opcode.AALOAD);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, appendMethod.index());
		code.add(Opcode.AASTORE);
		code.add(Opcode.ARETURN);
		return code;
	}

	/**
	 * Builds bytecode for _readLine helper. Lazily initializes static _stdinReader field,
	 * reads a line, and wraps it with '"' prefix/suffix for the internal string format.
	 * Returns null for EOF.
	 */
	static List<Integer> buildReadLineBody(ClassConstant bufferedReaderClass, ClassConstant inputStreamReaderClass,
			MethodrefConstant brInit, MethodrefConstant brReadLine, MethodrefConstant isrInit,
			ConstantPool.FieldrefConstant systemIn, ConstantPool.FieldrefConstant stdinReaderField,
			ConstantPool.StringConstant quoteStr, MethodrefConstant stringConcat) {
		List<Integer> code = new ArrayList<>();
		// if (_stdinReader == null)
		code.add(Opcode.GETSTATIC);
		emitU2(code, stdinReaderField.index());
		int ifNonnullPos = code.size();
		code.add(Opcode.IFNONNULL);
		emitU2(code, 0);
		// _stdinReader = new BufferedReader(new InputStreamReader(System.in))
		code.add(Opcode.NEW);
		emitU2(code, bufferedReaderClass.index());
		code.add(Opcode.DUP);
		code.add(Opcode.NEW);
		emitU2(code, inputStreamReaderClass.index());
		code.add(Opcode.DUP);
		code.add(Opcode.GETSTATIC);
		emitU2(code, systemIn.index());
		code.add(Opcode.INVOKESPECIAL);
		emitU2(code, isrInit.index());
		code.add(Opcode.INVOKESPECIAL);
		emitU2(code, brInit.index());
		code.add(Opcode.PUTSTATIC);
		emitU2(code, stdinReaderField.index());
		// end if
		patchBranch(code, ifNonnullPos, code.size());
		// String line = _stdinReader.readLine();
		code.add(Opcode.GETSTATIC);
		emitU2(code, stdinReaderField.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, brReadLine.index());
		code.add(Opcode.ASTORE_0);
		// if (line == null) return null;
		code.add(Opcode.ALOAD_0);
		int ifNotNullPos = code.size();
		code.add(Opcode.IFNONNULL);
		emitU2(code, 0);
		code.add(Opcode.ACONST_NULL);
		code.add(Opcode.ARETURN);
		// return "\"" + line + "\""
		patchBranch(code, ifNotNullPos, code.size());
		emitLdc(code, quoteStr.index());
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, stringConcat.index());
		emitLdc(code, quoteStr.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, stringConcat.index());
		code.add(Opcode.ARETURN);
		return code;
	}

	/**
	 * Constant-pool references for printing a wrapped {@code java:} host object as
	 * {@code #<java class.Name>} (interpreter parity), threaded into the two
	 * lisp-to-string builders only when the program uses {@code java:} interop.
	 */
	record JavaPrint(ClassConstant bigIntegerClass, MethodrefConstant objectGetClass, MethodrefConstant classGetName,
			MethodrefConstant stringConcat, ConstantPool.StringConstant prefix, ConstantPool.StringConstant suffix) {
	}

	/**
	 * Constant-pool references for printing a wrapped {@code objc:} object as
	 * {@code #<objc Class>} (interpreter parity) through the embedded bridge's print
	 * hook, threaded into the two lisp-to-string builders only when the program uses
	 * {@code objc:}. {@code initedField} is the {@code _objcInited} guard: the hook is
	 * called only once {@code _objcInit} has defined the bridge class, so a print before
	 * the first {@code objc:} call never resolves a class that does not exist yet.
	 */
	record ObjcPrint(FieldrefConstant initedField, MethodrefConstant print) {
	}

	/**
	 * Constant-pool references for printing a hash table as the unreadable
	 * {@code #<HASH-TABLE :TEST EQUAL :COUNT n>} tag the interpreter's
	 * {@code LispHashTable.print()} answers. Threaded into the two lisp-to-string
	 * builders only when the program uses hash tables.
	 *
	 * <p>
	 * {@code mapClass} is {@link JvmHashRuntimeBuilder#MAP_CLASS}, the runtime class a
	 * COMPILED table has and a host {@code java:} map does not: without a branch of its
	 * own a table used to fall through to {@code toString()} and print Java's own map
	 * syntax -- container braces, the raw {@code Object[]} entry pair, and an IDENTITY
	 * HASH, which made the same program print different text on two runs
	 * ({@code .kb/emitted-output-determinism.md}). The count comes from {@code mapSize},
	 * the {@code _hashSize} helper {@code _hashCount} reads too, so the printed number
	 * and {@code hash-table-count} cannot disagree.
	 */
	record HashPrint(ClassConstant mapClass, ConstantPool.StringConstant tag, MethodrefConstant mapSize,
			MethodrefConstant intToString, MethodrefConstant stringConcat, ConstantPool.StringConstant suffix,
			ConstantPool.@org.jspecify.annotations.Nullable StringConstant equalpTag,
			@org.jspecify.annotations.Nullable MethodrefConstant equalpTest) {
	}

	/**
	 * Constant-pool references for printing the opaque asynchronous values: a
	 * {@code CompletableFuture} (and a pending stream-read token, when the async
	 * machinery is present) prints as {@code futureStr}; a stream as {@code streamStr}.
	 * The marker/label/array entries are null in programs that never touch streams,
	 * keeping those branches out.
	 */
	record FuturePrint(ClassConstant futureClass, ConstantPool.StringConstant futureStr,
			@org.jspecify.annotations.Nullable ClassConstant objectArrayClass,
			ConstantPool.@org.jspecify.annotations.Nullable StringConstant streamMarker,
			ConstantPool.@org.jspecify.annotations.Nullable StringConstant readMarker,
			ConstantPool.@org.jspecify.annotations.Nullable StringConstant streamStr) {
	}

	/**
	 * Constant-pool references for printing a packed float array through the
	 * {@code #d(...)} / {@code #f(...)} reader syntax, so its printed form round-trips to
	 * a packed array. A double-float array is a {@code double[]} at runtime, a
	 * single-float array a {@code float[]}. The element data is rendered by boxing to a
	 * general array ({@code fvToGeneralMethod}, which handles both widths -- single
	 * widens f32-&gt;f64) and reusing the ordinary array renderer, then the leading
	 * {@code #}/{@code #nA} prefix is rewritten with
	 * {@code String.replaceFirst(prefixRegex, ...)} (regex {@code ^#\d*A?\(}) to
	 * {@code #d(} for a {@code double[]} or {@code #f(} for a {@code float[]}. Threaded
	 * into the two lisp-to-string builders only when the program uses packed float
	 * arrays.
	 */
	/**
	 * Constants for the float text of _lispToString/_lispToDisplayString: the lowercase
	 * exponent-marker rewrite every float spelling gets (the FloatText contract), and the
	 * transient Float box a packed single-float array element prints through.
	 */
	record FloatPrint(ClassConstant floatClass, MethodrefConstant floatToString, MethodrefConstant stringReplace,
			ConstantPool.StringConstant upperE, ConstantPool.StringConstant lowerE) {
	}

	record PackedPrint(ClassConstant doubleArrayClass, ClassConstant floatArrayClass,
			MethodrefConstant fvToGeneralMethod, MethodrefConstant stringReplaceFirst,
			ConstantPool.StringConstant prefixRegex, ConstantPool.StringConstant prefixRepl,
			ConstantPool.StringConstant prefixReplSingle) {
	}

	/**
	 * Constant-pool references for printing a packed integer vector (a {@code long[]}
	 * with a width header at runtime) as a plain {@code #(...)} vector -- CL prints
	 * specialized vectors this way, so unlike the {@code #d}/{@code #f} float syntax
	 * there is no prefix rewrite: the value is boxed to a general array
	 * ({@code ivToGeneralMethod}) and rendered by the ordinary array renderer. Threaded
	 * into the two lisp-to-string builders only when the program uses packed integer
	 * vectors.
	 */
	record PackedIntPrint(ClassConstant longArrayClass, MethodrefConstant ivToGeneralMethod) {
	}

	/**
	 * Constant-pool references for printing an instance -- {@code #S(NAME :SLOT v ...)}
	 * for a struct layout, {@code #&lt;NAME :SLOT v ...&gt;} for a class one. Threaded
	 * into the two lisp-to-string builders only when the program can build an instance,
	 * so an instance-free program keeps the branch out entirely.
	 *
	 * @param stringArrayClass the {@code [Ljava/lang/String;} discriminator: an instance
	 * is an {@code Object[]} with its layout there
	 * @param instToString the {@code _instToString} helper (prin1 slot values)
	 * @param instToDisplayString the {@code _instToDisplayString} helper (princ slot
	 * values)
	 */
	record InstPrint(ClassConstant stringArrayClass, MethodrefConstant instToString,
			MethodrefConstant instToDisplayString) {
	}

	/**
	 * Constant-pool references for the renderers' shared cycle guard -- the emitted twin
	 * of {@code RenderCycleGuard}: a value already on the current rendering path, or the
	 * frame past {@code RenderCycleGuard.MAX_RENDER_DEPTH}, renders as {@code "#"} (CL's
	 * {@code *print-level*} cutoff marker) instead of recursing without end. The two
	 * static fields are shared by both escape modes of the instance, cons and array
	 * renderers: whichever arm a nested render runs in, the path is one path.
	 *
	 * @param pathField the {@code _renderPath} static ({@code Object[]}, lazily
	 * allocated)
	 * @param depthField the {@code _renderDepth} static ({@code int})
	 * @param objectClass the {@code java/lang/Object} class constant (for the lazy
	 * {@code anewarray})
	 * @param depthMarkerStr the {@code "#"} marker
	 */
	record RenderGuardRefs(FieldrefConstant pathField, FieldrefConstant depthField, ClassConstant objectClass,
			ConstantPool.StringConstant depthMarkerStr) {
	}

	/**
	 * Builds {@code _instToString}/{@code _instToDisplayString}: renders an instance
	 * {@code Object[]{String[] layout, v1, ..., vn}} as {@code #S(NAME :SLOT v ...)} or
	 * {@code #&lt;NAME :SLOT v ...&gt;}, where the layout is
	 * <code>{tag, printName, "S"|"C", slot0, ...}</code>.
	 *
	 * <p>
	 * The {@code #S}/{@code #&lt;} frame and the colon on each slot key are literal
	 * syntax, so they are emitted in BOTH escape modes (CLHS 22.1.3.12); only the slot
	 * VALUES go through {@code elementFormatter}, which is the caller's choice of
	 * {@code _lispToString} (prin1) or {@code _lispToDisplayString} (princ). One body
	 * builder, two calls -- the {@code buildConsToDisplayStringBody} idiom -- so the two
	 * renderings cannot drift.
	 * @param objectArrayClass the {@code [Ljava/lang/Object;} class constant
	 * @param stringArrayClass the {@code [Ljava/lang/String;} class constant
	 * @param stringBuilderClass the {@code StringBuilder} class constant
	 * @param sbInitStr the {@code StringBuilder(String)} constructor
	 * @param sbAppendStr the {@code StringBuilder.append(String)} method
	 * @param sbToString the {@code StringBuilder.toString()} method
	 * @param objectEquals the {@code Object.equals(Object)} method
	 * @param elementFormatter the per-slot-value renderer
	 * @param structKindStr the {@code "S"} kind marker
	 * @param openStructStr the {@code "#S("} opener
	 * @param openClassStr the {@code "#&lt;"} opener
	 * @param closeStructStr the {@code ")"} closer
	 * @param closeClassStr the {@code "&gt;"} closer
	 * @param keySepStr the {@code " :"} separator preceding a slot name
	 * @param spaceStr the {@code " "} separator between a slot name and its value
	 * @param pathnameKindStr the {@code "P"} kind marker of the pathname layout
	 * @param pathnamePrefixStr the {@code "#P"} prefix a pathname prints under prin1, or
	 * null for the princ variant (CLHS 22.1.3.11: princ writes the bare namestring)
	 * @return the method body
	 */
	static List<Integer> buildInstToStringBody(ClassConstant objectArrayClass, ClassConstant stringArrayClass,
			ClassConstant stringBuilderClass, MethodrefConstant sbInitStr, MethodrefConstant sbAppendStr,
			MethodrefConstant sbToString, MethodrefConstant objectEquals, MethodrefConstant elementFormatter,
			ConstantPool.StringConstant structKindStr, ConstantPool.StringConstant openStructStr,
			ConstantPool.StringConstant openClassStr, ConstantPool.StringConstant closeStructStr,
			ConstantPool.StringConstant closeClassStr, ConstantPool.StringConstant keySepStr,
			ConstantPool.StringConstant spaceStr, ConstantPool.StringConstant pathnameKindStr,
			ConstantPool.@org.jspecify.annotations.Nullable StringConstant pathnamePrefixStr, RenderGuardRefs guard) {
		List<Integer> code = new ArrayList<>();
		// layout = (String[]) arr[0]
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.ICONST_0);
		code.add(Opcode.AALOAD);
		code.add(Opcode.CHECKCAST);
		emitU2(code, stringArrayClass.index());
		code.add(Opcode.ASTORE_2);
		// A PATHNAME layout short-circuits the slot-name loop entirely (CLHS
		// 22.1.3.11): prin1 is "#P" + the escaped namestring, princ the bare
		// namestring -- the element formatter already renders a string both ways.
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.ICONST_2);
		code.add(Opcode.AALOAD);
		emitLdc(code, pathnameKindStr.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, objectEquals.index());
		int ifNotPathnamePos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		if (pathnamePrefixStr == null) {
			// princ: return format(arr[1])
			code.add(Opcode.ALOAD_0);
			code.add(Opcode.ICONST_1);
			code.add(Opcode.AALOAD);
			code.add(Opcode.INVOKESTATIC);
			emitU2(code, elementFormatter.index());
			code.add(Opcode.ARETURN);
		}
		else {
			// prin1: return new StringBuilder("#P").append(format(arr[1])).toString()
			code.add(Opcode.NEW);
			emitU2(code, stringBuilderClass.index());
			code.add(Opcode.DUP);
			emitLdc(code, pathnamePrefixStr.index());
			code.add(Opcode.INVOKESPECIAL);
			emitU2(code, sbInitStr.index());
			code.add(Opcode.ALOAD_0);
			code.add(Opcode.ICONST_1);
			code.add(Opcode.AALOAD);
			code.add(Opcode.INVOKESTATIC);
			emitU2(code, elementFormatter.index());
			code.add(Opcode.INVOKEVIRTUAL);
			emitU2(code, sbAppendStr.index());
			code.add(Opcode.INVOKEVIRTUAL);
			emitU2(code, sbToString.index());
			code.add(Opcode.ARETURN);
		}
		patchBranch(code, ifNotPathnamePos, code.size());
		// The cycle guard (the shared RenderGuardRefs discipline, kept in step by
		// JvmLispCompilerTest.compileAndRunPrintOfACyclicInstanceGraphIsFinite): an
		// instance already on the current rendering path -- a scene graph's
		// parent/children pair is the everyday case -- or the frame past the 256-frame
		// depth cap returns "#", the *print-level* cutoff marker, instead of
		// overflowing the stack. Placed AFTER the pathname arm, whose one slot is a
		// string and cannot recurse, so only the one return below needs the pop.
		emitRenderGuardEnter(code, guard);
		// The opener is chosen into a local BEFORE the StringBuilder is allocated: a
		// branch merge with an uninitialized NEW on the operand stack is exactly what
		// the offline StackMapTable computation should never have to model.
		emitKindChoice(code, objectEquals, structKindStr, openStructStr, openClassStr);
		code.add(Opcode.NEW);
		emitU2(code, stringBuilderClass.index());
		code.add(Opcode.DUP);
		code.add(Opcode.ALOAD);
		code.add(4);
		code.add(Opcode.INVOKESPECIAL);
		emitU2(code, sbInitStr.index());
		code.add(Opcode.ASTORE_1);
		// sb.append(layout[1]) -- the printed type name
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.ICONST_1);
		code.add(Opcode.AALOAD);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, sbAppendStr.index());
		code.add(Opcode.POP);
		// for (i = 3; i < layout.length; i++) sb.append(" :").append(layout[i])
		// .append(' ').append(format(arr[i - 2]))
		code.add(Opcode.ICONST_3);
		code.add(Opcode.ISTORE_3);
		int loopStart = code.size();
		code.add(Opcode.ILOAD_3);
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.ARRAYLENGTH);
		int ifDonePos = code.size();
		code.add(Opcode.IF_ICMPGE);
		emitU2(code, 0);
		emitAppendConst(code, sbAppendStr, keySepStr);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.ILOAD_3);
		code.add(Opcode.AALOAD);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, sbAppendStr.index());
		code.add(Opcode.POP);
		emitAppendConst(code, sbAppendStr, spaceStr);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.ILOAD_3);
		code.add(Opcode.ICONST_2);
		code.add(Opcode.ISUB);
		code.add(Opcode.AALOAD);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, elementFormatter.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, sbAppendStr.index());
		code.add(Opcode.POP);
		code.add(Opcode.IINC);
		code.add(3);
		code.add(1);
		int gotoLoopPos = code.size();
		code.add(Opcode.GOTO);
		emitU2(code, 0);
		patchBranch(code, gotoLoopPos, loopStart);
		patchBranch(code, ifDonePos, code.size());
		emitKindChoice(code, objectEquals, structKindStr, closeStructStr, closeClassStr);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ALOAD);
		code.add(4);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, sbAppendStr.index());
		code.add(Opcode.POP);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, sbToString.index());
		emitRenderGuardExitAndReturn(code, guard);
		return code;
	}

	/**
	 * Emits the shared cycle guard's ENTER: lazily allocates the {@code _renderPath}
	 * array, scans it for the value in local 0 (identity, {@code if_acmpne} -- the same
	 * value REACHABLE twice on a finite path still renders twice), and pushes the value
	 * over ONE read of {@code _renderDepth} -- a served program's request threads may
	 * print concurrently over these shared statics, so every array index is
	 * bounds-checked against the SAME read that stores it: a race can at worst misplace a
	 * {@code "#"} marker, never index out of the path array. A value already on the path,
	 * or the frame past the depth cap, RETURNS {@code "#"} instead of entering. Local 3
	 * is scratch (an int); callers run this before local 3's own use begins.
	 */
	private static void emitRenderGuardEnter(List<Integer> code, RenderGuardRefs guard) {
		// if (_renderPath == null) _renderPath = new Object[256];
		code.add(Opcode.GETSTATIC);
		emitU2(code, guard.pathField().index());
		int ifPathInitedPos = code.size();
		code.add(Opcode.IFNONNULL);
		emitU2(code, 0);
		emitIntConstStatic(code, RENDER_DEPTH_CAP);
		code.add(Opcode.ANEWARRAY);
		emitU2(code, guard.objectClass().index());
		code.add(Opcode.PUTSTATIC);
		emitU2(code, guard.pathField().index());
		patchBranch(code, ifPathInitedPos, code.size());
		// for (i = 0; i < _renderDepth; i++) if (_renderPath[i] == arg) return "#";
		code.add(Opcode.ICONST_0);
		code.add(Opcode.ISTORE_3);
		int scanStart = code.size();
		code.add(Opcode.ILOAD_3);
		code.add(Opcode.GETSTATIC);
		emitU2(code, guard.depthField().index());
		int scanDonePos = code.size();
		code.add(Opcode.IF_ICMPGE);
		emitU2(code, 0);
		code.add(Opcode.GETSTATIC);
		emitU2(code, guard.pathField().index());
		code.add(Opcode.ILOAD_3);
		code.add(Opcode.AALOAD);
		code.add(Opcode.ALOAD_0);
		int scanMissPos = code.size();
		code.add(Opcode.IF_ACMPNE);
		emitU2(code, 0);
		emitLdc(code, guard.depthMarkerStr().index());
		code.add(Opcode.ARETURN);
		patchBranch(code, scanMissPos, code.size());
		code.add(Opcode.IINC);
		code.add(3);
		code.add(1);
		int scanLoopPos = code.size();
		code.add(Opcode.GOTO);
		emitU2(code, 0);
		patchBranch(code, scanLoopPos, scanStart);
		patchBranch(code, scanDonePos, code.size());
		// i = _renderDepth; if (i >= 256) return "#"; _renderPath[i] = arg;
		// _renderDepth = i + 1;
		code.add(Opcode.GETSTATIC);
		emitU2(code, guard.depthField().index());
		code.add(Opcode.ISTORE_3);
		code.add(Opcode.ILOAD_3);
		emitIntConstStatic(code, RENDER_DEPTH_CAP);
		int underCapPos = code.size();
		code.add(Opcode.IF_ICMPLT);
		emitU2(code, 0);
		emitLdc(code, guard.depthMarkerStr().index());
		code.add(Opcode.ARETURN);
		patchBranch(code, underCapPos, code.size());
		code.add(Opcode.GETSTATIC);
		emitU2(code, guard.pathField().index());
		code.add(Opcode.ILOAD_3);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.AASTORE);
		code.add(Opcode.ILOAD_3);
		code.add(Opcode.ICONST_1);
		code.add(Opcode.IADD);
		code.add(Opcode.PUTSTATIC);
		emitU2(code, guard.depthField().index());
	}

	/**
	 * Emits the shared cycle guard's EXIT under the rendered string already on the
	 * operand stack, then the {@code areturn}: over one read like the push
	 * ({@code i = _renderDepth - 1; if (i < 0) _renderDepth = 0 else { _renderPath[i] =
	 * null; _renderDepth = i; }}). No finally is needed -- the render helpers the guarded
	 * bodies call do not throw; the clamp is for a rendering RACE (concurrent request
	 * threads), which may misplace a marker but never index out of the array. Local 3 is
	 * scratch.
	 */
	private static void emitRenderGuardExitAndReturn(List<Integer> code, RenderGuardRefs guard) {
		code.add(Opcode.GETSTATIC);
		emitU2(code, guard.depthField().index());
		code.add(Opcode.ICONST_1);
		code.add(Opcode.ISUB);
		code.add(Opcode.ISTORE_3);
		code.add(Opcode.ILOAD_3);
		int popClampPos = code.size();
		code.add(Opcode.IFLT);
		emitU2(code, 0);
		code.add(Opcode.GETSTATIC);
		emitU2(code, guard.pathField().index());
		code.add(Opcode.ILOAD_3);
		code.add(Opcode.ACONST_NULL);
		code.add(Opcode.AASTORE);
		code.add(Opcode.ILOAD_3);
		code.add(Opcode.PUTSTATIC);
		emitU2(code, guard.depthField().index());
		code.add(Opcode.ARETURN);
		patchBranch(code, popClampPos, code.size());
		code.add(Opcode.ICONST_0);
		code.add(Opcode.PUTSTATIC);
		emitU2(code, guard.depthField().index());
		code.add(Opcode.ARETURN);
	}

	// Stores structText or classText into local 4, depending on layout[2].equals("S").
	private static void emitKindChoice(List<Integer> code, MethodrefConstant objectEquals,
			ConstantPool.StringConstant structKindStr, ConstantPool.StringConstant structText,
			ConstantPool.StringConstant classText) {
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.ICONST_2);
		code.add(Opcode.AALOAD);
		emitLdc(code, structKindStr.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, objectEquals.index());
		int ifClassPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		emitLdc(code, structText.index());
		code.add(Opcode.ASTORE);
		code.add(4);
		int gotoDonePos = code.size();
		code.add(Opcode.GOTO);
		emitU2(code, 0);
		patchBranch(code, ifClassPos, code.size());
		emitLdc(code, classText.index());
		code.add(Opcode.ASTORE);
		code.add(4);
		patchBranch(code, gotoDonePos, code.size());
	}

	// sb.append(<constant>); pop
	private static void emitAppendConst(List<Integer> code, MethodrefConstant sbAppendStr,
			ConstantPool.StringConstant text) {
		code.add(Opcode.ALOAD_1);
		emitLdc(code, text.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, sbAppendStr.index());
		code.add(Opcode.POP);
	}

	// Emits, inside the Object[] branch of a lisp-to-string body, the instance test:
	// "if (arr[0] instanceof String[]) return _instToString(arr);". A no-op when the
	// program can build no instance, so its bytes stay out entirely.
	private static int emitInstanceBranch(List<Integer> code, @org.jspecify.annotations.Nullable InstPrint instPrint,
			boolean display) {
		if (instPrint == null) {
			return -1;
		}
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ICONST_0);
		code.add(Opcode.AALOAD);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, instPrint.stringArrayClass().index());
		int ifNotInstPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, display ? instPrint.instToDisplayString().index() : instPrint.instToString().index());
		code.add(Opcode.ARETURN);
		return ifNotInstPos;
	}

	// Emits "if (val instanceof CompletableFuture) return "#<FUTURE>";" at the current
	// position. A no-op when the program cannot create futures, keeping the branch out
	// of future-free programs.
	private static void emitFutureBranch(List<Integer> code,
			@org.jspecify.annotations.Nullable FuturePrint futurePrint) {
		if (futurePrint == null) {
			return;
		}
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, futurePrint.futureClass().index());
		int skip = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		emitLdc(code, futurePrint.futureStr().index());
		code.add(Opcode.ARETURN);
		patchBranch(code, skip, code.size());
		emitMarkerPrintBranch(code, futurePrint, true);
		emitMarkerPrintBranch(code, futurePrint, false);
	}

	// Emits "if (val is Object[3] headed by the stream/read-token marker) return the
	// opaque label" -- streams print as #<STREAM>, pending stream-read tokens as the
	// future label. A no-op when the async value machinery is absent.
	private static void emitMarkerPrintBranch(List<Integer> code, FuturePrint futurePrint, boolean stream) {
		ConstantPool.StringConstant marker = stream ? futurePrint.streamMarker() : futurePrint.readMarker();
		ConstantPool.StringConstant label = stream ? futurePrint.streamStr() : futurePrint.futureStr();
		if (marker == null || label == null || futurePrint.objectArrayClass() == null) {
			return;
		}
		List<Integer> skips = new ArrayList<>();
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, futurePrint.objectArrayClass().index());
		skips.add(code.size());
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, futurePrint.objectArrayClass().index());
		code.add(Opcode.ARRAYLENGTH);
		code.add(Opcode.ICONST_3);
		skips.add(code.size());
		code.add(Opcode.IF_ICMPNE);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, futurePrint.objectArrayClass().index());
		code.add(Opcode.ICONST_0);
		code.add(Opcode.AALOAD);
		emitLdc(code, marker.index());
		skips.add(code.size());
		code.add(Opcode.IF_ACMPNE);
		emitU2(code, 0);
		emitLdc(code, label.index());
		code.add(Opcode.ARETURN);
		for (int skip : skips) {
			patchBranch(code, skip, code.size());
		}
	}

	// Emits "if (val is the compiled hash-table class) return "#<HASH-TABLE :TEST EQUAL
	// :COUNT ".concat(Integer.toString(map.size())).concat(">")" -- the interpreter's
	// LispHashTable.print() answer, so all four backends print one table identically. A
	// no-op in a program that never makes a table.
	private static void emitHashTableBranch(List<Integer> code,
			@org.jspecify.annotations.Nullable HashPrint hashPrint) {
		if (hashPrint == null) {
			return;
		}
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, hashPrint.mapClass().index());
		int skip = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		if (hashPrint.equalpTag() != null && hashPrint.equalpTest() != null) {
			// The table says which test it implements: equalp when it folds its keys,
			// equal otherwise. Only a program that can build one carries the branch.
			code.add(Opcode.ALOAD_0);
			code.add(Opcode.INVOKESTATIC);
			emitU2(code, hashPrint.equalpTest().index());
			int notEqualp = code.size();
			code.add(Opcode.IFNULL);
			emitU2(code, 0);
			emitLdc(code, hashPrint.equalpTag().index());
			int haveTag = code.size();
			code.add(Opcode.GOTO);
			emitU2(code, 0);
			patchBranch(code, notEqualp, code.size());
			emitLdc(code, hashPrint.tag().index());
			patchBranch(code, haveTag, code.size());
		}
		else {
			emitLdc(code, hashPrint.tag().index());
		}
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, hashPrint.mapSize().index());
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, hashPrint.intToString().index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, hashPrint.stringConcat().index());
		emitLdc(code, hashPrint.suffix().index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, hashPrint.stringConcat().index());
		code.add(Opcode.ARETURN);
		patchBranch(code, skip, code.size());
	}

	/**
	 * Emits the final fallback of {@code _lispToString}/{@code _lispToDisplayString}:
	 * plain {@code val.toString()}, preceded -- when {@code java:} interop is in use --
	 * by a {@code #<java class.Name>} branch for wrapped host objects. {@code BigInteger}
	 * (a promoted Lisp integer) still falls through to {@code toString()}, which is its
	 * decimal digits.
	 */
	/** One guarded bridge print branch of {@link #emitDefaultTail}. */
	private static void emitBridgePrintHook(List<Integer> code, @org.jspecify.annotations.Nullable ObjcPrint hook) {
		if (hook == null) {
			return;
		}
		code.add(Opcode.GETSTATIC);
		emitU2(code, hook.initedField().index());
		int notInited = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, hook.print().index());
		code.add(Opcode.DUP);
		int notHandled = code.size();
		code.add(Opcode.IFNULL);
		emitU2(code, 0);
		code.add(Opcode.ARETURN);
		patchBranch(code, notHandled, code.size());
		code.add(Opcode.POP);
		patchBranch(code, notInited, code.size());
	}

	private static void emitDefaultTail(List<Integer> code, MethodrefConstant objectToString,
			@org.jspecify.annotations.Nullable JavaPrint javaPrint,
			@org.jspecify.annotations.Nullable ObjcPrint objcPrint,
			@org.jspecify.annotations.Nullable ObjcPrint ffiPrint) {
		// The objc: and ffi: print hooks share one shape: if (_xInited != 0)
		// { String s = Bridge.print(val); if (s != null) return s; } -- ahead of the
		// java: branch, which would otherwise claim the wrapper as a host object.
		emitBridgePrintHook(code, objcPrint);
		emitBridgePrintHook(code, ffiPrint);
		if (javaPrint != null) {
			List<Integer> toStringBranches = new ArrayList<>();
			code.add(Opcode.ALOAD_0);
			code.add(Opcode.INSTANCEOF);
			emitU2(code, javaPrint.bigIntegerClass().index());
			toStringBranches.add(code.size());
			code.add(Opcode.IFNE);
			emitU2(code, 0);
			// return "#<java ".concat(val.getClass().getName()).concat(">");
			emitLdc(code, javaPrint.prefix().index());
			code.add(Opcode.ALOAD_0);
			code.add(Opcode.INVOKEVIRTUAL);
			emitU2(code, javaPrint.objectGetClass().index());
			code.add(Opcode.INVOKEVIRTUAL);
			emitU2(code, javaPrint.classGetName().index());
			code.add(Opcode.INVOKEVIRTUAL);
			emitU2(code, javaPrint.stringConcat().index());
			emitLdc(code, javaPrint.suffix().index());
			code.add(Opcode.INVOKEVIRTUAL);
			emitU2(code, javaPrint.stringConcat().index());
			code.add(Opcode.ARETURN);
			for (int branch : toStringBranches) {
				patchBranch(code, branch, code.size());
			}
		}
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, objectToString.index());
		code.add(Opcode.ARETURN);
	}

	static void emitU2(List<Integer> code, int value) {
		byte[] bytes = ByteBuffer.allocate(2).putShort((short) value).array();
		code.add((int) bytes[0]);
		code.add((int) bytes[1]);
	}

	// Emits one packed-array print branch: "if (val instanceof <arrayClass>) return
	// arrayToString(_fvToGeneral(val)).replaceFirst("^#\\d*A?\\(", <prefixRepl>);". The
	// element data is rendered exactly as the general array counterpart (single-float
	// widens
	// f32->f64 inside _fvToGeneral), then the leading #/#nA prefix is rewritten to #d( /
	// #f(
	// so the printed form round-trips to a packed array.
	private static void emitPackedPrintBranch(List<Integer> code, ClassConstant arrayClass,
			MethodrefConstant arrayToStringMethod, PackedPrint packedPrint, ConstantPool.StringConstant prefixRepl) {
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, arrayClass.index());
		int ifNotPackedPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, packedPrint.fvToGeneralMethod().index());
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, arrayToStringMethod.index());
		emitLdc(code, packedPrint.prefixRegex().index());
		emitLdc(code, prefixRepl.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, packedPrint.stringReplaceFirst().index());
		code.add(Opcode.ARETURN);
		patchBranch(code, ifNotPackedPos, code.size());
	}

	// Emits "if (val instanceof ArrayList) return arrayToString(val);" at the current
	// position, used by both the prin1 and princ string builders. A no-op when arrays are
	// not used (both args null), keeping the branch out of array-free programs. When the
	// program uses packed float arrays a preceding "if (val instanceof double[]) return
	// arrayToString(_fvToGeneral(val)).replaceFirst("^#\\d*A?\\(", "#d(");" branch
	// renders a
	// packed double[] through the #d(...) syntax (so it round-trips to a packed array):
	// the
	// element data is rendered exactly as the general counterpart, then the leading
	// #/#nA prefix is rewritten to #d. A mutable CHARACTER VECTOR (an ArrayList that
	// _strv normalizes to a String) renders like a string instead of #(...): the prin1
	// body (stringLength/stringSubstring null) returns the quote-framed _strv result
	// verbatim, the princ body strips the surrounding quotes with substring like its
	// String branch.
	private static void emitArrayBranch(List<Integer> code,
			@org.jspecify.annotations.Nullable ClassConstant arrayListClass,
			@org.jspecify.annotations.Nullable MethodrefConstant arrayToStringMethod,
			@org.jspecify.annotations.Nullable PackedPrint packedPrint,
			@org.jspecify.annotations.Nullable PackedIntPrint packedIntPrint,
			@org.jspecify.annotations.Nullable MethodrefConstant strvMethod, ClassConstant stringClass,
			@org.jspecify.annotations.Nullable MethodrefConstant stringLength,
			@org.jspecify.annotations.Nullable MethodrefConstant stringSubstring,
			@org.jspecify.annotations.Nullable MethodrefConstant strEscMethod) {
		if (arrayListClass == null || arrayToStringMethod == null) {
			return;
		}
		if (packedPrint != null) {
			// if (val instanceof double[]) -> #d(...); if (val instanceof float[]) ->
			// #f(...)
			emitPackedPrintBranch(code, packedPrint.doubleArrayClass(), arrayToStringMethod, packedPrint,
					packedPrint.prefixRepl());
			emitPackedPrintBranch(code, packedPrint.floatArrayClass(), arrayToStringMethod, packedPrint,
					packedPrint.prefixReplSingle());
		}
		if (packedIntPrint != null) {
			// if (val instanceof long[]) return arrayToString(_ivToGeneral(val)); -- a
			// plain #(...) vector, no prefix rewrite.
			code.add(Opcode.ALOAD_0);
			code.add(Opcode.INSTANCEOF);
			emitU2(code, packedIntPrint.longArrayClass().index());
			int ifNotPackedIntPos = code.size();
			code.add(Opcode.IFEQ);
			emitU2(code, 0);
			code.add(Opcode.ALOAD_0);
			code.add(Opcode.INVOKESTATIC);
			emitU2(code, packedIntPrint.ivToGeneralMethod().index());
			code.add(Opcode.INVOKESTATIC);
			emitU2(code, arrayToStringMethod.index());
			code.add(Opcode.ARETURN);
			patchBranch(code, ifNotPackedIntPos, code.size());
		}
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, arrayListClass.index());
		int ifNotArrayPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		if (strvMethod != null) {
			// Object s = _strv(val); if (s instanceof String) -> character vector
			code.add(Opcode.ALOAD_0);
			code.add(Opcode.INVOKESTATIC);
			emitU2(code, strvMethod.index());
			code.add(Opcode.ASTORE_1);
			code.add(Opcode.ALOAD_1);
			code.add(Opcode.INSTANCEOF);
			emitU2(code, stringClass.index());
			int ifPlainArrayPos = code.size();
			code.add(Opcode.IFEQ);
			emitU2(code, 0);
			code.add(Opcode.ALOAD_1);
			code.add(Opcode.CHECKCAST);
			emitU2(code, stringClass.index());
			if (stringLength == null || stringSubstring == null) {
				// prin1: the quote-framed string, escaped exactly like the String branch
				code.add(Opcode.INVOKESTATIC);
				emitU2(code, java.util.Objects.requireNonNull(strEscMethod).index());
				code.add(Opcode.ARETURN);
			}
			else {
				// princ: return s.substring(1, s.length() - 1)
				code.add(Opcode.ASTORE_1);
				code.add(Opcode.ALOAD_1);
				code.add(Opcode.ICONST_1);
				code.add(Opcode.ALOAD_1);
				code.add(Opcode.INVOKEVIRTUAL);
				emitU2(code, stringLength.index());
				code.add(Opcode.ICONST_1);
				code.add(Opcode.ISUB);
				code.add(Opcode.INVOKEVIRTUAL);
				emitU2(code, stringSubstring.index());
				code.add(Opcode.ARETURN);
			}
			patchBranch(code, ifPlainArrayPos, code.size());
		}
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, arrayToStringMethod.index());
		code.add(Opcode.ARETURN);
		patchBranch(code, ifNotArrayPos, code.size());
	}

	static void emitLdc(List<Integer> code, int cpIndex) {
		if (cpIndex <= 255) {
			code.add(Opcode.LDC);
			code.add(cpIndex);
		}
		else {
			code.add(Opcode.LDC_W);
			emitU2(code, cpIndex);
		}
	}

	/**
	 * The pool-free variant of {@link JvmEmitHelper#emitIntConst}, for the runtime
	 * builders that assemble a body as a raw byte list. Its callers push funcIds and
	 * character codes that are bounded by the program's own function count, so the
	 * {@code sipush} range is enough -- but a value past it would truncate and
	 * sign-extend SILENTLY into a class that verifies and computes the wrong number, so
	 * it fails loudly here instead, the way {@link #patchBranch} does for an overflowing
	 * branch offset. The fix if it ever fires: hand this builder a constant pool and use
	 * the {@code ldc} arm {@code JvmEmitHelper.emitIntConst} has.
	 */
	static void emitIntConstStatic(List<Integer> code, int value) {
		if (value >= 0 && value <= 5) {
			code.add(Opcode.ICONST_0 + value);
		}
		else if (value >= -128 && value <= 127) {
			code.add(Opcode.BIPUSH);
			code.add(value & 0xFF);
		}
		else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
			code.add(Opcode.SIPUSH);
			emitU2(code, value);
		}
		else {
			throw new IllegalStateException(
					"int constant " + value + " overflows the signed 16-bit sipush encoding in a pool-free builder");
		}
	}

	static void patchBranch(List<Integer> code, int branchPos, int targetPos) {
		int offset = targetPos - branchPos;
		if (offset < Short.MIN_VALUE || offset > Short.MAX_VALUE) {
			// A silently wrapped offset produces a class the verifier rejects with an
			// unrelated-looking error (or worse, wrong control flow); fail loudly at
			// the source instead. A body this large needs its dispatch outlined or
			// split -- see the shared %typep-runtime/%subtypep-runtime/%error-runtime
			// defuns and the segmented _invoke_N/_lookup builders.
			throw new IllegalStateException("branch offset " + offset + " at position " + branchPos
					+ " overflows the signed 16-bit branch encoding (method body too large)");
		}
		byte[] bytes = ByteBuffer.allocate(2).putShort((short) offset).array();
		code.set(branchPos + 1, (int) bytes[0]);
		code.set(branchPos + 2, (int) bytes[1]);
	}

}

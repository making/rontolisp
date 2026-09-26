package am.ik.rontolisp.codegen.jvm;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.RenderCycleGuard;
import am.ik.rontolisp.compiler.OperandTypes;

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
			@org.jspecify.annotations.Nullable Set<Integer> dispatchable, ArityReporting arityReporting) {
		return buildDispatchMethods(arity, functions, lambdaDecls, lambdaFuncInfos, cp, thisClass, objectArrayClass,
				integerClass, integerValue, objectClass, stringClass, applyRef, lookupRef, false, dispatchable,
				arityReporting);
	}

	/**
	 * The methodrefs a dispatcher signals a wrong argument COUNT through, or
	 * {@link #NONE} for a build that does not report one.
	 *
	 * <p>
	 * A dispatcher's no-match arm used to answer nil, which made
	 * {@code (funcall #'f 1 2)} on a one-parameter {@code f} the quietest of failures:
	 * the interpreter signals a {@code program-error} there, so an ANSI
	 * {@code signals-error} test passed interpreted and returned a WRONG VALUE compiled.
	 * The arm now throws instead, spelled by {@link ClosRegistry#arityMessage} exactly as
	 * the interpreter spells it, and {@code JvmHandlerCaseCompiler} recovers the class
	 * from that text (the unbound-variable precedent -- a bytecode-emitted throw site has
	 * no channel for a condition).
	 *
	 * <p>
	 * The SPREAD dispatcher has no such arm: it carries a case for every callable and
	 * reads the parameters out of a LIST, so a wrong count there is no dispatch miss at
	 * all. Its cases -- and the physical direct call a literal {@code (apply #'f list)}
	 * compiles to, which does not reach a dispatcher either -- carry a
	 * {@code _arityChk(argList, shape)} call instead, which measures the list against the
	 * callee shape baked at the site and throws the same message. One shared helper for
	 * both, because both sites are the same question asked of the same two values.
	 *
	 * @param errRef {@code _arityErr(funcId, got)}, the per-arity dispatchers' no-match
	 * arm: it maps the funcId back to the callee's shape and throws
	 * @param chkRef {@code _arityChk(argList, shape)}, the spread cases' and the literal
	 * {@code apply} call sites' count guard
	 * @param operators the program's arity-operator registry, which a spread case bakes
	 * its callee's shape through; null exactly when {@code chkRef} is
	 */
	record ArityReporting(@org.jspecify.annotations.Nullable MethodrefConstant errRef,
			@org.jspecify.annotations.Nullable MethodrefConstant chkRef,
			@org.jspecify.annotations.Nullable JvmArityOperators operators) {

		static final ArityReporting NONE = new ArityReporting(null, null, null);

	}

	/**
	 * The class every arity reporter throws: {@code _arityErr} and {@code _arityChk}. A
	 * bytecode-emitted throw site has no channel for a condition, so the landing pad
	 * recovers {@code program-error} from the exception's CLASS
	 * ({@code JvmHandlerCaseCompiler}). It used to recover it from the
	 * {@code Function expects } prefix of the text, which a named report does not start
	 * with and which a user's {@code (error "Function expects ...")} -- a plain
	 * {@code RuntimeException} -- matched too. The JDK class is the one whose meaning is
	 * a call made with the wrong signature, and nothing else a compiled program runs
	 * raises it.
	 */
	static final String ARITY_EXCEPTION_CLASS = "java/lang/invoke/WrongMethodTypeException";

	/**
	 * {@code _notFn(Object value)}: the exception applying a value that names no function
	 * raises ({@link #buildNotFnBody}).
	 */
	static final String NOT_FN_NAME = "_notFn";

	static final String NOT_FN_DESC = "(Ljava/lang/Object;)Ljava/lang/RuntimeException;";

	/** {@code _arityMsg(int shape, int got)}: the message, built at the throw. */
	static final String ARITY_MSG_NAME = "_arityMsg";

	static final String ARITY_MSG_DESC = "(II)Ljava/lang/String;";

	/**
	 * {@code _arityErr(int funcId, int got)}: the per-arity dispatchers' no-match arm.
	 */
	static final String ARITY_ERR_NAME = "_arityErr";

	static final String ARITY_ERR_DESC = "(II)Ljava/lang/Object;";

	/**
	 * {@code _arityChk(Object argList, int shape)}: the count guard a SPREAD case and a
	 * literal {@code apply}'s direct call share. It counts the list and throws
	 * {@code _arityMsg(shape, got)} when the shape cannot take that many.
	 */
	static final String ARITY_CHK_NAME = "_arityChk";

	static final String ARITY_CHK_DESC = "(Ljava/lang/Object;I)V";

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
			@org.jspecify.annotations.Nullable Set<Integer> dispatchable, ArityReporting arityReporting) {
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
				cases.add(renderSpreadCase(fi, entry.getKey(), -1, cp, objectArrayClass, arityReporting));
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
				cases.add(renderSpreadCase(lambdaFuncInfos.get(i), null, fvSlot, cp, objectArrayClass, arityReporting));
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
			if (segment == 0) {
				// The callee's representation decides, on the path every indirect call
				// already takes: a function value is an Object[] whose slot 0 is its
				// Integer funcId. The two instanceof tests fold into the casts that
				// follow them, so a function value pays nothing it did not pay before.
				MethodrefConstant notFnRef = cp.addMethodref(thisClass,
						cp.addNameAndType(cp.addUtf8(NOT_FN_NAME), cp.addUtf8(NOT_FN_DESC)));
				code.add(Opcode.ALOAD_0);
				code.add(Opcode.INSTANCEOF);
				emitU2(code, objectArrayClass.index());
				int ifNotArrayPos = code.size();
				code.add(Opcode.IFEQ);
				emitU2(code, 0);
				// Object[] fv = (Object[]) funcval;
				code.add(Opcode.ALOAD_0);
				code.add(Opcode.CHECKCAST);
				emitU2(code, objectArrayClass.index());
				code.add(Opcode.ASTORE);
				code.add(fvSlot);
				// A cons, an instance, an empty vector: no funcId in slot 0.
				code.add(Opcode.ALOAD);
				code.add(fvSlot);
				code.add(Opcode.ARRAYLENGTH);
				int ifEmptyPos = code.size();
				code.add(Opcode.IFEQ);
				emitU2(code, 0);
				code.add(Opcode.ALOAD);
				code.add(fvSlot);
				code.add(Opcode.ICONST_0);
				code.add(Opcode.AALOAD);
				code.add(Opcode.INSTANCEOF);
				emitU2(code, integerClass.index());
				int ifNoIdPos = code.size();
				code.add(Opcode.IFEQ);
				emitU2(code, 0);
				int toResolvedPos = code.size();
				code.add(Opcode.GOTO);
				emitU2(code, 0);
				patchBranch(code, ifNotArrayPos, code.size());
				if (lookupRef != null) {
					// A String funcval is a SYMBOL used as a function designator (the
					// interpreter's late binding): resolve it through _lookup, whose
					// Object[]{funcId, arity} result carries the id in slot 0 exactly
					// like a function value. A chained segment receives the
					// already-resolved fv.
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
					notFnThrowAt(code, notFnRef, ifNotStringPos, ifEmptyPos, ifNoIdPos);
					patchBranch(code, ifResolvedPos, code.size());
				}
				else {
					notFnThrowAt(code, notFnRef, ifEmptyPos, ifNoIdPos);
				}
				patchBranch(code, toResolvedPos, code.size());
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
				// Default: an id no case of this arity claims. For a per-arity
				// dispatcher that is a CALL with the wrong number of arguments -- the
				// callee exists, it just has another shape -- so the arm reports it
				// (ArityReporting). The spread dispatcher carries a case for every
				// callable, so its default really is an id nothing claims and answers
				// nil as before.
				int defaultPos = code.size();
				for (int jump : defaultJumps) {
					patchBranch(code, jump, defaultPos);
				}
				if (!spread && arityReporting.errRef() != null) {
					code.add(Opcode.ILOAD);
					code.add(idSlot);
					emitIntConstStatic(code, arity);
					code.add(Opcode.INVOKESTATIC);
					emitU2(code, arityReporting.errRef().index());
				}
				else {
					code.add(Opcode.ACONST_NULL);
				}
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

	/**
	 * {@code _notFn(Object value) -> RuntimeException}: what applying a value that names
	 * no function raises, returned for the caller to throw. The text is the
	 * interpreter's, so {@code JvmHandlerCaseCompiler} recovers the class from it:
	 * {@code The function NAME is undefined} ({@code undefined-function}) for a symbol --
	 * NIL, which is {@code null} here, included -- and
	 * {@link ClosRegistry#NOT_A_FUNCTION_MESSAGE_PREFIX} plus the value printed
	 * ({@code type-error}) for anything else, a quote-framed string among them.
	 * @param cp the constant pool
	 * @param stringClass the {@code String} class constant
	 * @param lispToString the generated class's {@code _lispToString(Object)}
	 * @return the method body
	 */
	static List<Integer> buildNotFnBody(ConstantPool cp, ClassConstant stringClass, MethodrefConstant lispToString) {
		ClassConstant runtimeEx = cp.addClass(cp.addUtf8("java/lang/RuntimeException"));
		MethodrefConstant exCtor = cp.addMethodref(runtimeEx,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		MethodrefConstant concat = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("concat"), cp.addUtf8("(Ljava/lang/String;)Ljava/lang/String;")));
		MethodrefConstant startsWith = cp.addMethodref(stringClass,
				cp.addNameAndType(cp.addUtf8("startsWith"), cp.addUtf8("(Ljava/lang/String;)Z")));
		JvmAsm a = new JvmAsm();
		int notNull = a.label();
		int notSymbol = a.label();
		a.aload(0);
		a.branch(Opcode.IFNONNULL, notNull);
		a.anew(runtimeEx);
		a.dup();
		a.ldcString(cp.addString(ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_PREFIX + "NIL"
				+ ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_SUFFIX));
		a.invokespecial(exCtor);
		a.areturn();
		a.bind(notNull);
		// a symbol is its bare name; a string keeps its framing quote
		a.aload(0);
		a.instanceOf(stringClass);
		a.branch(Opcode.IFEQ, notSymbol);
		a.aload(0);
		a.checkcast(stringClass);
		a.ldcString(cp.addString("\""));
		a.invokevirtual(startsWith);
		a.branch(Opcode.IFNE, notSymbol);
		a.anew(runtimeEx);
		a.dup();
		a.ldcString(cp.addString(ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_PREFIX));
		a.aload(0);
		a.checkcast(stringClass);
		a.invokevirtual(concat);
		a.ldcString(cp.addString(ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_SUFFIX));
		a.invokevirtual(concat);
		a.invokespecial(exCtor);
		a.areturn();
		a.bind(notSymbol);
		a.anew(runtimeEx);
		a.dup();
		a.ldcString(cp.addString(ClosRegistry.NOT_A_FUNCTION_MESSAGE_PREFIX));
		a.aload(0);
		a.invokestatic(lispToString);
		a.invokevirtual(concat);
		a.invokespecial(exCtor);
		a.areturn();
		return a.code;
	}

	/**
	 * Binds the given forward branches here and emits {@code throw _notFn(funcval)}.
	 */
	private static void notFnThrowAt(List<Integer> code, MethodrefConstant notFnRef, int... branches) {
		for (int branch : branches) {
			patchBranch(code, branch, code.size());
		}
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, notFnRef.index());
		code.add(Opcode.ATHROW);
	}

	private static boolean dispatchMatches(int paramCount, boolean variadic, int arity) {
		return variadic ? arity >= paramCount - 1 : paramCount == arity;
	}

	/**
	 * The callee shape a wrong-count report is spelled from: the required parameter count
	 * doubled, plus one for a {@code &rest} tail. One int, so it rides a string table
	 * cell and an {@code _arityChk} argument alike.
	 */
	static int arityShape(int required, boolean variadic) {
		return JvmArityOperators.plainShape(required, variadic);
	}

	/**
	 * Where a callee shape carries the operator a report names
	 * ({@link JvmArityOperators#OPERATOR_SHIFT}).
	 */
	static final int ARITY_OPERATOR_SHIFT = JvmArityOperators.OPERATOR_SHIFT;

	/**
	 * Where an {@code _arityErr} table cell carries the operator index: above the seven
	 * bits of {@code shape + 1}, so a cell that names nothing keeps its one-byte
	 * encoding. Nine bits are left in a {@code char}.
	 */
	private static final int ARITY_TABLE_OPERATOR_SHIFT = 7;

	private static final int ARITY_TABLE_MAX_OPERATOR = (1 << (Character.SIZE - ARITY_TABLE_OPERATOR_SHIFT)) - 1;

	/**
	 * The {@code _arityErr} table cell for a funcId no dispatchable callable claims --
	 * and for a shape too wide to fit one: such an id answers nil, exactly as the arm did
	 * before it reported anything. Chosen inside the one-byte range of the class file's
	 * modified UTF-8 so the table costs one byte per funcId whatever it holds.
	 */
	private static final int ARITY_TABLE_NONE = 0x7F;

	/**
	 * The widest funcId span {@code _arityErr} will table over. Past it the reporter is
	 * dropped rather than emitted wrong: the length comparison is a {@code sipush} and
	 * the table a single UTF-8 constant, both of which have hard ceilings, and no real
	 * program comes within an order of magnitude of this one.
	 */
	private static final int ARITY_TABLE_MAX_SPAN = 30_000;

	/**
	 * Builds the arity-reporting helpers a program shares: {@code _arityMsg} (the
	 * message), {@code _arityErr} (the per-arity dispatchers' no-match arm) and
	 * {@code _arityChk} (the count guard a SPREAD case and a literal {@code apply}'s
	 * direct call carry). The last two are emitted only for a program that has such a
	 * site, so a program with only one kind pays only for that kind.
	 *
	 * <p>
	 * {@code _arityErr} maps the funcId back to the callee's shape through a STRING
	 * indexed by funcId, not a search tree over the dispatchable ids. A tree is what the
	 * dispatchers themselves use, and it is the wrong shape here: it costs ~15 bytes per
	 * callable in ONE method, and the cl-postgres corpus (thousands of first-class
	 * functions) blew past the signed 16-bit branch offset -- the failure
	 * {@link #patchBranch} exists to name. The table is one byte per funcId in the
	 * constant pool and a constant three instructions of code, whatever the program's
	 * size.
	 * @param functions the program's named functions
	 * @param lambdaDecls the program's lambda declarations
	 * @param cp the constant pool
	 * @param thisClass the class being emitted
	 * @param objectArrayClass the {@code Object[]} class constant (a cons cell)
	 * @param stringClass the {@code String} class constant
	 * @param dispatchable the funcIds reachable as a function value, or null for all
	 * @param withErr whether the program has a per-arity dispatcher, whose no-match arm
	 * {@code _arityErr} serves
	 * @param withChk whether the program has a spread case or a literal {@code apply}
	 * call site, which {@code _arityChk} serves
	 * @param operators the program's arity-operator registry: every dispatchable callee
	 * registers here -- which covers every spread case, built after this -- and
	 * {@code _arityMsg} is built from it, frozen
	 * @return the helper methods
	 */
	static List<JvmLispCompiler.DispatchMethod> buildArityMethods(Map<String, JvmLispCompiler.FunctionInfo> functions,
			List<JvmLispCompiler.LambdaInfo> lambdaDecls, ConstantPool cp, ClassConstant thisClass,
			ClassConstant objectArrayClass, ClassConstant stringClass,
			@org.jspecify.annotations.Nullable Set<Integer> dispatchable, boolean withErr, boolean withChk,
			JvmArityOperators operators) {
		SortedMap<Integer, Integer> shapes = new java.util.TreeMap<>();
		for (Map.Entry<String, JvmLispCompiler.FunctionInfo> entry : functions.entrySet()) {
			JvmLispCompiler.FunctionInfo fi = entry.getValue();
			if (fi.isClosure() || (dispatchable != null && !dispatchable.contains(fi.funcId()))) {
				continue;
			}
			shapes.put(fi.funcId(), operators.shape(fi.variadic() ? fi.paramCount() - 1 : fi.paramCount(),
					fi.variadic(), entry.getKey()));
		}
		for (JvmLispCompiler.LambdaInfo lambda : lambdaDecls) {
			if (dispatchable != null && !dispatchable.contains(lambda.funcId())) {
				continue;
			}
			int params = lambda.paramNames().size();
			shapes.put(lambda.funcId(), arityShape(lambda.variadic() ? params - 1 : params, lambda.variadic()));
		}
		ClassConstant runtimeEx = cp.addClass(cp.addUtf8(ARITY_EXCEPTION_CLASS));
		MethodrefConstant exCtor = cp.addMethodref(runtimeEx,
				cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("(Ljava/lang/String;)V")));
		MethodrefConstant msgRef = cp.addMethodref(thisClass,
				cp.addNameAndType(cp.addUtf8(ARITY_MSG_NAME), cp.addUtf8(ARITY_MSG_DESC)));
		List<String> operatorNames = operators.freeze();
		List<JvmLispCompiler.DispatchMethod> methods = new ArrayList<>();
		methods.add(new JvmLispCompiler.DispatchMethod(cp.addUtf8(ARITY_MSG_NAME), cp.addUtf8(ARITY_MSG_DESC),
				buildArityMsgBody(cp, stringClass, operatorNames), operatorNames.isEmpty() ? 4 : 5));
		if (withErr) {
			methods.add(new JvmLispCompiler.DispatchMethod(cp.addUtf8(ARITY_ERR_NAME), cp.addUtf8(ARITY_ERR_DESC),
					buildArityErrBody(shapes, cp, stringClass, runtimeEx, exCtor, msgRef), 3));
		}
		if (withChk) {
			methods.add(new JvmLispCompiler.DispatchMethod(cp.addUtf8(ARITY_CHK_NAME), cp.addUtf8(ARITY_CHK_DESC),
					buildArityChkBody(objectArrayClass, runtimeEx, exCtor, msgRef, !operatorNames.isEmpty()), 5));
		}
		return methods;
	}

	/**
	 * {@code _arityChk(argList, shape)}: throw unless the list is a count the shape can
	 * take. The two sites that carry it -- a SPREAD dispatcher case and the physical
	 * direct call a literal {@code (apply #'f list)} compiles to -- both hold the callee
	 * shape as a compile-time constant and the count only as the LENGTH of a list, which
	 * is why neither is a dispatch miss and why one shared helper serves both.
	 *
	 * <p>
	 * The walk is the whole list, not the first {@code required + 1} cells: the count it
	 * reports has to be the real one ({@code Function expects 1 argument, got 3}, as the
	 * interpreter says it), and an {@code apply} argument list is the arguments of ONE
	 * call. Nothing here allocates, and the common case returns after the walk. It stops
	 * at the first non-cons rather than casting, so an IMPROPER tail ends the count
	 * instead of raising: {@code (apply #'f '(1 . 2))} is undefined in CL and answered
	 * {@code (f 1)} before this guard existed, and a guard is no place to start failing
	 * on it.
	 */
	private static List<Integer> buildArityChkBody(ClassConstant objectArrayClass, ClassConstant runtimeEx,
			MethodrefConstant exCtor, MethodrefConstant msgRef, boolean named) {
		// Params: 0 = argList, 1 = shape. Locals: 2 = got, 3 = cursor, 4 = required.
		int argList = 0, shape = 1, got = 2, cursor = 3, required = 4;
		JvmAsm a = new JvmAsm();
		int loop = a.label();
		int counted = a.label();
		int variadic = a.label();
		int bad = a.label();
		int ok = a.label();
		a.iconst(0);
		a.istore(got);
		a.aload(argList);
		a.astore(cursor);
		a.bind(loop);
		a.aload(cursor);
		a.instanceOf(objectArrayClass);
		a.branch(Opcode.IFEQ, counted);
		a.iinc(got, 1);
		a.aload(cursor);
		a.checkcast(objectArrayClass);
		a.iconst(1);
		a.aaload();
		a.astore(cursor);
		a.branch(Opcode.GOTO, loop);
		a.bind(counted);
		a.iload(shape);
		if (named) {
			// the operator bits above ARITY_OPERATOR_SHIFT shifted out first
			a.iconst(Integer.SIZE - ARITY_OPERATOR_SHIFT);
			a.op(Opcode.ISHL);
			a.iconst(Integer.SIZE - ARITY_OPERATOR_SHIFT + 1);
		}
		else {
			a.iconst(1);
		}
		a.op(Opcode.IUSHR);
		a.istore(required);
		// a &rest tail makes the required count a lower bound
		a.iload(shape);
		a.iconst(1);
		a.op(Opcode.IAND);
		a.branch(Opcode.IFNE, variadic);
		a.iload(got);
		a.iload(required);
		a.branch(Opcode.IF_ICMPEQ, ok);
		a.branch(Opcode.GOTO, bad);
		a.bind(variadic);
		a.iload(got);
		a.iload(required);
		a.branch(Opcode.IF_ICMPGE, ok);
		a.bind(bad);
		a.anew(runtimeEx);
		a.dup();
		a.iload(shape);
		a.iload(got);
		a.invokestatic(msgRef);
		a.invokespecial(exCtor);
		a.op(Opcode.ATHROW);
		a.bind(ok);
		a.op(Opcode.RETURN);
		return a.code;
	}

	/**
	 * {@code _arityMsg(shape, got)}: the text, assembled out of the very constants
	 * {@link ClosRegistry#arityMessage} composes, so the compiled wording cannot drift
	 * from the interpreter's. With {@code operatorNames} the shape may carry an operator
	 * ({@link JvmArityOperators}), whose name is read out of the newline-joined names and
	 * stands where {@code Function} would; without, the body is the one a build that
	 * named nothing emitted, byte for byte.
	 */
	private static List<Integer> buildArityMsgBody(ConstantPool cp, ClassConstant stringClass,
			List<String> operatorNames) {
		ClassConstant sb = cp.addClass(cp.addUtf8("java/lang/StringBuilder"));
		MethodrefConstant sbInit = cp.addMethodref(sb, cp.addNameAndType(cp.addUtf8("<init>"), cp.addUtf8("()V")));
		MethodrefConstant appendStr = cp.addMethodref(sb,
				cp.addNameAndType(cp.addUtf8("append"), cp.addUtf8("(Ljava/lang/String;)Ljava/lang/StringBuilder;")));
		MethodrefConstant appendInt = cp.addMethodref(sb,
				cp.addNameAndType(cp.addUtf8("append"), cp.addUtf8("(I)Ljava/lang/StringBuilder;")));
		MethodrefConstant toString = cp.addMethodref(sb,
				cp.addNameAndType(cp.addUtf8("toString"), cp.addUtf8("()Ljava/lang/String;")));
		List<Integer> code = new ArrayList<>();
		boolean named = !operatorNames.isEmpty();
		// required = shape >>> 1, the operator bits shifted out first when there are any
		code.add(Opcode.ILOAD_0);
		if (named) {
			emitIntConstStatic(code, Integer.SIZE - ARITY_OPERATOR_SHIFT);
			code.add(Opcode.ISHL);
			emitIntConstStatic(code, Integer.SIZE - ARITY_OPERATOR_SHIFT + 1);
		}
		else {
			code.add(Opcode.ICONST_1);
		}
		code.add(Opcode.IUSHR);
		code.add(Opcode.ISTORE_3);
		code.add(Opcode.NEW);
		emitU2(code, sb.index());
		code.add(Opcode.DUP);
		code.add(Opcode.INVOKESPECIAL);
		emitU2(code, sbInit.index());
		code.add(Opcode.ASTORE_2);
		if (named) {
			// operator = shape >>> ARITY_OPERATOR_SHIFT; 0 reports as "Function"
			int operator = 4;
			code.add(Opcode.ILOAD_0);
			emitIntConstStatic(code, ARITY_OPERATOR_SHIFT);
			code.add(Opcode.IUSHR);
			code.add(Opcode.DUP);
			code.add(Opcode.ISTORE);
			code.add(operator);
			int ifNamed = code.size();
			code.add(Opcode.IFNE);
			emitU2(code, 0);
			emitArityAppend(code, cp, appendStr, ClosRegistry.ARITY_MESSAGE_PREFIX);
			int toJoin = code.size();
			code.add(Opcode.GOTO);
			emitU2(code, 0);
			patchBranch(code, ifNamed, code.size());
			// sb.append(NAMES.split("\n")[operator - 1]).append(" expects ")
			code.add(Opcode.ALOAD_2);
			emitLdc(code, cp.addString(String.join(ARITY_OPERATOR_SEPARATOR, operatorNames)).index());
			emitLdc(code, cp.addString(ARITY_OPERATOR_SEPARATOR).index());
			code.add(Opcode.INVOKEVIRTUAL);
			emitU2(code, cp
				.addMethodref(stringClass,
						cp.addNameAndType(cp.addUtf8("split"), cp.addUtf8("(Ljava/lang/String;)[Ljava/lang/String;")))
				.index());
			code.add(Opcode.ILOAD);
			code.add(operator);
			code.add(Opcode.ICONST_1);
			code.add(Opcode.ISUB);
			code.add(Opcode.AALOAD);
			code.add(Opcode.INVOKEVIRTUAL);
			emitU2(code, appendStr.index());
			code.add(Opcode.POP);
			emitArityAppend(code, cp, appendStr, ClosRegistry.ARITY_VERB);
			patchBranch(code, toJoin, code.size());
		}
		else {
			emitArityAppend(code, cp, appendStr, ClosRegistry.ARITY_MESSAGE_PREFIX);
		}
		// a &rest tail makes the count a lower bound
		code.add(Opcode.ILOAD_0);
		code.add(Opcode.ICONST_1);
		code.add(Opcode.IAND);
		int notVariadic = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		emitArityAppend(code, cp, appendStr, ClosRegistry.ARITY_AT_LEAST);
		patchBranch(code, notVariadic, code.size());
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.ILOAD_3);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, appendInt.index());
		code.add(Opcode.POP);
		emitArityAppend(code, cp, appendStr, ClosRegistry.ARITY_ARGUMENT);
		code.add(Opcode.ILOAD_3);
		code.add(Opcode.ICONST_1);
		int singular = code.size();
		code.add(Opcode.IF_ICMPEQ);
		emitU2(code, 0);
		emitArityAppend(code, cp, appendStr, ClosRegistry.ARITY_PLURAL);
		patchBranch(code, singular, code.size());
		emitArityAppend(code, cp, appendStr, ClosRegistry.ARITY_MESSAGE_INFIX);
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.ILOAD_1);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, appendInt.index());
		code.add(Opcode.POP);
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, toString.index());
		code.add(Opcode.ARETURN);
		return code;
	}

	/**
	 * What joins the operator names {@code _arityMsg} splits: no operator name contains a
	 * newline, and a one-character non-metacharacter pattern is {@code String.split}'s
	 * regex-free path.
	 */
	private static final String ARITY_OPERATOR_SEPARATOR = "\n";

	/** {@code sb.append(<literal>)} inside {@code _arityMsg}, discarding the builder. */
	private static void emitArityAppend(List<Integer> code, ConstantPool cp, MethodrefConstant appendStr, String text) {
		code.add(Opcode.ALOAD_2);
		emitLdc(code, cp.addString(text).index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, appendStr.index());
		code.add(Opcode.POP);
	}

	/**
	 * {@code _arityErr(funcId, got)}: read the callee's shape out of the table and throw.
	 * A funcId the table does not cover answers nil, which is what the no-match arm did
	 * before it reported anything -- such an id can only come from a corrupted function
	 * value, and a report is not worth turning that into a throw.
	 */
	private static List<Integer> buildArityErrBody(SortedMap<Integer, Integer> shapes, ConstantPool cp,
			ClassConstant stringClass, ClassConstant runtimeEx, MethodrefConstant exCtor, MethodrefConstant msgRef) {
		List<Integer> code = new ArrayList<>();
		List<Integer> nullJumps = new ArrayList<>();
		int base = shapes.isEmpty() ? 0 : shapes.firstKey();
		int span = shapes.isEmpty() ? 0 : shapes.lastKey() - base + 1;
		if (span > 0 && span <= ARITY_TABLE_MAX_SPAN) {
			StringBuilder table = new StringBuilder(span);
			boolean named = false;
			for (int i = 0; i < span; i++) {
				Integer shape = shapes.get(base + i);
				int cell = shape == null ? ARITY_TABLE_NONE : arityTableCell(shape);
				named |= cell > ARITY_TABLE_NONE;
				table.append((char) cell);
			}
			code.add(Opcode.ILOAD_0);
			if (base != 0) {
				emitIntConstStatic(code, base);
				code.add(Opcode.ISUB);
			}
			code.add(Opcode.ISTORE_2);
			code.add(Opcode.ILOAD_2);
			nullJumps.add(code.size());
			code.add(Opcode.IFLT);
			emitU2(code, 0);
			code.add(Opcode.ILOAD_2);
			emitIntConstStatic(code, span);
			nullJumps.add(code.size());
			code.add(Opcode.IF_ICMPGE);
			emitU2(code, 0);
			emitLdc(code, cp.addString(table.toString()).index());
			code.add(Opcode.ILOAD_2);
			code.add(Opcode.INVOKEVIRTUAL);
			emitU2(code,
					cp.addMethodref(stringClass, cp.addNameAndType(cp.addUtf8("charAt"), cp.addUtf8("(I)C"))).index());
			code.add(Opcode.ISTORE_2);
			code.add(Opcode.ILOAD_2);
			emitIntConstStatic(code, ARITY_TABLE_NONE);
			nullJumps.add(code.size());
			code.add(Opcode.IF_ICMPEQ);
			emitU2(code, 0);
			code.add(Opcode.NEW);
			emitU2(code, runtimeEx.index());
			code.add(Opcode.DUP);
			if (named) {
				// shape = (cell >>> 7) << 16 | ((cell & 0x7F) - 1)
				code.add(Opcode.ILOAD_2);
				emitIntConstStatic(code, ARITY_TABLE_OPERATOR_SHIFT);
				code.add(Opcode.IUSHR);
				emitIntConstStatic(code, ARITY_OPERATOR_SHIFT);
				code.add(Opcode.ISHL);
				code.add(Opcode.ILOAD_2);
				emitIntConstStatic(code, (1 << ARITY_TABLE_OPERATOR_SHIFT) - 1);
				code.add(Opcode.IAND);
				code.add(Opcode.ICONST_1);
				code.add(Opcode.ISUB);
				code.add(Opcode.IOR);
			}
			else {
				code.add(Opcode.ILOAD_2);
				code.add(Opcode.ICONST_1);
				code.add(Opcode.ISUB);
			}
			code.add(Opcode.ILOAD_1);
			code.add(Opcode.INVOKESTATIC);
			emitU2(code, msgRef.index());
			code.add(Opcode.INVOKESPECIAL);
			emitU2(code, exCtor.index());
			code.add(Opcode.ATHROW);
		}
		for (int jump : nullJumps) {
			patchBranch(code, jump, code.size());
		}
		code.add(Opcode.ACONST_NULL);
		code.add(Opcode.ARETURN);
		return code;
	}

	/**
	 * The {@code _arityErr} table cell of a callee shape
	 * ({@link JvmArityOperators#shape}): {@code shape + 1} in the low seven bits and the
	 * operator index above them, or {@link #ARITY_TABLE_NONE} for a shape too wide to fit
	 * -- such a callee answers nil rather than a report. An operator index past the nine
	 * bits left drops the name, not the report.
	 */
	private static int arityTableCell(int shape) {
		int plain = (shape & ((1 << ARITY_OPERATOR_SHIFT) - 1)) + 1;
		if (plain >= ARITY_TABLE_NONE) {
			return ARITY_TABLE_NONE;
		}
		int operator = shape >>> ARITY_OPERATOR_SHIFT;
		return operator <= ARITY_TABLE_MAX_OPERATOR ? operator << ARITY_TABLE_OPERATOR_SHIFT | plain : plain;
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
	private static Case renderSpreadCase(JvmLispCompiler.FunctionInfo fi,
			@org.jspecify.annotations.Nullable String name, int fvSlot, ConstantPool cp, ClassConstant objectArrayClass,
			ArityReporting arityReporting) {
		List<Integer> code = new ArrayList<>();
		int required = fi.variadic() ? fi.paramCount() - 1 : fi.paramCount();
		// The count guard. A short list would otherwise BIND nil for the parameters it
		// does not reach and a long one would drop its tail, because the walk below is
		// car/cdr and both answer nil past the end -- neither of which is a dispatch
		// miss, so the shared no-match arm never sees it (ArityReporting). A built-in's
		// shape carries its operator index, past sipush range: that one constant is
		// pooled.
		if (arityReporting.chkRef() != null) {
			code.add(Opcode.ALOAD_1);
			int shape = java.util.Objects.requireNonNull(arityReporting.operators())
				.shape(required, fi.variadic(), name);
			if (shape > Short.MAX_VALUE) {
				emitLdc(code, cp.addInteger(shape).index());
			}
			else {
				emitIntConstStatic(code, shape);
			}
			code.add(Opcode.INVOKESTATIC);
			emitU2(code, arityReporting.chkRef().index());
		}
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
			FuncPrint funcPrint, ClassConstant ratioArrayClass, MethodrefConstant stringConcat,
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
			@org.jspecify.annotations.Nullable HashPrint hashPrint,
			@org.jspecify.annotations.Nullable ComplexPrintRefs cplx) {
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
				null, null, strEscMethod, javaPrint);
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

		// if (val instanceof RontoComplex) -> "#C(re im)" (complex-capable
		// programs only, so the travelling class stays out of every other
		// constant pool)
		patchBranch(code, ifNotRatioPos, code.size());
		if (cplx != null) {
			patchBranch(code, emitComplexToString(code, cplx, stringConcat), code.size());
		}

		// if (val instanceof Object[])
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
		// It's a function value: #<function NAME> under a name, #<lambda> without one
		emitFuncValPrint(code, funcPrint);
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
	 * value that does not start with one is a symbol and is routed to {@code _symEsc}
	 * ({@link #buildSymEscBody}, todo 626) rather than returned verbatim. For a real
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
			MethodrefConstant stringSubstring, MethodrefConstant stringReplace, MethodrefConstant stringConcat,
			MethodrefConstant symEscMethod) {
		JvmAsm a = new JvmAsm();
		int slotS = 0, slotN = 1;
		int framed = a.label();
		int symbol = a.label();
		int returnAsIs = a.label();
		int escape = a.label();
		// int n = s.length(); if (n < 2) goto symbol;
		a.aload(slotS);
		a.invokevirtual(stringLength);
		a.istore(slotN);
		a.iload(slotN);
		a.iconst(2);
		a.branch(Opcode.IF_ICMPGE, framed);
		a.branch(Opcode.GOTO, symbol);
		// if (s.charAt(0) != '"') goto symbol -- a symbol name, escaped by _symEsc
		a.bind(framed);
		a.aload(slotS);
		a.iconst(0);
		a.invokevirtual(stringCharAt);
		a.iconst('"');
		a.branch(Opcode.IF_ICMPNE, symbol);
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
		a.bind(symbol);
		a.aload(slotS);
		a.invokestatic(symEscMethod);
		a.areturn();
		return a.finish();
	}

	/**
	 * Builds {@code _symEsc(String s) -> String}: the {@code *print-escape*} = {@code t}
	 * rendering of one bare symbol name (no package qualifier, no keyword/gensym marker
	 * -- {@code _lispToDisplayString}'s princ arm strips those before this method would
	 * ever see them, and prin1's callers hand it the whole name, marker included, but the
	 * marker characters ({@code :}, {@code #}) are constituent and upcase-invariant so
	 * they never trigger escaping here on their own). Wraps the name in {@code |...|},
	 * doubling every embedded {@code |} and {@code \}, when CLHS 22.1.3.3 says the bare
	 * spelling would not read back as itself: the empty name, a name holding a character
	 * the reader would not accept inside a bare symbol token, or a name holding an ASCII
	 * lowercase letter.
	 *
	 * <p>
	 * The lowercase check is scoped to ASCII {@code a}-{@code z} rather than the
	 * interpreter's exact-Unicode {@code Character.toUpperCase(char)} fold
	 * ({@code LispSymbol.needsEscape}'s Javadoc): the general fold's range table
	 * ({@code WasmCaseFoldRuntimeBuilder}'s JVM-side counterpart does not exist) would
	 * otherwise have to be reachable from every program that prints any symbol at all. A
	 * name whose only non-constituent characters are non-ASCII lowercase letters
	 * therefore prints unescaped here -- the same known, narrow gap as the interpreter's,
	 * kept identical on purpose.
	 */
	static List<Integer> buildSymEscBody(ConstantPool cp, MethodrefConstant stringLength,
			MethodrefConstant stringCharAt, MethodrefConstant stringIndexOf, MethodrefConstant stringSubstring,
			MethodrefConstant stringReplace, MethodrefConstant stringConcat) {
		JvmAsm a = new JvmAsm();
		// locals: s = 0 (param), n = 1, prefixEnd = 2, i = 3, c = 4, idx = 5 (int),
		// tmp = 6 (String, the substring/concat scratch).
		int slotS = 0, slotN = 1, slotPrefixEnd = 2, slotI = 3, slotC = 4, slotIdx = 5, slotTmp = 6;
		int checkSharpColon = a.label();
		int scanQualifier = a.label();
		int havePrefix = a.label();
		int loopTop = a.label();
		int doEscape = a.label();
		int returnAsIs = a.label();
		int notLower = a.label();
		// n = s.length();
		a.aload(slotS);
		a.invokevirtual(stringLength);
		a.istore(slotN);
		// prefixEnd: a keyword's ':' (LispSymbol.isKeyword), else an uninterned
		// symbol's "#:" marker, else a package qualifier's colon(s)
		// (LispSymbol.qualifierEnd) -- all printed verbatim, never escaped, exactly
		// like the interpreter's LispSymbol.print(). 0 when none apply.
		a.iload(slotN);
		a.branch(Opcode.IFEQ, checkSharpColon);
		a.aload(slotS);
		a.iconst(0);
		a.invokevirtual(stringCharAt);
		a.iconst(':');
		a.branch(Opcode.IF_ICMPNE, checkSharpColon);
		a.iconst(1);
		a.istore(slotPrefixEnd);
		a.branch(Opcode.GOTO, havePrefix);
		a.bind(checkSharpColon);
		a.iload(slotN);
		a.iconst(2);
		a.branch(Opcode.IF_ICMPLT, scanQualifier);
		a.aload(slotS);
		a.iconst(0);
		a.invokevirtual(stringCharAt);
		a.iconst('#');
		a.branch(Opcode.IF_ICMPNE, scanQualifier);
		a.aload(slotS);
		a.iconst(1);
		a.invokevirtual(stringCharAt);
		a.iconst(':');
		a.branch(Opcode.IF_ICMPNE, scanQualifier);
		a.iconst(2);
		a.istore(slotPrefixEnd);
		a.branch(Opcode.GOTO, havePrefix);
		a.bind(scanQualifier);
		// idx = s.indexOf(':'); prefixEnd = (idx <= 0) ? 0
		// : (idx+1<n && s.charAt(idx+1)==':') ? idx+2 : idx+1
		a.aload(slotS);
		a.iconst(':');
		a.invokevirtual(stringIndexOf);
		a.istore(slotIdx);
		a.iload(slotIdx);
		int idxLePos = a.label();
		a.branch(Opcode.IFLE, idxLePos);
		a.iload(slotIdx);
		a.iconst(1);
		a.op(Opcode.IADD);
		a.iload(slotN);
		int notDoubleColon = a.label();
		a.branch(Opcode.IF_ICMPGE, notDoubleColon);
		a.aload(slotS);
		a.iload(slotIdx);
		a.iconst(1);
		a.op(Opcode.IADD);
		a.invokevirtual(stringCharAt);
		a.iconst(':');
		a.branch(Opcode.IF_ICMPNE, notDoubleColon);
		a.iload(slotIdx);
		a.iconst(2);
		a.op(Opcode.IADD);
		a.istore(slotPrefixEnd);
		a.branch(Opcode.GOTO, havePrefix);
		a.bind(notDoubleColon);
		a.iload(slotIdx);
		a.iconst(1);
		a.op(Opcode.IADD);
		a.istore(slotPrefixEnd);
		a.branch(Opcode.GOTO, havePrefix);
		a.bind(idxLePos);
		a.iconst(0);
		a.istore(slotPrefixEnd);
		a.bind(havePrefix);
		// needsPipes: the empty member (prefixEnd == n), or a member byte that is
		// ASCII lowercase or non-constituent (LispSymbol.needsEscape's mirror).
		a.iload(slotPrefixEnd);
		a.istore(slotI);
		a.bind(loopTop);
		a.iload(slotI);
		a.iload(slotN);
		a.branch(Opcode.IF_ICMPGE, returnAsIs);
		a.aload(slotS);
		a.iload(slotI);
		a.invokevirtual(stringCharAt);
		a.istore(slotC);
		a.iload(slotC);
		a.iconst('a');
		a.branch(Opcode.IF_ICMPLT, notLower);
		a.iload(slotC);
		a.iconst('z');
		a.branch(Opcode.IF_ICMPLE, doEscape);
		a.bind(notLower);
		// Non-constituent characters (LispSymbol.isBareConstituent's mirror): ASCII
		// whitespace, the reader's list/string/quote/comment/backquote/comma
		// terminators, and '|' / '\' themselves (reader-special even though the
		// lexer's own isSymbolChar does not exclude them).
		for (char forbidden : new char[] { ' ', '\t', '\n', '\r', '\f', '(', ')', '\'', '"', ';', ',', '`', '|',
				'\\' }) {
			a.iload(slotC);
			a.iconst(forbidden);
			a.branch(Opcode.IF_ICMPEQ, doEscape);
		}
		a.iinc(slotI, 1);
		a.branch(Opcode.GOTO, loopTop);
		a.bind(returnAsIs);
		a.aload(slotS);
		a.areturn();
		// return (prefixEnd == 0 ? "" : s.substring(0, prefixEnd)) + "|"
		// + member.replace("\\", "\\\\").replace("|", "\\|") + "|", where member is
		// s.substring(prefixEnd) -- the empty member (prefixEnd == n) needs no
		// replace calls, String.substring(n, n) already being "".
		a.bind(doEscape);
		a.aload(slotS);
		a.iload(slotPrefixEnd);
		a.iload(slotN);
		a.invokevirtual(stringSubstring);
		a.ldcString(cp.addString("\\"));
		a.ldcString(cp.addString("\\\\"));
		a.invokevirtual(stringReplace);
		a.ldcString(cp.addString("|"));
		a.ldcString(cp.addString("\\|"));
		a.invokevirtual(stringReplace);
		a.astore(slotTmp);
		a.ldcString(cp.addString("|"));
		a.aload(slotTmp);
		a.invokevirtual(stringConcat);
		a.ldcString(cp.addString("|"));
		a.invokevirtual(stringConcat);
		a.astore(slotTmp);
		a.iload(slotPrefixEnd);
		int noPrefixPos = a.label();
		a.branch(Opcode.IFEQ, noPrefixPos);
		a.aload(slotS);
		a.iconst(0);
		a.iload(slotPrefixEnd);
		a.invokevirtual(stringSubstring);
		a.aload(slotTmp);
		a.invokevirtual(stringConcat);
		a.areturn();
		a.bind(noPrefixPos);
		a.aload(slotTmp);
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

	// Emits the complex branch of _lispToString/_lispToDisplayString: if the value
	// in slot 0 is a RontoComplex, returns "#C(" + str(real) + " " + str(imag) +
	// ")", each part through the same renderer (so a ratio part prints as 1/2).
	// Returns the branch position to patch to the next type check.
	private static int emitComplexToString(List<Integer> code, ComplexPrintRefs cplx, MethodrefConstant stringConcat) {
		ClassConstant rcClass = java.util.Objects.requireNonNull(cplx.rcClass());
		FieldrefConstant rcReal = java.util.Objects.requireNonNull(cplx.rcReal());
		FieldrefConstant rcImag = java.util.Objects.requireNonNull(cplx.rcImag());
		// The presence probe first: a lone class run without the travelling file
		// falls through to the checks below without resolving the holder class
		// (.todo/757) -- exact, since no holder can exist then.
		code.add(Opcode.GETSTATIC);
		emitU2(code, java.util.Objects.requireNonNull(cplx.hasComplex()).index());
		int ifNoHolderPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, rcClass.index());
		int ifNotComplexPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, rcClass.index());
		code.add(Opcode.ASTORE_1);
		emitLdc(code, cplx.openStr().index());
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.GETFIELD);
		emitU2(code, rcReal.index());
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, cplx.selfStr().index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, stringConcat.index());
		emitLdc(code, cplx.spaceStr().index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, stringConcat.index());
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.GETFIELD);
		emitU2(code, rcImag.index());
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, cplx.selfStr().index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, stringConcat.index());
		emitLdc(code, cplx.closeStr().index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, stringConcat.index());
		code.add(Opcode.ARETURN);
		patchBranch(code, ifNoHolderPos, code.size());
		return ifNotComplexPos;
	}

	static List<Integer> buildConsToStringBody(ClassConstant objectArrayClass, ClassConstant stringBuilderClass,
			MethodrefConstant sbInitStr, MethodrefConstant sbAppendStr, MethodrefConstant sbToString,
			MethodrefConstant lispToStringMethod, ConstantPool.StringConstant openParenStr,
			ConstantPool.StringConstant closeParenStr, ConstantPool.StringConstant spaceStr,
			ConstantPool.StringConstant dotStr, ClassConstant ratioArrayClass, RenderGuardRefs guard,
			QuoteAbbrevRefs abbrev) {
		List<Integer> code = new ArrayList<>();
		// The cycle guard (the shared RenderGuardRefs discipline, kept in step by
		// JvmLispCompilerTest.compileAndRunPrintOfACyclicConsIsFinite): a chain whose
		// HEAD is already on the current rendering path -- a car reaching back to a
		// list still being rendered -- or the frame past the 256-frame depth cap
		// returns "#", the *print-level* cutoff marker. Locals here: 0 = arg, 1 = sb,
		// 2 = current (Floyd's slow first), 3 = first flag (guard scratch first),
		// 4 = cell, 5 = the chain's cycle-start cell or null, 6 = its seen flag,
		// 7 = Floyd's fast cursor, 8/9 = the quote/function abbreviation check's car/cdr
		// scratch.
		emitRenderGuardEnter(code, guard);
		// A 2-element (QUOTE x) / (FUNCTION x) cell abbreviates to 'x / #'x (CLHS
		// 22.1.3.7 permits this unconditionally; the reader already reads the
		// abbreviation back into exactly this shape) -- checked, and rendered, INSIDE
		// the guarded section already opened above, so a self-referential (QUOTE x)
		// with x reaching back to this very cell still hits the guard on the recursive
		// render of x rather than looping forever. This runs before the general list
		// loop below and shares its exit through emitRenderGuardExitAndReturn; a value
		// of any other shape falls through to that loop unchanged.
		List<Integer> notAbbrevPatches = emitQuoteAbbrevCheck(code, objectArrayClass, ratioArrayClass,
				lispToStringMethod, guard, abbrev);
		for (int patchPos : notAbbrevPatches) {
			patchBranch(code, patchPos, code.size());
		}
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
			ConstantPool.StringConstant nilStr, FuncPrint funcPrint, MethodrefConstant stringCharAt,
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
			@org.jspecify.annotations.Nullable HashPrint hashPrint,
			@org.jspecify.annotations.Nullable ComplexPrintRefs cplx) {
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
				stringClass, stringLength, stringSubstring, null, javaPrint);
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

		// if (val instanceof RontoComplex) -> "#C(re im)" (complex-capable
		// programs only)
		patchBranch(code, ifNotRatioPos, code.size());
		if (cplx != null) {
			patchBranch(code, emitComplexToString(code, cplx, stringConcat), code.size());
		}

		// if (val instanceof Object[])
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
		// Function value: the same naming the readable renderer gives
		emitFuncValPrint(code, funcPrint);
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
			ConstantPool.StringConstant dotStr, ClassConstant ratioArrayClass, RenderGuardRefs guard,
			QuoteAbbrevRefs abbrev) {
		return buildConsToStringBody(objectArrayClass, stringBuilderClass, sbInitStr, sbAppendStr, sbToString,
				lispToDisplayStringMethod, openParenStr, closeParenStr, spaceStr, dotStr, ratioArrayClass, guard,
				abbrev);
	}

	/**
	 * Builds bytecode for _append(Object a, Object b). If a is null, returns b.
	 * Otherwise, copies a's spine iteratively and patches the last cdr to b.
	 *
	 * <p>
	 * The recursive spelling allocated its result by recursing once per element, so a
	 * long first argument was a StackOverflowError rather than a slow call (.todo/749).
	 * The result is unchanged (a fresh spine, the tail shared). A first argument that is
	 * no list, or ends dotted, is {@code APPEND}'s {@code LIST} type-error over the atom
	 * the walk met ({@link JvmOperandTypeRuntime}).
	 */
	static List<Integer> buildAppendBody(ConstantPool cp, ClassConstant thisClass, ClassConstant objectArrayClass,
			ClassConstant objectClass) {
		List<Integer> code = new ArrayList<>();
		// cursor = a; head = null; tail = null
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.ASTORE);
		code.add(4);
		code.add(Opcode.ACONST_NULL);
		code.add(Opcode.ASTORE_2);
		code.add(Opcode.ACONST_NULL);
		code.add(Opcode.ASTORE);
		code.add(3);
		int loopPos = code.size();
		// if (cursor == null) goto end; if (!(cursor instanceof Object[])) goto notList
		code.add(Opcode.ALOAD);
		code.add(4);
		int endPos = code.size();
		code.add(Opcode.IFNULL);
		emitU2(code, 0);
		code.add(Opcode.ALOAD);
		code.add(4);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, objectArrayClass.index());
		int notListPos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		// fresh = new Object[]{((Object[]) cursor)[0], null}
		code.add(Opcode.ICONST_2);
		code.add(Opcode.ANEWARRAY);
		emitU2(code, objectClass.index());
		code.add(Opcode.DUP);
		code.add(Opcode.ICONST_0);
		code.add(Opcode.ALOAD);
		code.add(4);
		code.add(Opcode.CHECKCAST);
		emitU2(code, objectArrayClass.index());
		code.add(Opcode.ICONST_0);
		code.add(Opcode.AALOAD);
		code.add(Opcode.AASTORE);
		code.add(Opcode.ASTORE);
		code.add(5);
		// if (head == null) head = fresh; else ((Object[]) tail)[1] = fresh
		code.add(Opcode.ALOAD_2);
		int headNullPos = code.size();
		code.add(Opcode.IFNULL);
		emitU2(code, 0);
		code.add(Opcode.ALOAD);
		code.add(3);
		code.add(Opcode.CHECKCAST);
		emitU2(code, objectArrayClass.index());
		code.add(Opcode.ICONST_1);
		code.add(Opcode.ALOAD);
		code.add(5);
		code.add(Opcode.AASTORE);
		int tailSetPos = code.size();
		code.add(Opcode.GOTO);
		emitU2(code, 0);
		patchBranch(code, headNullPos, code.size());
		code.add(Opcode.ALOAD);
		code.add(5);
		code.add(Opcode.ASTORE_2);
		patchBranch(code, tailSetPos, code.size());
		// tail = fresh; cursor = ((Object[]) cursor)[1]; goto loop
		code.add(Opcode.ALOAD);
		code.add(5);
		code.add(Opcode.ASTORE);
		code.add(3);
		code.add(Opcode.ALOAD);
		code.add(4);
		code.add(Opcode.CHECKCAST);
		emitU2(code, objectArrayClass.index());
		code.add(Opcode.ICONST_1);
		code.add(Opcode.AALOAD);
		code.add(Opcode.ASTORE);
		code.add(4);
		int againPos = code.size();
		code.add(Opcode.GOTO);
		emitU2(code, 0);
		patchBranch(code, againPos, loopPos);
		patchBranch(code, endPos, code.size());
		// if (head == null) return b
		code.add(Opcode.ALOAD_2);
		int nullHeadPos = code.size();
		code.add(Opcode.IFNULL);
		emitU2(code, 0);
		// ((Object[]) tail)[1] = b; return head
		code.add(Opcode.ALOAD);
		code.add(3);
		code.add(Opcode.CHECKCAST);
		emitU2(code, objectArrayClass.index());
		code.add(Opcode.ICONST_1);
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.AASTORE);
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.ARETURN);
		patchBranch(code, nullHeadPos, code.size());
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ARETURN);
		// notList: throw _opTypeErr(_teRaw(cursor, "LIST"), "APPEND", "LIST")
		patchBranch(code, notListPos, code.size());
		ConstantPool.StringConstant list = cp.addString(OperandTypes.Kind.LIST.name());
		code.add(Opcode.ALOAD);
		code.add(4);
		emitLdc(code, list.index());
		code.add(Opcode.INVOKESTATIC);
		emitU2(code,
				JvmOperandTypeRuntime
					.self(cp, thisClass, JvmOperandTypeRuntime.TE_RAW, JvmOperandTypeRuntime.TE_RAW_DESC)
					.index());
		emitLdc(code, cp.addString(LispNames.APPEND).index());
		emitLdc(code, list.index());
		code.add(Opcode.INVOKESTATIC);
		emitU2(code,
				JvmOperandTypeRuntime
					.self(cp, thisClass, JvmOperandTypeRuntime.OP_TYPE_ERR, JvmOperandTypeRuntime.OP_TYPE_ERR_DESC)
					.index());
		code.add(Opcode.ATHROW);
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
	 * lisp-to-string builders only when the program uses {@code java:} interop -- which
	 * is also when an {@code ArrayList} may be a host object rather than a Lisp array, so
	 * the array branch checks for the array's header first.
	 */
	record JavaPrint(ClassConstant bigIntegerClass, MethodrefConstant objectGetClass, MethodrefConstant classGetName,
			MethodrefConstant stringConcat, ConstantPool.StringConstant prefix, ConstantPool.StringConstant suffix,
			MethodrefConstant arrayListIsEmpty, MethodrefConstant arrayListGet, ClassConstant objectArrayClass) {
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
			@org.jspecify.annotations.Nullable MethodrefConstant equalpTest,
			ConstantPool.@org.jspecify.annotations.Nullable StringConstant eqlTag,
			ConstantPool.@org.jspecify.annotations.Nullable StringConstant eqTag,
			@org.jspecify.annotations.Nullable MethodrefConstant testCode) {
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
	 * Constant-pool references for printing a closure value as the interpreter's
	 * {@code #<function NAME>} (named function) or {@code #<lambda>} (anonymous): the
	 * name comes from the {@code _funName(funcId)} table, which is empty — and the whole
	 * lookup degenerates to the constant {@code lambdaStr} — in a program that never
	 * turns a named function into a value. {@code integerClass}/{@code integerValue}
	 * unbox the closure's funcId slot; {@code stringConcat} assembles the tag around the
	 * name.
	 *
	 * @param funNameMethod the {@code _funName(int)String} lookup, null when no function
	 * can reach printing under a name
	 * @param prefix {@code "#<function "}
	 * @param suffix {@code ">"}
	 * @param lambdaStr {@code "#<lambda>"}
	 */
	record FuncPrint(@org.jspecify.annotations.Nullable MethodrefConstant funNameMethod, ClassConstant integerClass,
			MethodrefConstant integerValue, MethodrefConstant stringConcat, ConstantPool.StringConstant prefix,
			ConstantPool.StringConstant suffix, ConstantPool.StringConstant lambdaStr) {
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
	 * {@code #d(} for a {@code double[]}, {@code #f(} for a {@code float[]} or
	 * {@code #bf16(} for a {@code short[]}. Threaded into the two lisp-to-string builders
	 * only when the program uses packed float arrays.
	 */
	/**
	 * Constants for the float text of _lispToString/_lispToDisplayString: the lowercase
	 * exponent-marker rewrite every float spelling gets (the FloatText contract), and the
	 * transient Float box a packed single-float array element prints through.
	 */
	record FloatPrint(ClassConstant floatClass, MethodrefConstant floatToString, MethodrefConstant stringReplace,
			ConstantPool.StringConstant upperE, ConstantPool.StringConstant lowerE) {
	}

	/**
	 * Constant-pool references for printing a complex value as {@code #C(re im)}, each
	 * part rendered through the same renderer (so a ratio part prints as {@code 1/2},
	 * like the interpreter). Created only for a complex-capable program; every component
	 * is null together with the bundle itself.
	 */
	record ComplexPrintRefs(@org.jspecify.annotations.Nullable ClassConstant rcClass,
			@org.jspecify.annotations.Nullable FieldrefConstant rcReal,
			@org.jspecify.annotations.Nullable FieldrefConstant rcImag, MethodrefConstant selfStr,
			ConstantPool.StringConstant openStr, ConstantPool.StringConstant spaceStr,
			ConstantPool.StringConstant closeStr, @org.jspecify.annotations.Nullable FieldrefConstant hasComplex) {
	}

	record PackedPrint(ClassConstant doubleArrayClass, ClassConstant floatArrayClass, ClassConstant shortArrayClass,
			MethodrefConstant fvToGeneralMethod, MethodrefConstant stringReplaceFirst,
			ConstantPool.StringConstant prefixRegex, ConstantPool.StringConstant prefixRepl,
			ConstantPool.StringConstant prefixReplSingle, ConstantPool.StringConstant prefixReplBFloat16,
			@org.jspecify.annotations.Nullable ClassConstant byteArrayClass,
			@org.jspecify.annotations.Nullable MethodrefConstant qmToString) {
	}

	/**
	 * Constant-pool references for printing a packed integer vector (a {@code byte[]} at
	 * width 8, a {@code long[]} at 16/32, each with a width header at runtime) as a plain
	 * {@code #(...)} vector -- CL prints specialized vectors this way, so unlike the
	 * {@code #d}/{@code #f} float syntax there is no prefix rewrite: the value is boxed
	 * to a general array ({@code ivToGeneralMethod}) and rendered by the ordinary array
	 * renderer. Threaded into the two lisp-to-string builders only when the program uses
	 * packed integer vectors.
	 *
	 * @param longArrayClass the {@code [J} class constant
	 * @param byteArrayClass the {@code [B} class constant
	 * @param quantized whether a quantized matrix -- another {@code byte[]} -- can exist,
	 * so the octet test reads the tag ({@code JvmIntArrayRuntimeBuilder.OCTET_TAG})
	 * @param ivToGeneralMethod {@code _ivToGeneral}
	 */
	record PackedIntPrint(ClassConstant longArrayClass, ClassConstant byteArrayClass, boolean quantized,
			MethodrefConstant ivToGeneralMethod) {
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
	 * Constants {@link #emitQuoteAbbrevCheck} needs to recognize and spell a
	 * {@code (QUOTE x)} / {@code (FUNCTION x)} cell: the {@code String} class and its
	 * {@code equals(Object)} / (reused from {@code Object.equals}) and
	 * {@code concat(String)} methods, the two head-symbol spellings to match, and the two
	 * abbreviation marks to emit.
	 */
	record QuoteAbbrevRefs(ClassConstant stringClass, MethodrefConstant stringEquals, MethodrefConstant stringConcat,
			ConstantPool.StringConstant quoteName, ConstantPool.StringConstant functionName,
			ConstantPool.StringConstant quoteMark, ConstantPool.StringConstant functionMark) {
	}

	/**
	 * Emits the quote/function abbreviation check ahead of the general list-printing loop
	 * in {@link #buildConsToStringBody}: a cons cell {@code {car, cdr}} whose {@code car}
	 * is the String {@code "QUOTE"} / {@code "FUNCTION"} and whose {@code cdr} is itself
	 * a cons cell {@code {x, null}} (a proper 2-element list -- {@code (QUOTE A B)} still
	 * prints in full) leaves {@code "'"}/{@code "#'"} concatenated with {@code x}'s
	 * rendering on the stack, ready for {@code emitRenderGuardExitAndReturn}. Anything
	 * else falls through with an EMPTY stack to the position the caller must patch every
	 * returned branch site to (the general loop's start) -- the same "return the
	 * unpatched branch list" idiom {@link #emitConsCellCheck} uses. Locals 8 and 9 are
	 * scratch (the car and cdr/rest candidates in turn).
	 */
	private static List<Integer> emitQuoteAbbrevCheck(List<Integer> code, ClassConstant objectArrayClass,
			ClassConstant ratioArrayClass, MethodrefConstant lispToStringMethod, RenderGuardRefs guard,
			QuoteAbbrevRefs abbrev) {
		List<Integer> notAbbrev = new ArrayList<>();
		// car = ((Object[]) arg)[0]; if (!(car instanceof String)) goto notAbbrev
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, objectArrayClass.index());
		code.add(Opcode.ICONST_0);
		code.add(Opcode.AALOAD);
		code.add(Opcode.ASTORE);
		code.add(8);
		code.add(Opcode.ALOAD);
		code.add(8);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, abbrev.stringClass().index());
		notAbbrev.add(code.size());
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		// cdr = ((Object[]) arg)[1]; must be a cons cell (a non-ratio Object[])
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.CHECKCAST);
		emitU2(code, objectArrayClass.index());
		code.add(Opcode.ICONST_1);
		code.add(Opcode.AALOAD);
		code.add(Opcode.ASTORE);
		code.add(9);
		code.add(Opcode.ALOAD);
		code.add(9);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, objectArrayClass.index());
		notAbbrev.add(code.size());
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		code.add(Opcode.ALOAD);
		code.add(9);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, ratioArrayClass.index());
		notAbbrev.add(code.size());
		code.add(Opcode.IFNE);
		emitU2(code, 0);
		// rest = (Object[]) cdr; if (rest[1] != null) goto notAbbrev -- exactly one
		// element, so (QUOTE A B) is excluded and falls through to the general loop.
		code.add(Opcode.ALOAD);
		code.add(9);
		code.add(Opcode.CHECKCAST);
		emitU2(code, objectArrayClass.index());
		code.add(Opcode.ASTORE);
		code.add(9);
		code.add(Opcode.ALOAD);
		code.add(9);
		code.add(Opcode.ICONST_1);
		code.add(Opcode.AALOAD);
		notAbbrev.add(code.size());
		code.add(Opcode.IFNONNULL);
		emitU2(code, 0);
		// carStr = (String) car; tag = carStr.equals("QUOTE") ? "'"
		// : carStr.equals("FUNCTION") ? "#'" : goto notAbbrev
		code.add(Opcode.ALOAD);
		code.add(8);
		code.add(Opcode.CHECKCAST);
		emitU2(code, abbrev.stringClass().index());
		code.add(Opcode.ASTORE);
		code.add(8);
		code.add(Opcode.ALOAD);
		code.add(8);
		emitLdc(code, abbrev.quoteName().index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, abbrev.stringEquals().index());
		int isQuotePos = code.size();
		code.add(Opcode.IFNE);
		emitU2(code, 0);
		code.add(Opcode.ALOAD);
		code.add(8);
		emitLdc(code, abbrev.functionName().index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, abbrev.stringEquals().index());
		notAbbrev.add(code.size());
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		emitLdc(code, abbrev.functionMark().index());
		int gotoHaveTag = code.size();
		code.add(Opcode.GOTO);
		emitU2(code, 0);
		patchBranch(code, isQuotePos, code.size());
		emitLdc(code, abbrev.quoteMark().index());
		patchBranch(code, gotoHaveTag, code.size());
		// return tag.concat(lispToString(rest[0]))
		code.add(Opcode.ALOAD);
		code.add(9);
		code.add(Opcode.ICONST_0);
		code.add(Opcode.AALOAD);
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, lispToStringMethod.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, abbrev.stringConcat().index());
		emitRenderGuardExitAndReturn(code, guard);
		return notAbbrev;
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
	 * @param opaqueKindStr the {@code "O"} kind marker of an OPAQUE layout (the
	 * {@code %STREAM} value): it prints {@code #<NAME>} with NO slots in either escape
	 * mode -- its HANDLE slot is backend-local and must never reach the output
	 * ({@code .kb/emitted-output-determinism.md})
	 * @return the method body
	 */
	static List<Integer> buildInstToStringBody(ClassConstant objectArrayClass, ClassConstant stringArrayClass,
			ClassConstant stringBuilderClass, MethodrefConstant sbInitStr, MethodrefConstant sbAppendStr,
			MethodrefConstant sbToString, MethodrefConstant objectEquals, MethodrefConstant elementFormatter,
			ConstantPool.StringConstant structKindStr, ConstantPool.StringConstant openStructStr,
			ConstantPool.StringConstant openClassStr, ConstantPool.StringConstant closeStructStr,
			ConstantPool.StringConstant closeClassStr, ConstantPool.StringConstant keySepStr,
			ConstantPool.StringConstant spaceStr, ConstantPool.StringConstant pathnameKindStr,
			ConstantPool.@org.jspecify.annotations.Nullable StringConstant pathnamePrefixStr,
			ConstantPool.StringConstant opaqueKindStr, RenderGuardRefs guard) {
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
		// An OPAQUE layout (the %STREAM value) short-circuits to "#<NAME>" with no slots
		// in EITHER escape mode: the HANDLE slot is backend-local (a table index here, a
		// WASI fd there, a wasm linear-memory address) and must never reach the output
		// (.kb/emitted-output-determinism.md). Same text as the async #<STREAM> tag and
		// the other two backends; placed before the cycle guard, which its slot-free
		// rendering cannot need.
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.ICONST_2);
		code.add(Opcode.AALOAD);
		emitLdc(code, opaqueKindStr.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, objectEquals.index());
		int ifNotOpaquePos = code.size();
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		// return new StringBuilder("#<").append(layout[1]).append(">").toString()
		code.add(Opcode.NEW);
		emitU2(code, stringBuilderClass.index());
		code.add(Opcode.DUP);
		emitLdc(code, openClassStr.index());
		code.add(Opcode.INVOKESPECIAL);
		emitU2(code, sbInitStr.index());
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.ICONST_1);
		code.add(Opcode.AALOAD);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, sbAppendStr.index());
		emitLdc(code, closeClassStr.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, sbAppendStr.index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, sbToString.index());
		code.add(Opcode.ARETURN);
		patchBranch(code, ifNotOpaquePos, code.size());
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

	// Emits the function-value arm of the two renderers, at the position where the
	// value is known to be an Object[] whose slot 1 holds an Integer funcId (arr is in
	// local slot 1). Reads as: String name = _funName(((Integer)arr[0]).intValue());
	// return name == null ? "#<lambda>" : "#<function " + name + ">"; -- the
	// interpreter's LispLambda.print() answer, where the funcId not in the table is the
	// runtime sentinel for interpreted lambdas and prints anonymous. A funcId the table
	// answers null for is the ONLY route to "#<lambda>" beyond an empty table, so the
	// two renderers cannot drift on which closures are named. Uses local slot 2.
	private static void emitFuncValPrint(List<Integer> code, FuncPrint fp) {
		if (fp.funNameMethod() == null) {
			// No named function can reach printing: every closure value is anonymous.
			emitLdc(code, fp.lambdaStr().index());
			code.add(Opcode.ARETURN);
			return;
		}
		code.add(Opcode.ALOAD_1);
		code.add(Opcode.ICONST_0);
		code.add(Opcode.AALOAD);
		code.add(Opcode.CHECKCAST);
		emitU2(code, fp.integerClass().index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, fp.integerValue().index());
		code.add(Opcode.INVOKESTATIC);
		emitU2(code, fp.funNameMethod().index());
		code.add(Opcode.ASTORE_2);
		code.add(Opcode.ALOAD_2);
		int ifNamedPos = code.size();
		code.add(Opcode.IFNONNULL);
		emitU2(code, 0);
		emitLdc(code, fp.lambdaStr().index());
		code.add(Opcode.ARETURN);
		patchBranch(code, ifNamedPos, code.size());
		emitLdc(code, fp.prefix().index());
		code.add(Opcode.ALOAD_2);
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, fp.stringConcat().index());
		emitLdc(code, fp.suffix().index());
		code.add(Opcode.INVOKEVIRTUAL);
		emitU2(code, fp.stringConcat().index());
		code.add(Opcode.ARETURN);
	}

	/**
	 * Builds the body of {@code _funName}: a static {@code (I)Ljava/lang/String;}
	 * linear-scan table from funcId to the name it was defined under, or null for a
	 * funcId with no name (the runtime sentinel, and any funcId the gate dropped). The
	 * reverse direction of the {@code _lookup} registry, gated on the same funcId set so
	 * the two agree on which functions carry names at run time.
	 * @param entries funcId to name string constant, in ascending funcId order
	 * @return the bytecode body
	 */
	static List<Integer> buildFunNameBody(SortedMap<Integer, ConstantPool.StringConstant> entries) {
		List<Integer> code = new ArrayList<>();
		for (Map.Entry<Integer, ConstantPool.StringConstant> entry : entries.entrySet()) {
			code.add(Opcode.ILOAD_0);
			emitIntConstStatic(code, entry.getKey());
			int skip = code.size();
			code.add(Opcode.IF_ICMPNE);
			emitU2(code, 0);
			emitLdc(code, entry.getValue().index());
			code.add(Opcode.ARETURN);
			patchBranch(code, skip, code.size());
		}
		code.add(Opcode.ACONST_NULL);
		code.add(Opcode.ARETURN);
		return code;
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
		if (hashPrint.equalpTag() != null && hashPrint.equalpTest() != null && hashPrint.testCode() == null) {
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
		else if (hashPrint.testCode() != null && hashPrint.eqlTag() != null && hashPrint.eqTag() != null) {
			// The table says which of the four tests it implements, read off its test
			// code. Only a program that can build an identity table carries the
			// branch; an equalp-only program keeps the two-way shape above.
			code.add(Opcode.ALOAD_0);
			code.add(Opcode.INVOKESTATIC);
			emitU2(code, hashPrint.testCode().index());
			code.add(Opcode.ICONST_2);
			int isEql = code.size();
			code.add(Opcode.IF_ICMPEQ);
			emitU2(code, 0);
			code.add(Opcode.ALOAD_0);
			code.add(Opcode.INVOKESTATIC);
			emitU2(code, hashPrint.testCode().index());
			code.add(Opcode.ICONST_3);
			int isEq = code.size();
			code.add(Opcode.IF_ICMPEQ);
			emitU2(code, 0);
			code.add(Opcode.ALOAD_0);
			code.add(Opcode.INVOKESTATIC);
			emitU2(code, hashPrint.testCode().index());
			code.add(Opcode.ICONST_1);
			int isEqualp = code.size();
			code.add(Opcode.IF_ICMPEQ);
			emitU2(code, 0);
			emitLdc(code, hashPrint.tag().index());
			int haveTag = code.size();
			code.add(Opcode.GOTO);
			emitU2(code, 0);
			patchBranch(code, isEql, code.size());
			emitLdc(code, hashPrint.eqlTag().index());
			int haveEql = code.size();
			code.add(Opcode.GOTO);
			emitU2(code, 0);
			patchBranch(code, isEq, code.size());
			emitLdc(code, hashPrint.eqTag().index());
			int haveEq = code.size();
			code.add(Opcode.GOTO);
			emitU2(code, 0);
			patchBranch(code, isEqualp, code.size());
			if (hashPrint.equalpTag() != null) {
				emitLdc(code, hashPrint.equalpTag().index());
			}
			else {
				emitLdc(code, hashPrint.tag().index());
			}
			patchBranch(code, haveTag, code.size());
			patchBranch(code, haveEql, code.size());
			patchBranch(code, haveEq, code.size());
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

	/**
	 * Appends a two-byte operand. The high part is kept whole rather than cut to a byte:
	 * the class file only ever receives its low eight bits (so an operand that fits is
	 * written exactly as before), while a constant-pool index past 65535 -- which an
	 * unbounded pool hands out once a program outgrows one class file -- survives in the
	 * code list until {@link am.ik.jvm.JvmClassSplitter} re-points it into the pool of
	 * the class the method lands in. Every emitter's u2 goes through here or through
	 * {@code Ctx.emitU2}, which keeps the same rule.
	 * @param code the method body, one element per byte
	 * @param value the operand
	 */
	static void emitU2(List<Integer> code, int value) {
		code.add(value >> 8);
		code.add(value & 0xFF);
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
			@org.jspecify.annotations.Nullable MethodrefConstant strEscMethod,
			@org.jspecify.annotations.Nullable JavaPrint javaPrint) {
		if (arrayListClass == null || arrayToStringMethod == null) {
			return;
		}
		if (packedIntPrint != null) {
			// if (val is a packed integer vector) return
			// arrayToString(_ivToGeneral(val));
			// -- a plain #(...) vector, no prefix rewrite. Ahead of the quantized
			// matrix's byte[] test, which it tells an octet vector from by the tag.
			code.add(Opcode.ALOAD_0);
			code.add(Opcode.INSTANCEOF);
			emitU2(code, packedIntPrint.byteArrayClass().index());
			List<Integer> notOctets = new ArrayList<>();
			notOctets.add(code.size());
			code.add(Opcode.IFEQ);
			emitU2(code, 0);
			if (packedIntPrint.quantized()) {
				code.add(Opcode.ALOAD_0);
				code.add(Opcode.CHECKCAST);
				emitU2(code, packedIntPrint.byteArrayClass().index());
				code.add(Opcode.ICONST_0);
				code.add(Opcode.BALOAD);
				code.add(Opcode.BIPUSH);
				code.add(JvmIntArrayRuntimeBuilder.OCTET_TAG);
				notOctets.add(code.size());
				code.add(Opcode.IF_ICMPNE);
				emitU2(code, 0);
			}
			int isPackedIntPos = code.size();
			code.add(Opcode.GOTO);
			emitU2(code, 0);
			for (int pos : notOctets) {
				patchBranch(code, pos, code.size());
			}
			code.add(Opcode.ALOAD_0);
			code.add(Opcode.INSTANCEOF);
			emitU2(code, packedIntPrint.longArrayClass().index());
			int ifNotPackedIntPos = code.size();
			code.add(Opcode.IFEQ);
			emitU2(code, 0);
			patchBranch(code, isPackedIntPos, code.size());
			code.add(Opcode.ALOAD_0);
			code.add(Opcode.INVOKESTATIC);
			emitU2(code, packedIntPrint.ivToGeneralMethod().index());
			code.add(Opcode.INVOKESTATIC);
			emitU2(code, arrayToStringMethod.index());
			code.add(Opcode.ARETURN);
			patchBranch(code, ifNotPackedIntPos, code.size());
		}
		if (packedPrint != null) {
			// if (val instanceof double[]) -> #d(...); if (val instanceof float[]) ->
			// #f(...)
			emitPackedPrintBranch(code, packedPrint.doubleArrayClass(), arrayToStringMethod, packedPrint,
					packedPrint.prefixRepl());
			emitPackedPrintBranch(code, packedPrint.floatArrayClass(), arrayToStringMethod, packedPrint,
					packedPrint.prefixReplSingle());
			// if (val instanceof short[]) -> #bf16(...)
			emitPackedPrintBranch(code, packedPrint.shortArrayClass(), arrayToStringMethod, packedPrint,
					packedPrint.prefixReplBFloat16());
			if (packedPrint.byteArrayClass() != null && packedPrint.qmToString() != null) {
				// if (val instanceof byte[]) return _qmToString(val); -- the quantized
				// matrix's #<quantized-matrix q8-0 (rows cols)>, the same for prin1 and
				// princ (.kb/quantized-matrix.md).
				code.add(Opcode.ALOAD_0);
				code.add(Opcode.INSTANCEOF);
				emitU2(code, packedPrint.byteArrayClass().index());
				int ifNotQuantized = code.size();
				code.add(Opcode.IFEQ);
				emitU2(code, 0);
				code.add(Opcode.ALOAD_0);
				code.add(Opcode.INVOKESTATIC);
				emitU2(code, packedPrint.qmToString().index());
				code.add(Opcode.ARETURN);
				patchBranch(code, ifNotQuantized, code.size());
			}
		}
		code.add(Opcode.ALOAD_0);
		code.add(Opcode.INSTANCEOF);
		emitU2(code, arrayListClass.index());
		List<Integer> notArray = new ArrayList<>();
		notArray.add(code.size());
		code.add(Opcode.IFEQ);
		emitU2(code, 0);
		if (javaPrint != null) {
			// A java: call can answer an ArrayList of its own, which is a host object: a
			// Lisp array's slot 0 is its Object[] header (the bridge's kindOf test), and
			// anything else falls through to the #<java ...> tail.
			code.add(Opcode.ALOAD_0);
			code.add(Opcode.CHECKCAST);
			emitU2(code, arrayListClass.index());
			code.add(Opcode.INVOKEVIRTUAL);
			emitU2(code, javaPrint.arrayListIsEmpty().index());
			notArray.add(code.size());
			code.add(Opcode.IFNE);
			emitU2(code, 0);
			code.add(Opcode.ALOAD_0);
			code.add(Opcode.CHECKCAST);
			emitU2(code, arrayListClass.index());
			code.add(Opcode.ICONST_0);
			code.add(Opcode.INVOKEVIRTUAL);
			emitU2(code, javaPrint.arrayListGet().index());
			code.add(Opcode.INSTANCEOF);
			emitU2(code, javaPrint.objectArrayClass().index());
			notArray.add(code.size());
			code.add(Opcode.IFEQ);
			emitU2(code, 0);
		}
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
		for (int pos : notArray) {
			patchBranch(code, pos, code.size());
		}
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

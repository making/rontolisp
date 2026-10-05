package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.util.List;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.OperandTypes;
import org.jspecify.annotations.Nullable;

/**
 * Compiles {@code subseq} for strings and lists: {@code (subseq seq start [end])}.
 *
 * <p>
 * The public {@code subseq} operator is rewritten first through
 * {@link LispMacroExpander#expandSubseqCompat}, which routes a general array through an
 * inline {@code make-array}+{@code aref}+{@code %aset} fill and falls back to
 * {@link am.ik.rontolisp.LispNames#SUBSEQ_CORE} for the string/list branches this class
 * compiles directly (uax-15's {@code (subseq unicode-string beg end)} is the seed).
 *
 * <p>
 * For the string/list branch, the sequence type is not known statically, so a runtime
 * {@code instanceof String} test selects the arm. For a string (which carries surrounding
 * quotes), the content at {@code [start, end)} is {@code s.substring(1 + start, 1 + end)}
 * re-wrapped in quotes. For a list (an {@code Object[2]} cons chain, nil = null), the
 * elements from index {@code start} up to {@code end} are copied into a fresh cons chain.
 * An omitted {@code end} is nil and defaults to the sequence length; the lanes test the
 * nil itself, never an int sentinel, so a GIVEN negative {@code end} reaches the bounds
 * check (an int sentinel such as {@code -1} read {@code (subseq s 0 -1)} as the whole
 * sequence).
 *
 * <p>
 * The lane is ONE per-class helper, {@code _subseqCore(seq, start, end)}
 * ({@link JvmEmitHelper#emitSharedCall}), and a site is a call: a program without arrays
 * reaches it from every {@code subseq} a lowering introduced (a {@code format} directive
 * renders through several), which used to inline the whole walk at each. Both lanes check
 * {@code 0 <= start <= end <= length} and refuse a bad range with the interpreter's text
 * ({@link #emitBoundsCheck}); the list lane finds a short list during the walk it already
 * does, so a valid range costs no length walk.
 */
final class JvmSubseqCompiler {

	private JvmSubseqCompiler() {
	}

	/** The per-class helper holding the string/list lane. */
	static final String CORE = "_subseqCore";

	/** The per-class helper behind {@code %subseq-end}. */
	static final String END = "_subseqEnd";

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		LispVal rewritten = LispMacroExpander.expandSubseqCompat(cons, ctx.usesArrays,
				ctx.functions.containsKey(LispNames.SUBSEQ_RUNTIME));
		if (rewritten != null) {
			JvmExprCompiler.compileExpr(rewritten, ctx, className);
			return;
		}
		List<LispVal> args = cons.toList();
		// seq, UNNORMALIZED: a mutable character vector reads its elements directly in
		// _subseqCv (rendering it here would both cost O(source) per slice and launder
		// the mutable representation away, .todo/559). An omitted end is nil, which the
		// lane reads as "to the end" like a runtime nil, matching the interpreter's
		// (subseq seq start nil).
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		if (args.size() >= 4) {
			JvmExprCompiler.compileExpr(args.get(3), ctx, className);
		}
		else {
			ctx.body.aconst_null();
		}
		JvmEmitHelper.emitSharedCall(ctx, className, CORE, 3, helper -> emitLane(helper, className));
	}

	/**
	 * Compiles {@code (%subseq-end start end length)}: the resolved end as a fixnum, or
	 * the {@code vector} bounds report (see {@link LispNames#SUBSEQ_END}).
	 */
	static void compileEnd(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		for (int i = 1; i <= 3; i++) {
			JvmExprCompiler.compileExpr(args.get(i), ctx, className);
		}
		JvmEmitHelper.emitSharedCall(ctx, className, END, 3, helper -> {
			int startSlot = helper.allocTemp();
			int lenSlot = helper.allocTemp();
			int realEndSlot = helper.allocTemp();
			unboxIndex(helper, 0, startSlot);
			unboxIndex(helper, 2, lenSlot);
			emitResolveEnd(helper.body, helper.cp, 1, lenSlot, realEndSlot);
			emitBoundsCheck(helper.body, helper.cp, helper.subseqRecords, startSlot, realEndSlot, lenSlot, "vector");
			helper.body.iload(realEndSlot).i2l();
			JvmEmitHelper.boxLong(helper);
		});
	}

	// slot = (int) the fixnum in Object parameter `param`
	private static void unboxIndex(JvmLispCompiler.Ctx ctx, int param, int slot) {
		ctx.body.aload(param);
		JvmEmitHelper.unboxLong(ctx);
		ctx.body.l2i().istore(slot);
	}

	// slot = (int) the fixnum in Object parameter `param`, or 0 for nil. The 0 is a
	// placeholder, not a sentinel: whether end was omitted is read from the parameter
	// itself, since a caller can give any int.
	private static void unboxEnd(JvmLispCompiler.Ctx ctx, int param, int slot) {
		MethodCode.Label nil = ctx.body.newLabel();
		MethodCode.Label done = ctx.body.newLabel();
		ctx.body.aload(param);
		ctx.body.ifnull(nil);
		ctx.body.aload(param);
		JvmEmitHelper.unboxLong(ctx);
		ctx.body.l2i();
		ctx.body.goto_(done);
		ctx.body.labelBinding(nil);
		ctx.body.iconst_0();
		ctx.body.labelBinding(done);
		ctx.body.istore(slot);
	}

	// The lane's body over the helper's parameters 0 = seq, 1 = start, 2 = end (nil:
	// to the end); leaves the subsequence on the stack.
	private static void emitLane(JvmLispCompiler.Ctx ctx, String className) {
		MethodRefEntry length = JvmEmitHelper.stringMethod(ctx, "length", "()I");
		MethodRefEntry substring = JvmEmitHelper.stringMethod(ctx, "substring", "(II)Ljava/lang/String;");
		MethodRefEntry concat = JvmEmitHelper.stringMethod(ctx, "concat", "(Ljava/lang/String;)Ljava/lang/String;");
		// _cpoff(s, i): the UTF-16 code-unit index of the i-th CHARACTER inside the
		// framing quotes. Used to translate a character range (start, end) into code-unit
		// offsets so a supplementary code point in the middle is one indexed step,
		// matching (length s) after todo 153.
		MethodRefEntry cpOffset = JvmEmitHelper.selfMethod(ctx, className, JvmStringIndexRuntimeBuilder.OFFSET_METHOD,
				JvmStringIndexRuntimeBuilder.OFFSET_DESC);
		StringEntry quote = ctx.cp.stringEntry("\"");

		int seqSlot = 0;
		int startSlot = ctx.allocTemp();
		int endSlot = ctx.allocTemp();
		int sSlot = ctx.allocTemp();
		int aSlot = ctx.allocTemp();
		int bSlot = ctx.allocTemp();
		int nodeSlot = ctx.allocTemp();
		int headSlot = ctx.allocTemp();
		int tailSlot = ctx.allocTemp();
		int newSlot = ctx.allocTemp();
		int iSlot = ctx.allocTemp();
		int resultSlot = ctx.allocTemp();
		int lenSlot = ctx.allocTemp();
		int realEndSlot = ctx.allocTemp();
		unboxIndex(ctx, 1, startSlot);
		if (!ctx.usesArrays) {
			// Both arms read a given end; with the array runtime the string arm is
			// _subseqCv's, which reads it from the parameter, and only the list arm
			// unboxes it.
			unboxEnd(ctx, 2, endSlot);
		}

		MethodCode asm = ctx.body;
		MethodCode.Label listLabel = asm.newLabel();
		MethodCode.Label doneLabel = asm.newLabel();
		// result = null
		asm.aconst_null();
		asm.astore(resultSlot);
		if (ctx.usesArrays) {
			// ---- STRING PATH, mutable result (.todo/559 step 2) ----
			// _subseqCv answers a fresh MUTABLE character vector for a string in either
			// representation, so a copy-seq/subseq result has a writable identity like
			// the interpreter's. A character vector reads its elements directly (no
			// rendered string), an immutable String slices by code point.
			MethodCode.Label cvLabel = asm.newLabel();
			asm.aload(seqSlot);
			asm.instanceOf(ctx.stringClass);
			asm.ifne(cvLabel);
			asm.aload(seqSlot);
			asm.instanceOf(ctx.cp.classEntry("java/util/ArrayList"));
			asm.ifeq(listLabel);
			asm.labelBinding(cvLabel);
			asm.aload(seqSlot);
			asm.iload(startSlot);
			asm.aload(2);
			asm.invokestatic(JvmEmitHelper.selfMethod(ctx, className, JvmArrayRuntimeBuilder.SUBSEQ_CV,
					JvmArrayRuntimeBuilder.SUBSEQ_CV_DESC));
			asm.astore(resultSlot);
			asm.goto_(doneLabel);
		}
		else {
			// Without the array runtime no character vector can exist and the mutable
			// representation is unavailable, so the result stays the immutable slice.
			// if (!(seq instanceof String)) goto listLabel
			asm.aload(seqSlot);
			asm.instanceOf(ctx.stringClass);
			asm.ifeq(listLabel);

			// A subseq range on a string is a CHARACTER range: translate (start, end) to
			// code-unit offsets via s.offsetByCodePoints(1, N) so a supplementary code
			// point in the middle counts as one indexed step (matching the (length s)
			// contract).
			asm.aload(seqSlot);
			asm.checkcast(ctx.stringClass);
			asm.astore(sSlot);
			// The bounds, in characters, before any code-unit offset math.
			asm.aload(sSlot);
			asm.invokestatic(JvmEmitHelper.selfMethod(ctx, className, JvmStringIndexRuntimeBuilder.COUNT_METHOD,
					JvmStringIndexRuntimeBuilder.COUNT_DESC));
			asm.istore(lenSlot);
			emitResolveEnd(asm, 2, endSlot, lenSlot, realEndSlot);
			emitBoundsCheck(asm, ctx.cp, ctx.subseqRecords, startSlot, realEndSlot, lenSlot, "string");
			// a = _cpoff(s, start) -- the offset of character `start` past the leading
			// quote.
			asm.aload(sSlot);
			asm.iload(startSlot);
			asm.invokestatic(cpOffset);
			asm.istore(aSlot);
			// b = (end == nil) ? s.length() - 1 : _cpoff(s, end)
			MethodCode.Label haveEnd = asm.newLabel();
			MethodCode.Label gotB = asm.newLabel();
			asm.aload(2);
			asm.ifnonnull(haveEnd);
			asm.aload(sSlot);
			asm.invokevirtual(length);
			asm.loadConstant(1);
			asm.isub();
			asm.istore(bSlot);
			asm.goto_(gotB);
			asm.labelBinding(haveEnd);
			asm.aload(sSlot);
			asm.iload(realEndSlot);
			asm.invokestatic(cpOffset);
			asm.istore(bSlot);
			asm.labelBinding(gotB);
			// result = "\"" + s.substring(a, b) + "\""
			asm.ldc(quote);
			asm.aload(sSlot);
			asm.iload(aSlot);
			asm.iload(bSlot);
			asm.invokevirtual(substring);
			asm.invokevirtual(concat);
			asm.ldc(quote);
			asm.invokevirtual(concat);
			asm.astore(resultSlot);
			asm.goto_(doneLabel);
		}

		// ---- LIST PATH ----
		asm.labelBinding(listLabel);
		if (!ctx.usesArrays) {
			// No %subseq-runtime dispatch ran ahead of this lane (expandSubseqCompat
			// answers null without arrays), so a value that is neither a string nor a
			// list reaches it: SUBSEQ's SEQUENCE type-error, as the dispatch's own arm.
			MethodCode.Label isList = asm.newLabel();
			asm.aload(seqSlot);
			asm.ifnull(isList);
			asm.aload(seqSlot);
			asm.instanceOf(ctx.objectArrayClass);
			asm.ifne(isList);
			asm.aload(seqSlot);
			asm.ldc(ctx.cp.stringEntry(OperandTypes.Kind.SEQUENCE.name()));
			asm.invokestatic(JvmEmitHelper.selfMethod(ctx, className, JvmOperandTypeRuntime.TE_RAW,
					JvmOperandTypeRuntime.TE_RAW_DESC));
			asm.ldc(ctx.cp.stringEntry(LispNames.SUBSEQ));
			asm.ldc(ctx.cp.stringEntry(OperandTypes.FUNNEL_TYPE));
			asm.invokestatic(JvmEmitHelper.selfMethod(ctx, className, JvmOperandTypeRuntime.OP_TYPE_ERR,
					JvmOperandTypeRuntime.OP_TYPE_ERR_DESC));
			asm.athrow();
			asm.labelBinding(isList);
		}
		if (ctx.usesArrays) {
			unboxEnd(ctx, 2, endSlot);
		}
		// A negative start, or a start past a given end, is refused before the walk.
		MethodCode.Label listBad = asm.newLabel();
		MethodCode.Label noEndCheck = asm.newLabel();
		asm.iload(startSlot);
		asm.iflt(listBad);
		asm.aload(2);
		asm.ifnull(noEndCheck);
		asm.iload(startSlot);
		asm.iload(endSlot);
		asm.if_icmpgt(listBad);
		asm.labelBinding(noEndCheck);
		// node = seq
		asm.aload(seqSlot);
		asm.astore(nodeSlot);
		// skip the first `start` cells: i = 0; while (i < start) { if (node == null)
		// the list is shorter than start; node = cdr }
		MethodCode.Label skipLoop = asm.newLabel();
		MethodCode.Label skipDone = asm.newLabel();
		asm.loadConstant(0);
		asm.istore(iSlot);
		asm.labelBinding(skipLoop);
		asm.iload(iSlot);
		asm.iload(startSlot);
		asm.if_icmpge(skipDone);
		asm.aload(nodeSlot);
		asm.ifnull(listBad);
		asm.aload(nodeSlot);
		asm.checkcast(ctx.objectArrayClass);
		asm.loadConstant(1);
		asm.aaload();
		asm.astore(nodeSlot);
		asm.iinc(iSlot, 1);
		asm.goto_(skipLoop);
		asm.labelBinding(skipDone);
		// head = null; tail = null; i = start
		asm.aconst_null();
		asm.astore(headSlot);
		asm.aconst_null();
		asm.astore(tailSlot);
		asm.iload(startSlot);
		asm.istore(iSlot);
		MethodCode.Label buildLoop = asm.newLabel();
		MethodCode.Label buildDone = asm.newLabel();
		MethodCode.Label doBody = asm.newLabel();
		MethodCode.Label toEnd = asm.newLabel();
		asm.labelBinding(buildLoop);
		// to the end (end nil): stop at the last cell; up to end: stop at end, and a
		// list that runs out first is shorter than end
		asm.aload(2);
		asm.ifnull(toEnd);
		asm.iload(iSlot);
		asm.iload(endSlot);
		asm.if_icmpge(buildDone);
		asm.aload(nodeSlot);
		asm.ifnull(listBad);
		asm.goto_(doBody);
		asm.labelBinding(toEnd);
		asm.aload(nodeSlot);
		asm.ifnull(buildDone);
		asm.labelBinding(doBody);
		// newcons = new Object[2]; newcons[0] = node[0]; newcons[1] = null
		asm.loadConstant(2);
		asm.anewarray(ctx.objectClass);
		asm.dup();
		asm.loadConstant(0);
		asm.aload(nodeSlot);
		asm.checkcast(ctx.objectArrayClass);
		asm.loadConstant(0);
		asm.aaload();
		asm.aastore();
		asm.astore(newSlot);
		// if (head == null) { head = tail = newcons } else { tail[1] = newcons; tail =
		// newcons }
		MethodCode.Label appendTail = asm.newLabel();
		MethodCode.Label afterAppend = asm.newLabel();
		asm.aload(headSlot);
		asm.ifnonnull(appendTail);
		asm.aload(newSlot);
		asm.astore(headSlot);
		asm.aload(newSlot);
		asm.astore(tailSlot);
		asm.goto_(afterAppend);
		asm.labelBinding(appendTail);
		asm.aload(tailSlot);
		asm.checkcast(ctx.objectArrayClass);
		asm.loadConstant(1);
		asm.aload(newSlot);
		asm.aastore();
		asm.aload(newSlot);
		asm.astore(tailSlot);
		asm.labelBinding(afterAppend);
		// node = node[1]; i++
		asm.aload(nodeSlot);
		asm.checkcast(ctx.objectArrayClass);
		asm.loadConstant(1);
		asm.aaload();
		asm.astore(nodeSlot);
		asm.iinc(iSlot, 1);
		asm.goto_(buildLoop);
		asm.labelBinding(buildDone);
		asm.aload(headSlot);
		asm.astore(resultSlot);
		asm.goto_(doneLabel);

		// The refusal: only now is the whole list counted, for the report's length.
		asm.labelBinding(listBad);
		asm.loadConstant(0);
		asm.istore(lenSlot);
		asm.aload(seqSlot);
		asm.astore(nodeSlot);
		MethodCode.Label countLoop = asm.newLabel();
		MethodCode.Label countDone = asm.newLabel();
		asm.labelBinding(countLoop);
		asm.aload(nodeSlot);
		asm.instanceOf(ctx.objectArrayClass);
		asm.ifeq(countDone);
		asm.aload(nodeSlot);
		asm.checkcast(ctx.objectArrayClass);
		asm.loadConstant(1);
		asm.aaload();
		asm.astore(nodeSlot);
		asm.iinc(lenSlot, 1);
		asm.goto_(countLoop);
		asm.labelBinding(countDone);
		emitResolveEnd(asm, 2, endSlot, lenSlot, realEndSlot);
		emitBoundsError(asm, ctx.cp, ctx.subseqRecords, startSlot, realEndSlot, lenSlot, "list");

		asm.labelBinding(doneLabel);
		asm.aload(resultSlot);
	}

	/**
	 * {@code realEnd = (end == nil) ? len : (int) end}. Whether {@code end} was omitted
	 * is read from the parameter's nil, never from an int sentinel: a caller can give any
	 * int, and the {@code -1} the lanes used to pass for nil read {@code (subseq s 0 -1)}
	 * as the whole sequence instead of refusing it.
	 * @param m the method being emitted
	 * @param cp its constant pool
	 * @param endParam the {@code Object} parameter holding {@code end}, nil when omitted
	 * @param lenSlot the int length of the sequence
	 * @param realEndSlot the int local to store
	 */
	static void emitResolveEnd(MethodCode m, ConstantPool cp, int endParam, int lenSlot, int realEndSlot) {
		MethodCode.Label given = m.newLabel();
		MethodCode.Label resolved = m.newLabel();
		ClassEntry longClass = cp.classEntry("java/lang/Long");
		m.aload(endParam);
		m.ifnonnull(given);
		m.iload(lenSlot);
		m.goto_(resolved);
		m.labelBinding(given);
		m.aload(endParam);
		m.checkcast(longClass);
		m.invokevirtual(cp.methodRef(longClass, "longValue", "()J"));
		m.l2i();
		m.labelBinding(resolved);
		m.istore(realEndSlot);
	}

	// realEnd = (end == nil) ? len : end, the end already unboxed into endSlot
	// (unboxEnd)
	private static void emitResolveEnd(MethodCode m, int endParam, int endSlot, int lenSlot, int realEndSlot) {
		MethodCode.Label given = m.newLabel();
		MethodCode.Label resolved = m.newLabel();
		m.aload(endParam);
		m.ifnonnull(given);
		m.iload(lenSlot);
		m.goto_(resolved);
		m.labelBinding(given);
		m.iload(endSlot);
		m.labelBinding(resolved);
		m.istore(realEndSlot);
	}

	/**
	 * Falls through when {@code 0 <= start <= end <= len}; throws the interpreter's
	 * {@code "SUBSEQ: invalid bounds S, E for KIND of length N"} otherwise.
	 * @param m the method being emitted
	 * @param cp its constant pool
	 * @param records the class's {@code _subseqRec} builder, or null when it has no
	 * landing pad
	 * @param startSlot the int start
	 * @param endSlot the int end, already resolved (never the omitted nil)
	 * @param lenSlot the int length of the sequence
	 * @param kind the report's sequence kind ({@code string}, {@code list},
	 * {@code vector})
	 */
	static void emitBoundsCheck(MethodCode m, ConstantPool cp, JvmOperandTypeRuntime.@Nullable SubseqRecords records,
			int startSlot, int endSlot, int lenSlot, String kind) {
		MethodCode.Label ok = m.newLabel();
		MethodCode.Label bad = m.newLabel();
		emitBoundsTest(m, startSlot, endSlot, lenSlot, bad);
		m.goto_(ok);
		m.labelBinding(bad);
		emitBoundsError(m, cp, records, startSlot, endSlot, lenSlot, kind);
		m.labelBinding(ok);
	}

	// Falls through when 0 <= start <= end <= len, jumps to bad otherwise: for a method
	// whose several checks share one emitBoundsError block.
	static void emitBoundsTest(MethodCode m, int startSlot, int endSlot, int lenSlot, MethodCode.Label bad) {
		m.iload(startSlot);
		m.iflt(bad);
		m.iload(endSlot);
		m.iload(lenSlot);
		m.if_icmpgt(bad);
		m.iload(startSlot);
		m.iload(endSlot);
		m.if_icmpgt(bad);
	}

	/**
	 * Throws the bounds report, a {@code RuntimeException}: under a landing pad
	 * ({@code records} non-null) recorded by {@code _subseqRec}, so the pad catches it as
	 * the interpreter's {@code type-error} naming the refused bound; without one nothing
	 * can observe the class and the exception is thrown bare. Never falls through.
	 * @param m the method being emitted
	 * @param cp its constant pool
	 * @param records the class's {@code _subseqRec} builder, or null when it has no
	 * landing pad
	 * @param startSlot the int start
	 * @param endSlot the int end, already resolved
	 * @param lenSlot the int length of the sequence
	 * @param kind the report's sequence kind
	 */
	static void emitBoundsError(MethodCode m, ConstantPool cp, JvmOperandTypeRuntime.@Nullable SubseqRecords records,
			int startSlot, int endSlot, int lenSlot, String kind) {
		ClassEntry rtEx = cp.classEntry("java/lang/RuntimeException");
		MethodRefEntry concat = cp.methodRef("java/lang/String", "concat", "(Ljava/lang/String;)Ljava/lang/String;");
		MethodRefEntry intToStr = cp.methodRef("java/lang/String", "valueOf", "(I)Ljava/lang/String;");
		m.new_(rtEx);
		m.dup();
		m.ldc(cp.stringEntry(LispNames.SUBSEQ + ": invalid bounds "));
		m.iload(startSlot);
		m.invokestatic(intToStr);
		m.invokevirtual(concat);
		m.ldc(cp.stringEntry(", "));
		m.invokevirtual(concat);
		m.iload(endSlot);
		m.invokestatic(intToStr);
		m.invokevirtual(concat);
		m.ldc(cp.stringEntry(" for " + kind + " of length "));
		m.invokevirtual(concat);
		m.iload(lenSlot);
		m.invokestatic(intToStr);
		m.invokevirtual(concat);
		m.invokespecial(cp.methodRef(rtEx, "<init>", "(Ljava/lang/String;)V"));
		if (records != null) {
			m.iload(startSlot);
			m.iload(endSlot);
			m.iload(lenSlot);
			m.invokestatic(records.ref());
		}
		m.athrow();
	}

}

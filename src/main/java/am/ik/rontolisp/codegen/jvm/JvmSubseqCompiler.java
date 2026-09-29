package am.ik.rontolisp.codegen.jvm;

import java.lang.classfile.constantpool.MethodRefEntry;
import java.util.List;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.OperandTypes;
import am.ik.jvm.ConstantPool.StringConstant;
import am.ik.jvm.Opcode;

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
 * When {@code end} is omitted (passed as the sentinel int {@code -1}) it defaults to the
 * sequence length.
 */
final class JvmSubseqCompiler {

	private JvmSubseqCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		// The inline walk below is a loop in expression position: its head must sit at
		// operand stack depth 0, or HotSpot refuses to OSR-compile the method
		// (JvmEmitHelper.inLoopScope).
		JvmEmitHelper.inLoopScope(ctx, () -> compileLoop(cons, ctx, className));
	}

	private static void compileLoop(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		LispVal rewritten = LispMacroExpander.expandSubseqCompat(cons, ctx.usesArrays,
				ctx.functions.containsKey(LispNames.SUBSEQ_RUNTIME));
		if (rewritten != null) {
			JvmExprCompiler.compileExpr(rewritten, ctx, className);
			return;
		}
		List<LispVal> args = cons.toList();
		MethodRefEntry length = JvmEmitHelper.stringMethod(ctx, "length", "()I").methodRefEntry();
		MethodRefEntry substring = JvmEmitHelper.stringMethod(ctx, "substring", "(II)Ljava/lang/String;")
			.methodRefEntry();
		MethodRefEntry concat = JvmEmitHelper.stringMethod(ctx, "concat", "(Ljava/lang/String;)Ljava/lang/String;")
			.methodRefEntry();
		// _cpoff(s, i): the UTF-16 code-unit index of the i-th CHARACTER inside the
		// framing quotes. Used to translate a character range (start, end) into code-unit
		// offsets so a supplementary code point in the middle is one indexed step,
		// matching (length s) after todo 153.
		MethodRefEntry cpOffset = JvmEmitHelper
			.selfMethod(ctx, className, JvmStringIndexRuntimeBuilder.OFFSET_METHOD,
					JvmStringIndexRuntimeBuilder.OFFSET_DESC)
			.methodRefEntry();
		StringConstant quote = ctx.cp.addString("\"");

		int seqSlot = ctx.allocTemp();
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

		// Pre-compile the argument expressions into slots; the dispatch below follows
		// them in the same body.
		// seq = arg, UNNORMALIZED: a mutable character vector reads its elements
		// directly in _subseqCv (rendering it here would both cost O(source) per slice
		// and launder the mutable representation away, .todo/559).
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(seqSlot);
		// start = (int) arg
		JvmExprCompiler.compileExpr(args.get(2), ctx, className);
		JvmEmitHelper.unboxLong(ctx);
		ctx.emit(Opcode.L2I);
		ctx.emit(Opcode.ISTORE);
		ctx.emit(startSlot);
		// end = (int) arg, or -1 (sentinel for "to the end") when omitted; a runtime
		// nil value (e.g. an end parameter defaulting to nil) also maps to the
		// sentinel, matching the interpreter's (subseq seq start nil).
		if (args.size() >= 4 && !(args.get(3) instanceof LispNil)) {
			JvmExprCompiler.compileExpr(args.get(3), ctx, className);
			ctx.emit(Opcode.DUP);
			int ifNullPos = ctx.code.size();
			ctx.emit(Opcode.IFNULL);
			ctx.emitU2(0);
			JvmEmitHelper.unboxLong(ctx);
			ctx.emit(Opcode.L2I);
			int gotoEndPos = ctx.code.size();
			ctx.emit(Opcode.GOTO);
			ctx.emitU2(0);
			JvmEmitHelper.patchBranch(ctx, ifNullPos, ctx.code.size());
			ctx.emit(Opcode.POP);
			ctx.emit(Opcode.ICONST_M1);
			JvmEmitHelper.patchBranch(ctx, gotoEndPos, ctx.code.size());
		}
		else {
			ctx.emit(Opcode.ICONST_M1);
		}
		ctx.emit(Opcode.ISTORE);
		ctx.emit(endSlot);

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
			asm.instanceOf(ctx.stringClass.entry());
			asm.ifne(cvLabel);
			asm.aload(seqSlot);
			asm.instanceOf(ctx.cp.addClass(ctx.cp.addUtf8("java/util/ArrayList")).entry());
			asm.ifeq(listLabel);
			asm.labelBinding(cvLabel);
			asm.aload(seqSlot);
			asm.iload(startSlot);
			asm.iload(endSlot);
			asm.invokestatic(JvmEmitHelper
				.selfMethod(ctx, className, JvmArrayRuntimeBuilder.SUBSEQ_CV, JvmArrayRuntimeBuilder.SUBSEQ_CV_DESC)
				.entry());
			asm.astore(resultSlot);
			asm.goto_(doneLabel);
		}
		else {
			// Without the array runtime no character vector can exist and the mutable
			// representation is unavailable, so the result stays the immutable slice.
			// if (!(seq instanceof String)) goto listLabel
			asm.aload(seqSlot);
			asm.instanceOf(ctx.stringClass.entry());
			asm.ifeq(listLabel);

			// A subseq range on a string is a CHARACTER range: translate (start, end) to
			// code-unit offsets via s.offsetByCodePoints(1, N) so a supplementary code
			// point in the middle counts as one indexed step (matching the (length s)
			// contract).
			asm.aload(seqSlot);
			asm.checkcast(ctx.stringClass.entry());
			asm.astore(sSlot);
			// a = _cpoff(s, start) -- the offset of character `start` past the leading
			// quote.
			asm.aload(sSlot);
			asm.iload(startSlot);
			asm.invokestatic(cpOffset);
			asm.istore(aSlot);
			// b = (end < 0) ? s.length() - 1 : _cpoff(s, end)
			MethodCode.Label haveEnd = asm.newLabel();
			MethodCode.Label gotB = asm.newLabel();
			asm.iload(endSlot);
			asm.ifge(haveEnd);
			asm.aload(sSlot);
			asm.invokevirtual(length);
			asm.loadConstant(1);
			asm.isub();
			asm.istore(bSlot);
			asm.goto_(gotB);
			asm.labelBinding(haveEnd);
			asm.aload(sSlot);
			asm.iload(endSlot);
			asm.invokestatic(cpOffset);
			asm.istore(bSlot);
			asm.labelBinding(gotB);
			// result = "\"" + s.substring(a, b) + "\""
			asm.ldc(quote.entry());
			asm.aload(sSlot);
			asm.iload(aSlot);
			asm.iload(bSlot);
			asm.invokevirtual(substring);
			asm.invokevirtual(concat);
			asm.ldc(quote.entry());
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
			asm.instanceOf(ctx.objectArrayClass.entry());
			asm.ifne(isList);
			asm.aload(seqSlot);
			asm.ldc(ctx.cp.addString(OperandTypes.Kind.SEQUENCE.name()).entry());
			asm.invokestatic(JvmEmitHelper
				.selfMethod(ctx, className, JvmOperandTypeRuntime.TE_RAW, JvmOperandTypeRuntime.TE_RAW_DESC)
				.entry());
			asm.ldc(ctx.cp.addString(LispNames.SUBSEQ).entry());
			asm.ldc(ctx.cp.addString(OperandTypes.FUNNEL_TYPE).entry());
			asm.invokestatic(JvmEmitHelper
				.selfMethod(ctx, className, JvmOperandTypeRuntime.OP_TYPE_ERR, JvmOperandTypeRuntime.OP_TYPE_ERR_DESC)
				.entry());
			asm.athrow();
			asm.labelBinding(isList);
		}
		// node = seq
		asm.aload(seqSlot);
		asm.astore(nodeSlot);
		// skip the first `start` cells: i = 0; while (i < start && node != null) cdr
		MethodCode.Label skipLoop = asm.newLabel();
		MethodCode.Label skipDone = asm.newLabel();
		asm.loadConstant(0);
		asm.istore(iSlot);
		asm.labelBinding(skipLoop);
		asm.iload(iSlot);
		asm.iload(startSlot);
		asm.if_icmpge(skipDone);
		asm.aload(nodeSlot);
		asm.ifnull(skipDone);
		asm.aload(nodeSlot);
		asm.checkcast(ctx.objectArrayClass.entry());
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
		asm.labelBinding(buildLoop);
		// while node != null
		asm.aload(nodeSlot);
		asm.ifnull(buildDone);
		// and (end < 0 || i < end)
		asm.iload(endSlot);
		asm.iflt(doBody);
		asm.iload(iSlot);
		asm.iload(endSlot);
		asm.if_icmpge(buildDone);
		asm.labelBinding(doBody);
		// newcons = new Object[2]; newcons[0] = node[0]; newcons[1] = null
		asm.loadConstant(2);
		asm.anewarray(ctx.objectClass.entry());
		asm.dup();
		asm.loadConstant(0);
		asm.aload(nodeSlot);
		asm.checkcast(ctx.objectArrayClass.entry());
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
		asm.checkcast(ctx.objectArrayClass.entry());
		asm.loadConstant(1);
		asm.aload(newSlot);
		asm.aastore();
		asm.aload(newSlot);
		asm.astore(tailSlot);
		asm.labelBinding(afterAppend);
		// node = node[1]; i++
		asm.aload(nodeSlot);
		asm.checkcast(ctx.objectArrayClass.entry());
		asm.loadConstant(1);
		asm.aaload();
		asm.astore(nodeSlot);
		asm.iinc(iSlot, 1);
		asm.goto_(buildLoop);
		asm.labelBinding(buildDone);
		asm.aload(headSlot);
		asm.astore(resultSlot);

		asm.labelBinding(doneLabel);
		asm.aload(resultSlot);
	}

}

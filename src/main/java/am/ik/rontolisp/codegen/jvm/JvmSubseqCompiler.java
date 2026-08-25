package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.ConstantPool.StringConstant;
import am.ik.jvm.Opcode;
import am.ik.jvm.OperandStack;

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
		int length = JvmEmitHelper.stringMethod(ctx, "length", "()I").index();
		int substring = JvmEmitHelper.stringMethod(ctx, "substring", "(II)Ljava/lang/String;").index();
		int concat = JvmEmitHelper.stringMethod(ctx, "concat", "(Ljava/lang/String;)Ljava/lang/String;").index();
		// _cpoff(s, i): the UTF-16 code-unit index of the i-th CHARACTER inside the
		// framing quotes. Used to translate a character range (start, end) into code-unit
		// offsets so a supplementary code point in the middle is one indexed step,
		// matching (length s) after todo 153.
		int cpOffset = JvmEmitHelper
			.selfMethod(ctx, className, JvmStringIndexRuntimeBuilder.OFFSET_METHOD,
					JvmStringIndexRuntimeBuilder.OFFSET_DESC)
			.index();
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

		// Pre-compile the argument expressions into slots (compileExpr writes to
		// ctx.code; the dispatch assembly below is self-contained and appended after).
		// seq = arg (a mutable character vector normalizes to a string first)
		JvmExprCompiler.compileExpr(args.get(1), ctx, className);
		JvmArrayCompiler.emitStrvNormalize(ctx, className);
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

		JvmAsm asm = new JvmAsm();
		int listLabel = asm.label();
		int doneLabel = asm.label();
		// result = null
		asm.aconstNull();
		asm.astore(resultSlot);
		// if (!(seq instanceof String)) goto listLabel
		asm.aload(seqSlot);
		asm.instanceOf(ctx.stringClass);
		asm.branch(Opcode.IFEQ, listLabel);

		// ---- STRING PATH ----
		// A subseq range on a string is a CHARACTER range: translate (start, end) to
		// code-unit offsets via s.offsetByCodePoints(1, N) so a supplementary code point
		// in the middle counts as one indexed step (matching the (length s) contract).
		asm.aload(seqSlot);
		asm.checkcast(ctx.stringClass);
		asm.astore(sSlot);
		// a = _cpoff(s, start) -- the offset of character `start` past the leading quote.
		asm.aload(sSlot);
		asm.iload(startSlot);
		asm.op(Opcode.INVOKESTATIC);
		asm.u2(cpOffset);
		asm.istore(aSlot);
		// b = (end < 0) ? s.length() - 1 : _cpoff(s, end)
		int haveEnd = asm.label();
		int gotB = asm.label();
		asm.iload(endSlot);
		asm.branch(Opcode.IFGE, haveEnd);
		asm.aload(sSlot);
		asm.op(Opcode.INVOKEVIRTUAL);
		asm.u2(length);
		asm.iconst(1);
		asm.op(Opcode.ISUB);
		asm.istore(bSlot);
		asm.branch(Opcode.GOTO, gotB);
		asm.bind(haveEnd);
		asm.aload(sSlot);
		asm.iload(endSlot);
		asm.op(Opcode.INVOKESTATIC);
		asm.u2(cpOffset);
		asm.istore(bSlot);
		asm.bind(gotB);
		// result = "\"" + s.substring(a, b) + "\""
		asm.ldcString(quote);
		asm.aload(sSlot);
		asm.iload(aSlot);
		asm.iload(bSlot);
		asm.op(Opcode.INVOKEVIRTUAL);
		asm.u2(substring);
		asm.op(Opcode.INVOKEVIRTUAL);
		asm.u2(concat);
		asm.ldcString(quote);
		asm.op(Opcode.INVOKEVIRTUAL);
		asm.u2(concat);
		asm.astore(resultSlot);
		asm.branch(Opcode.GOTO, doneLabel);

		// ---- LIST PATH ----
		asm.bind(listLabel);
		// node = seq
		asm.aload(seqSlot);
		asm.astore(nodeSlot);
		// skip the first `start` cells: i = 0; while (i < start && node != null) cdr
		int skipLoop = asm.label();
		int skipDone = asm.label();
		asm.iconst(0);
		asm.istore(iSlot);
		asm.bind(skipLoop);
		asm.iload(iSlot);
		asm.iload(startSlot);
		asm.branch(Opcode.IF_ICMPGE, skipDone);
		asm.aload(nodeSlot);
		asm.branch(Opcode.IFNULL, skipDone);
		asm.aload(nodeSlot);
		asm.checkcast(ctx.objectArrayClass);
		asm.iconst(1);
		asm.aaload();
		asm.astore(nodeSlot);
		asm.iinc(iSlot, 1);
		asm.branch(Opcode.GOTO, skipLoop);
		asm.bind(skipDone);
		// head = null; tail = null; i = start
		asm.aconstNull();
		asm.astore(headSlot);
		asm.aconstNull();
		asm.astore(tailSlot);
		asm.iload(startSlot);
		asm.istore(iSlot);
		int buildLoop = asm.label();
		int buildDone = asm.label();
		int doBody = asm.label();
		asm.bind(buildLoop);
		// while node != null
		asm.aload(nodeSlot);
		asm.branch(Opcode.IFNULL, buildDone);
		// and (end < 0 || i < end)
		asm.iload(endSlot);
		asm.branch(Opcode.IFLT, doBody);
		asm.iload(iSlot);
		asm.iload(endSlot);
		asm.branch(Opcode.IF_ICMPGE, buildDone);
		asm.bind(doBody);
		// newcons = new Object[2]; newcons[0] = node[0]; newcons[1] = null
		asm.iconst(2);
		asm.anewarray(ctx.objectClass);
		asm.dup();
		asm.iconst(0);
		asm.aload(nodeSlot);
		asm.checkcast(ctx.objectArrayClass);
		asm.iconst(0);
		asm.aaload();
		asm.aastore();
		asm.astore(newSlot);
		// if (head == null) { head = tail = newcons } else { tail[1] = newcons; tail =
		// newcons }
		int appendTail = asm.label();
		int afterAppend = asm.label();
		asm.aload(headSlot);
		asm.branch(Opcode.IFNONNULL, appendTail);
		asm.aload(newSlot);
		asm.astore(headSlot);
		asm.aload(newSlot);
		asm.astore(tailSlot);
		asm.branch(Opcode.GOTO, afterAppend);
		asm.bind(appendTail);
		asm.aload(tailSlot);
		asm.checkcast(ctx.objectArrayClass);
		asm.iconst(1);
		asm.aload(newSlot);
		asm.aastore();
		asm.aload(newSlot);
		asm.astore(tailSlot);
		asm.bind(afterAppend);
		// node = node[1]; i++
		asm.aload(nodeSlot);
		asm.checkcast(ctx.objectArrayClass);
		asm.iconst(1);
		asm.aaload();
		asm.astore(nodeSlot);
		asm.iinc(iSlot, 1);
		asm.branch(Opcode.GOTO, buildLoop);
		asm.bind(buildDone);
		asm.aload(headSlot);
		asm.astore(resultSlot);

		asm.bind(doneLabel);
		asm.aload(resultSlot);
		ctx.emitBlock(asm.finish(), OperandStack.Slot.REF);
	}

}

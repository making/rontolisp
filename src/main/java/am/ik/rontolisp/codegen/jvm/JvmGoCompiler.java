package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code go} special form: an unconditional jump to a label of the innermost
 * lexically enclosing {@code tagbody} that declares the tag (see
 * {@link JvmTagbodyCompiler}). A backward jump is patched immediately; a forward jump is
 * recorded on the tagbody scope and patched when the label is emitted. Like
 * {@code return}, the jump discards the operands of any expression it abandons
 * mid-evaluation and compiles the cleanup forms of every {@code unwind-protect} scope it
 * escapes (the scopes entered after the tagbody), innermost first, with the same
 * hole-recording so a throw from an inlined cleanup does not re-enter its own handler.
 */
final class JvmGoCompiler {

	private JvmGoCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2 || !(parts.get(1) instanceof LispSymbol)) {
			throw new IllegalArgumentException(LispNames.GO + " expects a tag: " + cons.print());
		}
		String tag = JvmTagbodyCompiler.labelName(parts.get(1));
		JvmLispCompiler.TagbodyScope scope = null;
		for (JvmLispCompiler.TagbodyScope s : ctx.tagbodyScopes) {
			if (s.pendingGos().containsKey(tag)) {
				scope = s;
				break;
			}
		}
		if (scope == null) {
			// The interpreter's dynamic go can cross a function boundary (a go inside
			// an flet local targeting the enclosing function's tagbody -- cl-ppcre's
			// charset rehash); the compilers cannot, so the jump becomes a cold-path
			// runtime signal and the library still compiles.
			JvmExprCompiler.compileExpr(new LispCons(new LispSymbol(LispNames.ERROR),
					new LispCons(new am.ik.rontolisp.LispString(LispNames.GO + " tag " + tag
							+ " has no lexically enclosing tagbody: the compilers support go within the same function only"),
							am.ik.rontolisp.LispNil.INSTANCE)),
					ctx, className);
			return;
		}
		compileEscapedCleanups(ctx, className, scope);
		emitStackUnwind(ctx, scope);
		int gotoPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		Integer labelPos = scope.labelPositions().get(tag);
		if (labelPos != null) {
			JvmEmitHelper.patchBranch(ctx, gotoPos, labelPos);
		}
		else {
			scope.pendingGos().computeIfAbsent(tag, k -> new ArrayList<>()).add(gotoPos);
		}
	}

	/**
	 * Brings the operand stack to the shape every label of the target tagbody is reached
	 * with -- the stack at tagbody entry. Mirrors {@code JvmReturnCompiler}: operands the
	 * abandoned expression pushed are discarded, and when the jump escapes a
	 * {@code handler-case} spill (entered after the tagbody), the tagbody's own operands
	 * are reloaded from the outermost escaped spill instead.
	 */
	private static void emitStackUnwind(JvmLispCompiler.Ctx ctx, JvmLispCompiler.TagbodyScope scope) {
		int exitDepth = scope.entryStack().size();
		JvmLispCompiler.SpillScope escaped = null;
		int escapedCount = ctx.spillScopes.size() - scope.spillDepth();
		int i = 0;
		for (JvmLispCompiler.SpillScope s : ctx.spillScopes) {
			if (i++ >= escapedCount) {
				break;
			}
			escaped = s;
		}
		if (escaped == null) {
			ctx.discardOperandsDownTo(exitDepth);
			return;
		}
		ctx.discardOperandsDownTo(0);
		escaped.spill().restore(ctx, exitDepth);
	}

	/**
	 * Compiles the cleanup forms of every {@code unwind-protect} scope this {@code go}
	 * escapes -- the scopes entered after the tagbody, innermost first -- recording the
	 * inlined sequence as a hole in each escaped scope from its own cleanup onward, the
	 * same bookkeeping as {@code JvmReturnCompiler}.
	 */
	private static void compileEscapedCleanups(JvmLispCompiler.Ctx ctx, String className,
			JvmLispCompiler.TagbodyScope scope) {
		List<JvmLispCompiler.UnwindScope> escaped = new ArrayList<>();
		int escapedCount = ctx.unwindScopes.size() - scope.unwindDepth();
		int i = 0;
		for (JvmLispCompiler.UnwindScope unwindScope : ctx.unwindScopes) {
			if (i++ >= escapedCount) {
				break;
			}
			escaped.add(unwindScope);
		}
		if (escaped.isEmpty()) {
			return;
		}
		int[] holeStarts = new int[escaped.size()];
		for (int j = 0; j < escaped.size(); j++) {
			holeStarts[j] = ctx.code.size();
			JvmUnwindProtectCompiler.compileCleanups(escaped.get(j).cleanupForms, ctx, className);
		}
		int sequenceEnd = ctx.code.size();
		for (int j = 0; j < escaped.size(); j++) {
			escaped.get(j).holes.add(new int[] { holeStarts[j], sequenceEnd });
		}
	}

}

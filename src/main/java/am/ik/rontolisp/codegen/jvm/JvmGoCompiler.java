package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.jvm.MethodCode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the {@code go} special form: an unconditional jump to a label of the innermost
 * lexically enclosing {@code tagbody} that declares the tag (see
 * {@link JvmTagbodyCompiler}), a {@code goto} to the tag's label on the tagbody scope --
 * resolved at once when the label is already bound, when it is bound otherwise. Like
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
		// A tag is a symbol or an integer (CLHS 5.3) -- labelName keys both the way the
		// tagbody scopes do.
		String tag = (parts.size() == 2) ? JvmTagbodyCompiler.labelName(parts.get(1)) : null;
		if (tag == null) {
			throw new IllegalArgumentException(LispNames.GO + " expects a tag: " + cons.print());
		}
		JvmLispCompiler.TagbodyScope scope = null;
		for (JvmLispCompiler.TagbodyScope s : ctx.tagbodyScopes) {
			if (s.labels().containsKey(tag)) {
				scope = s;
				break;
			}
		}
		if (scope == null) {
			// No lexically visible tagbody declares the tag. A go crossing a nested
			// lambda/flet into an ENCLOSING one never reaches here -- the shared
			// CrossLambdaExitLowering rewrote it into a %nlx-throw the tagbody's
			// re-entry loop catches. What is left is the interpreter's DYNAMIC go into
			// a caller's tagbody, which the compilers cannot express, so the jump
			// becomes a cold-path runtime signal and the library still compiles.
			JvmExprCompiler.compileExpr(new LispCons(new LispSymbol(LispNames.ERROR),
					new LispCons(new am.ik.rontolisp.LispString(LispNames.GO + " tag " + tag
							+ " has no lexically enclosing tagbody: the compilers support go within the same function only"),
							am.ik.rontolisp.LispNil.INSTANCE)),
					ctx, className);
			return;
		}
		compileEscapedCleanups(ctx, className, scope);
		emitStackUnwind(ctx, scope);
		ctx.body.goto_(java.util.Objects.requireNonNull(scope.labels().get(tag)));
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
		MethodCode.Label[] holeStarts = new MethodCode.Label[escaped.size()];
		for (int j = 0; j < escaped.size(); j++) {
			holeStarts[j] = ctx.body.newBoundLabel();
			JvmUnwindProtectCompiler.compileCleanups(escaped.get(j).cleanupForms, ctx, className);
		}
		MethodCode.Label sequenceEnd = ctx.body.newBoundLabel();
		for (int j = 0; j < escaped.size(); j++) {
			escaped.get(j).holes.add(new JvmLispCompiler.Hole(holeStarts[j], sequenceEnd));
		}
	}

}

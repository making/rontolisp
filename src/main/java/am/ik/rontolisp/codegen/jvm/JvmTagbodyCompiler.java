package am.ik.rontolisp.codegen.jvm;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.jvm.MethodCode;
import am.ik.jvm.OperandStack;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import org.jspecify.annotations.Nullable;

/**
 * Compiles the {@code tagbody} special form. Body atoms (symbols or integers) are labels;
 * other forms are compiled for effect in order. A {@code go} (see {@link JvmGoCompiler})
 * jumps to a label of the innermost lexically enclosing tagbody that declares its tag.
 * One whose tag belongs to a tagbody OUTSIDE the nested lambda it sits in is rewritten by
 * {@code compiler/CrossLambdaExitLowering} into a throw the tagbody's generated re-entry
 * loop catches, before this compiler ever sees it ({@code .kb/do-return-block.md}); only
 * the interpreter's dynamic {@code go} into a CALLER's tagbody is out of reach.
 *
 * <p>
 * Every label is a join point reached with the operand stack the tagbody was entered with
 * (a {@code go} discards whatever the abandoned expression had pushed on top of it), so
 * each label position declares that shape to the operand-stack model: a backward
 * {@code go}'s target must already have a fixed shape, and a label whose predecessors are
 * all {@code go}s would otherwise be modeled unreachable. A forward {@code go} is
 * resolved when its label is bound; falling off the end yields nil.
 *
 * <p>
 * That entry stack is spilled to locals FIRST, so every label -- and therefore the target
 * of every backward {@code go} -- sits at operand stack depth 0. This is the
 * {@code loop}/{@code do}/{@code dotimes}/{@code dolist} family's whole loop shape, and
 * HotSpot can only enter an on-stack-replacement compilation at a backedge whose operand
 * stack is empty: a loop head under the enclosing expression's pending operands is
 * refused at every tier ({@code COMPILE SKIPPED: stack not empty at OSR entry point}),
 * and a top-level form or a {@code defun} called once is entered once, so OSR is the only
 * route into it. Written in argument position -- {@code (setq s (+ s (loop ...)))} -- an
 * unspilled tagbody would run in the bytecode interpreter forever. The operands are
 * reloaded under the tagbody's nil result on the way out.
 */
final class JvmTagbodyCompiler {

	private JvmTagbodyCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		Map<String, MethodCode.Label> labels = new LinkedHashMap<>();
		for (int i = 1; i < parts.size(); i++) {
			String label = labelName(parts.get(i));
			if (label != null) {
				labels.put(label, ctx.body.newLabel());
			}
		}
		// Entered BEFORE the TagbodyScope records its spill depth, so a go to one of this
		// tagbody's own labels does not treat the spill as escaped -- those labels are at
		// depth 0 now. A return/go leaving for an ENCLOSING block does escape it, and
		// reloads that block's operands from there.
		JvmLispCompiler.Ctx.Spill spill = JvmEmitHelper.enterLoopScope(ctx);
		List<OperandStack.Slot> entryStack = ctx.stack.snapshot();
		JvmLispCompiler.TagbodyScope scope = new JvmLispCompiler.TagbodyScope(entryStack, ctx.unwindScopes.size(),
				ctx.spillScopes.size(), labels);
		Set<String> bound = new HashSet<>();
		ctx.tagbodyScopes.push(scope);
		for (int i = 1; i < parts.size(); i++) {
			LispVal part = parts.get(i);
			String label = labelName(part);
			if (label != null) {
				ctx.stack.joinShape(entryStack);
				if (!bound.add(label)) {
					// A tag standing twice: a go past its second place jumps there.
					labels.put(label, ctx.body.newLabel());
				}
				ctx.body.labelBinding(labels.get(label));
			}
			else {
				JvmExprCompiler.compileForEffect(part, ctx, className);
			}
		}
		ctx.tagbodyScopes.pop();
		JvmEmitHelper.leaveLoopScope(ctx, spill);
		ctx.body.aconst_null();
	}

	/** The label name of a tagbody body atom, or null when the element is a form. */
	static @Nullable String labelName(LispVal part) {
		if (part instanceof LispSymbol sym) {
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
			return qn == null ? sym.name() : qn.member();
		}
		if (part instanceof LispInteger n) {
			return Long.toString(n.value());
		}
		return null;
	}

}

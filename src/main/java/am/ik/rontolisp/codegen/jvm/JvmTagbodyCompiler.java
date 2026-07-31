package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.jvm.Opcode;
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
 * all {@code go}s would otherwise be modeled unreachable. Forward {@code go}s are
 * back-patched when their label is emitted; falling off the end yields nil.
 */
final class JvmTagbodyCompiler {

	private JvmTagbodyCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		Map<String, Integer> labelPositions = new LinkedHashMap<>();
		Map<String, List<Integer>> pendingGos = new LinkedHashMap<>();
		for (int i = 1; i < parts.size(); i++) {
			String label = labelName(parts.get(i));
			if (label != null) {
				pendingGos.put(label, new ArrayList<>());
			}
		}
		List<OperandStack.Slot> entryStack = ctx.stack.snapshot();
		JvmLispCompiler.TagbodyScope scope = new JvmLispCompiler.TagbodyScope(entryStack, ctx.unwindScopes.size(),
				ctx.spillScopes.size(), labelPositions, pendingGos);
		ctx.tagbodyScopes.push(scope);
		for (int i = 1; i < parts.size(); i++) {
			LispVal part = parts.get(i);
			String label = labelName(part);
			if (label != null) {
				int pos = ctx.code.size();
				labelPositions.put(label, pos);
				ctx.stack.joinShape(entryStack);
				List<Integer> pending = pendingGos.computeIfAbsent(label, k -> new ArrayList<>());
				for (int patchPos : pending) {
					JvmEmitHelper.patchBranch(ctx, patchPos, pos);
				}
				pending.clear();
			}
			else {
				JvmExprCompiler.compileExpr(part, ctx, className);
				ctx.emit(Opcode.POP);
			}
		}
		ctx.tagbodyScopes.pop();
		ctx.emit(Opcode.ACONST_NULL);
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

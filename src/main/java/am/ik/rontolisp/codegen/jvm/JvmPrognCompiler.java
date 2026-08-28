package am.ik.rontolisp.codegen.jvm;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;
import org.jspecify.annotations.Nullable;

/**
 * Compiles the {@code progn} special form.
 */
final class JvmPrognCompiler {

	private JvmPrognCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		compile(cons, ctx, className, null);
	}

	/**
	 * @param tail the tail spine this {@code progn} ends, or null. When it has one, the
	 * body forms JOIN the spine instead of being emitted by the loop below -- the same
	 * emission, item by item, with a split point between any two of them
	 * ({@link JvmBodyOutliner}).
	 */
	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className, JvmBodyOutliner.@Nullable Tail tail) {
		List<LispVal> parts = cons.toList();
		if (parts.size() == 1) {
			// (progn) is nil; a value must be pushed even with no body forms.
			ctx.emit(Opcode.ACONST_NULL);
			return;
		}
		if (tail != null) {
			List<JvmBodyOutliner.Item> items = new ArrayList<>();
			for (int i = 1; i < parts.size() - 1; i++) {
				items.add(new JvmBodyOutliner.EffectForm(parts.get(i)));
			}
			items.add(new JvmBodyOutliner.ValueForm(parts.get(parts.size() - 1)));
			tail.pushFront(items);
			return;
		}
		for (int i = 1; i < parts.size() - 1; i++) {
			JvmExprCompiler.compileForEffect(parts.get(i), ctx, className);
		}
		JvmExprCompiler.compileExpr(parts.get(parts.size() - 1), ctx, className);
	}

}

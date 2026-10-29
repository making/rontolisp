package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;

/**
 * The three primitives of a Clojure program's run-time lowering on the JVM
 * ({@link JvmLinkage}):
 * <ul>
 * <li>{@code (rontolisp::%host-lower-at-run-time verb args meta refuse ns)} -- the host
 * class's hand-off of a lowering verb to the compiler it carries
 * ({@link JvmLinkage#LOWER_METHOD});</li>
 * <li>{@code (rontolisp::%run-time-answer form)} -- in a fragment, the form whose value
 * the verb answers, stored in {@link JvmLinkage#ANSWER_FIELD} on the way through;</li>
 * <li>{@code (rontolisp::%run-time-constant i)} -- in a fragment, the {@code i}th value
 * its forms embed ({@link JvmLinkage#CONSTANTS_FIELD}).</li>
 * </ul>
 */
final class JvmRunTimeLinkCompiler {

	private JvmRunTimeLinkCompiler() {
	}

	static boolean handles(String member) {
		return LispNames.HOST_LOWER_AT_RUN_TIME_INTERNAL.equals(member)
				|| LispNames.RUN_TIME_ANSWER_INTERNAL.equals(member)
				|| LispNames.RUN_TIME_CONSTANT_INTERNAL.equals(member);
	}

	static void compile(String member, LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> items = cons.toList();
		if (LispNames.HOST_LOWER_AT_RUN_TIME_INTERNAL.equals(member)) {
			requireArgs(member, items, 5);
			for (int i = 1; i <= 5; i++) {
				JvmExprCompiler.compileExpr(items.get(i), ctx, className);
			}
			ctx.body.invokestatic(
					ctx.cp.methodRef(ctx.cp.classEntry(className), JvmLinkage.LOWER_METHOD, JvmLinkage.LOWER_DESC));
			return;
		}
		if (LispNames.RUN_TIME_ANSWER_INTERNAL.equals(member)) {
			requireArgs(member, items, 1);
			JvmExprCompiler.compileExpr(items.get(1), ctx, className);
			ctx.body.dup();
			ctx.body.putstatic(
					ctx.cp.fieldRef(ctx.cp.classEntry(className), JvmLinkage.ANSWER_FIELD, "Ljava/lang/Object;"));
			return;
		}
		requireArgs(member, items, 1);
		if (!(items.get(1) instanceof LispInteger index)) {
			throw new UnsupportedOperationException("%run-time-constant takes a literal index, got " + items.get(1));
		}
		ctx.body.getstatic(
				ctx.cp.fieldRef(ctx.cp.classEntry(className), JvmLinkage.CONSTANTS_FIELD, "[Ljava/lang/Object;"));
		JvmEmitHelper.emitIntConst(ctx, (int) index.value());
		ctx.body.aaload();
	}

	private static void requireArgs(String member, List<LispVal> items, int count) {
		if (items.size() != count + 1) {
			throw new UnsupportedOperationException(member.toLowerCase(java.util.Locale.ROOT) + " expects " + count
					+ " argument" + (count == 1 ? "" : "s") + ", got " + (items.size() - 1));
		}
	}

}

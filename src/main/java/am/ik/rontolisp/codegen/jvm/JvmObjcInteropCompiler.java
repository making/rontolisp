package am.ik.rontolisp.codegen.jvm;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;

/**
 * Compiles the seven {@code objc:} verbs ({@code objc:class}, {@code objc:send},
 * {@code objc:define-class}, {@code objc:on-main}, {@code objc:string},
 * {@code objc:address}, {@code objc:objectp}). Each call site first invokes the emitted
 * {@code _objcInit} helper (which lazily defines the embedded {@code am.ik.objc} blob and
 * the {@link JvmObjcTemplate bridge}, see {@link JvmObjcRuntimeBuilder}), then evaluates
 * the arguments -- the leading fixed arguments as-is, {@code objc:send}'s variadic tail
 * packed into an {@code Object[]} -- and calls the matching bridge entry point.
 * Marshalling, ownership and every run-time validation live in the bridge, so compiled
 * behavior matches the interpreter's {@code eval/ObjcBridge}.
 */
final class JvmObjcInteropCompiler {

	private JvmObjcInteropCompiler() {
	}

	/** Every member of the {@code objc} package, as the compiler gates on them. */
	static List<String> members() {
		return List.of(LispNames.OBJC_CLASS, LispNames.OBJC_SEND, LispNames.OBJC_DEFINE_CLASS, LispNames.OBJC_ON_MAIN,
				LispNames.OBJC_STRING, LispNames.OBJC_ADDRESS, LispNames.OBJC_OBJECTP);
	}

	/**
	 * Returns whether the given {@code objc} package member is one of the seven verbs.
	 */
	static boolean handles(String member) {
		return members().contains(member);
	}

	static void compile(String member, LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		Map<String, MethodrefConstant> ops = ctx.objcOps;
		if (ops == null) {
			throw new IllegalStateException("objc runtime was not emitted");
		}
		List<LispVal> args = cons.toList();
		// Make sure the blob is defined before a bridge method reference resolves.
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(Objects.requireNonNull(ops.get("init")).index());
		switch (member) {
			case LispNames.OBJC_SEND -> {
				requireArity(args.size() >= 3, "objc:send expects (objc:send receiver \"selector\" args...)");
				JvmExprCompiler.compileExpr(args.get(1), ctx, className);
				JvmExprCompiler.compileExpr(args.get(2), ctx, className);
				compileRestArray(args, 3, ctx, className);
				emitBridgeCall(ctx, ops, "send");
			}
			case LispNames.OBJC_DEFINE_CLASS -> {
				requireArity(args.size() == 4 || args.size() == 5,
						"objc:define-class expects (objc:define-class \"Name\" \"Superclass\" methods [protocols])");
				JvmExprCompiler.compileExpr(args.get(1), ctx, className);
				JvmExprCompiler.compileExpr(args.get(2), ctx, className);
				JvmExprCompiler.compileExpr(args.get(3), ctx, className);
				if (args.size() == 5) {
					JvmExprCompiler.compileExpr(args.get(4), ctx, className);
				}
				else {
					ctx.emit(Opcode.ACONST_NULL);
				}
				emitBridgeCall(ctx, ops, "define-class");
			}
			case LispNames.OBJC_CLASS, LispNames.OBJC_ON_MAIN, LispNames.OBJC_STRING, LispNames.OBJC_ADDRESS,
					LispNames.OBJC_OBJECTP -> {
				String spelled = "objc:" + member.toLowerCase(java.util.Locale.ROOT);
				requireArity(args.size() == 2, spelled + " expects 1 argument, got " + (args.size() - 1));
				JvmExprCompiler.compileExpr(args.get(1), ctx, className);
				emitBridgeCall(ctx, ops, member.toLowerCase(java.util.Locale.ROOT));
			}
			default -> throw new UnsupportedOperationException("Cannot compile: objc:" + member);
		}
	}

	private static void requireArity(boolean ok, String message) {
		if (!ok) {
			throw new UnsupportedOperationException(message);
		}
	}

	/** Evaluates {@code args[from..]} into a fresh {@code Object[]} left on the stack. */
	private static void compileRestArray(List<LispVal> args, int from, JvmLispCompiler.Ctx ctx, String className) {
		JvmEmitHelper.emitIntConst(ctx, args.size() - from);
		ctx.emit(Opcode.ANEWARRAY);
		ctx.emitU2(ctx.objectClass.index());
		for (int i = from; i < args.size(); i++) {
			ctx.emit(Opcode.DUP);
			JvmEmitHelper.emitIntConst(ctx, i - from);
			JvmExprCompiler.compileExpr(args.get(i), ctx, className);
			ctx.emit(Opcode.AASTORE);
		}
	}

	private static void emitBridgeCall(JvmLispCompiler.Ctx ctx, Map<String, MethodrefConstant> ops, String key) {
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(Objects.requireNonNull(ops.get(key)).index());
	}

}

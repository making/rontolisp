package am.ik.rontolisp.codegen.jvm;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;

import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code ffi:} verbs ({@code ffi:open}, {@code ffi:symbol},
 * {@code ffi:call}, {@code ffi:%apply-call}, {@code ffi:callback}, {@code ffi:alloc},
 * {@code ffi:free}, {@code ffi:peek}, {@code ffi:poke}, {@code ffi:size},
 * {@code ffi:align}, {@code ffi:pointerp}, {@code ffi:address}, {@code ffi:errno}). Each
 * call site first invokes the emitted {@code _ffiInit} helper (which lazily defines the
 * embedded {@code am.ik.ffi} blob and the {@link JvmFfiTemplate bridge}, see
 * {@link JvmFfiRuntimeBuilder}), then evaluates the arguments -- the leading fixed
 * arguments as-is, {@code ffi:call}'s variadic tail packed into an {@code Object[]}, a
 * missing optional as the compiled {@code null} -- and calls the matching bridge entry
 * point. Marshalling, type parsing and every run-time validation live in the bridge, so
 * compiled behavior matches the interpreter's {@code eval/FfiBridge}.
 */
final class JvmFfiInteropCompiler {

	private JvmFfiInteropCompiler() {
	}

	/** Every member of the {@code ffi} package, as the compiler gates on them. */
	static List<String> members() {
		return List.of(LispNames.FFI_OPEN, LispNames.FFI_SYMBOL, LispNames.FFI_CALL, LispNames.FFI_APPLY_CALL,
				LispNames.FFI_CALLBACK, LispNames.FFI_ALLOC, LispNames.FFI_FREE, LispNames.FFI_PEEK, LispNames.FFI_POKE,
				LispNames.FFI_SIZE, LispNames.FFI_ALIGN, LispNames.FFI_POINTERP, LispNames.FFI_ADDRESS,
				LispNames.FFI_ERRNO);
	}

	/** Returns whether the given {@code ffi} package member is one of the verbs. */
	static boolean handles(String member) {
		return members().contains(member);
	}

	static void compile(String member, LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		Map<String, MethodrefConstant> ops = ctx.ffiOps;
		if (ops == null) {
			throw new IllegalStateException("ffi runtime was not emitted");
		}
		List<LispVal> args = cons.toList();
		String spelled = "ffi:" + member.toLowerCase(Locale.ROOT);
		// Make sure the blob is defined before a bridge method reference resolves.
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(Objects.requireNonNull(ops.get("init")).index());
		switch (member) {
			case LispNames.FFI_OPEN -> {
				requireArity(args.size() <= 2, "ffi:open expects at most 1 argument, got " + (args.size() - 1));
				compileFixedOrNull(args, 1, 1, ctx, className);
				emitBridgeCall(ctx, ops, "open");
			}
			case LispNames.FFI_SYMBOL -> {
				requireArity(args.size() == 3, "ffi:symbol expects (ffi:symbol library \"name\")");
				JvmExprCompiler.compileExpr(args.get(1), ctx, className);
				JvmExprCompiler.compileExpr(args.get(2), ctx, className);
				emitBridgeCall(ctx, ops, "symbol");
			}
			case LispNames.FFI_CALL -> {
				requireArity(args.size() >= 4,
						"ffi:call expects (ffi:call function return-type argument-types args...)");
				JvmExprCompiler.compileExpr(args.get(1), ctx, className);
				JvmExprCompiler.compileExpr(args.get(2), ctx, className);
				JvmExprCompiler.compileExpr(args.get(3), ctx, className);
				compileRestArray(args, 4, ctx, className);
				emitBridgeCall(ctx, ops, "call");
			}
			case LispNames.FFI_APPLY_CALL -> {
				requireArity(args.size() == 5,
						"ffi:%apply-call expects (ffi:%apply-call function return-type argument-types args)");
				compileFixedOrNull(args, 1, 4, ctx, className);
				emitBridgeCall(ctx, ops, "%apply-call");
			}
			case LispNames.FFI_POKE -> {
				requireArity(args.size() == 4 || args.size() == 5,
						"ffi:poke expects (ffi:poke pointer type value [offset])");
				compileFixedOrNull(args, 1, 4, ctx, className);
				emitBridgeCall(ctx, ops, "poke");
			}
			case LispNames.FFI_CALLBACK -> {
				requireArity(args.size() == 4,
						"ffi:callback expects (ffi:callback function return-type argument-types)");
				compileFixedOrNull(args, 1, 3, ctx, className);
				emitBridgeCall(ctx, ops, "callback");
			}
			case LispNames.FFI_PEEK -> {
				requireArity(args.size() == 3 || args.size() == 4, "ffi:peek expects (ffi:peek pointer type [offset])");
				compileFixedOrNull(args, 1, 3, ctx, className);
				emitBridgeCall(ctx, ops, "peek");
			}
			case LispNames.FFI_ERRNO -> {
				requireArity(args.size() == 1, "ffi:errno expects no arguments, got " + (args.size() - 1));
				emitBridgeCall(ctx, ops, "errno");
			}
			case LispNames.FFI_ALLOC, LispNames.FFI_FREE, LispNames.FFI_SIZE, LispNames.FFI_ALIGN,
					LispNames.FFI_POINTERP, LispNames.FFI_ADDRESS -> {
				requireArity(args.size() == 2, spelled + " expects 1 argument, got " + (args.size() - 1));
				JvmExprCompiler.compileExpr(args.get(1), ctx, className);
				emitBridgeCall(ctx, ops, member.toLowerCase(Locale.ROOT));
			}
			default -> throw new UnsupportedOperationException("Cannot compile: " + spelled);
		}
	}

	private static void requireArity(boolean ok, String message) {
		if (!ok) {
			throw new UnsupportedOperationException(message);
		}
	}

	/**
	 * Evaluates {@code args[from..from+count-1]}, emitting the compiled {@code null}
	 * (nil) for each trailing optional the form did not carry.
	 */
	private static void compileFixedOrNull(List<LispVal> args, int from, int count, JvmLispCompiler.Ctx ctx,
			String className) {
		for (int i = from; i < from + count; i++) {
			if (i < args.size()) {
				JvmExprCompiler.compileExpr(args.get(i), ctx, className);
			}
			else {
				ctx.emit(Opcode.ACONST_NULL);
			}
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

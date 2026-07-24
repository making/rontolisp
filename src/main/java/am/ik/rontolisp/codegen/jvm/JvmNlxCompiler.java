package am.ik.rontolisp.codegen.jvm;

import java.util.List;
import java.util.Objects;

import am.ik.jvm.ByteCodeWriter;
import am.ik.jvm.ConstantPool;
import am.ik.jvm.Opcode;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispVal;

/**
 * Compiles the three internal non-local-exit primitives the compile-path
 * {@code CrossLambdaExitLowering} injects to make a {@code return-from} exit a function
 * from inside a nested lambda (a separately compiled method a lexical goto cannot cross):
 *
 * <ul>
 * <li>{@code (%nlx-tag)} mints a fresh {@code new Object()} -- the dynamic block-instance
 * id. A {@code let} binds one per establishing block activation, and the existing closure
 * machinery captures it into the lambda, so recursion targets the right frame.</li>
 * <li>{@code (%nlx-throw id value)} publishes {@code {throwable, id, value}} on the
 * per-thread {@code _nleTl} channel and {@code athrow}s a plain {@code RuntimeException};
 * the real stack unwind runs every intervening
 * {@code unwind-protect}/{@code handler-case} handler.</li>
 * <li>{@code (%nlx-catch id body...)} runs {@code body} inside a catch-any region; when
 * the caught throwable is the pending NLE whose id matches {@code id}, its value becomes
 * the form's value, otherwise it is rethrown (a real condition, or an outer block's
 * exit).</li>
 * </ul>
 *
 * The {@code _nleTl} triple keys on the throwable's identity
 * ({@code triple[0] == caught}) so a stale channel (an unwind-protect cleanup that itself
 * threw a new exception) never misfires. {@link JvmHandlerCaseCompiler} rethrows a
 * matching NLE before dispatching, so a cross-lambda exit is never swallowed as a
 * synthesized condition.
 */
final class JvmNlxCompiler {

	private JvmNlxCompiler() {
	}

	/** {@code (%nlx-tag)} -- a fresh unique identity object. */
	static void compileTag(JvmLispCompiler.Ctx ctx) {
		ConstantPool.MethodrefConstant objectCtor = ctx.cp.addMethodref(ctx.objectClass,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("<init>"), ctx.cp.addUtf8("()V")));
		ctx.emit(Opcode.NEW);
		ctx.emitU2(ctx.objectClass.index());
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.INVOKESPECIAL);
		ctx.emitU2(objectCtor.index());
	}

	/** {@code (%nlx-throw id value)} -- throw a non-local exit carrying (id, value). */
	static void compileThrow(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 3) {
			throw new IllegalArgumentException(LispNames.NLX_THROW_INTERNAL + " expects (%nlx-throw id value)");
		}
		JvmLispCompiler.ConditionChannel channel = ctx.conditionChannel;
		channel.ensureNle(ctx.cp, className);
		ConstantPool.ClassConstant runtimeEx = ctx.cp.addClass(ctx.cp.addUtf8("java/lang/RuntimeException"));
		ConstantPool.MethodrefConstant exCtor = ctx.cp.addMethodref(runtimeEx,
				ctx.cp.addNameAndType(ctx.cp.addUtf8("<init>"), ctx.cp.addUtf8("()V")));
		int savedNextLocal = ctx.nextLocal;
		int exSlot = ctx.allocTemp();
		// ex = new RuntimeException()
		ctx.emit(Opcode.NEW);
		ctx.emitU2(runtimeEx.index());
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.INVOKESPECIAL);
		ctx.emitU2(exCtor.index());
		ctx.emit(Opcode.ASTORE);
		ctx.emit(exSlot);
		// _nleTl.set(new Object[]{ex, id, value})
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(Objects.requireNonNull(channel.nleTlField).index());
		ctx.emit(Opcode.ICONST_3);
		ctx.emit(Opcode.ANEWARRAY);
		ctx.emitU2(ctx.objectClass.index());
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(exSlot);
		ctx.emit(Opcode.AASTORE);
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_1);
		JvmExprCompiler.compileExpr(parts.get(1), ctx, className);
		ctx.emit(Opcode.AASTORE);
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_2);
		JvmExprCompiler.compileExpr(parts.get(2), ctx, className);
		ctx.emit(Opcode.AASTORE);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(Objects.requireNonNull(channel.tlSet).index());
		// throw ex
		ctx.emit(Opcode.ALOAD);
		ctx.emit(exSlot);
		ctx.emit(Opcode.ATHROW);
		ctx.nextLocal = savedNextLocal;
	}

	/**
	 * {@code (%nlx-catch id body...)} -- catch a matching non-local exit; else rethrow.
	 */
	static void compileCatch(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> parts = cons.toList();
		if (parts.size() < 2) {
			throw new IllegalArgumentException(LispNames.NLX_CATCH_INTERNAL + " expects (%nlx-catch id body...)");
		}
		LispVal idForm = parts.get(1);
		JvmLispCompiler.ConditionChannel channel = ctx.conditionChannel;
		channel.ensureNle(ctx.cp, className);
		ConstantPool.ClassConstant objectArrayClass = ctx.objectArrayClass;
		int savedNextLocal = ctx.nextLocal;
		// Entering the handler discards the operand stack, so enclosing operands are
		// spilled and reloaded past the merge (a statement-position catch spills nothing
		// and stays byte-neutral relative to a bare block).
		JvmLispCompiler.Ctx.Spill spill = ctx.spillOperandStack();
		int resultSlot = ctx.allocTemp();
		int excSlot = ctx.allocTemp();
		int arrSlot = ctx.allocTemp();
		if (!spill.live().isEmpty()) {
			ctx.spillScopes.push(new JvmLispCompiler.SpillScope(spill, ctx.blockTargets.size()));
		}
		int start = ctx.code.size();
		// Body as an implicit progn.
		if (parts.size() <= 2) {
			ctx.emit(Opcode.ACONST_NULL);
		}
		else {
			for (int i = 2; i < parts.size(); i++) {
				if (i > 2) {
					ctx.emit(Opcode.POP);
				}
				JvmExprCompiler.compileExpr(parts.get(i), ctx, className);
			}
		}
		int end = ctx.code.size();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(resultSlot);
		int gotoDonePos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		// Handler: read the pending NLE; deliver on an id match, else rethrow.
		int handler = ctx.code.size();
		ctx.stack.enterHandler();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(excSlot);
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(Objects.requireNonNull(channel.nleTlField).index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(Objects.requireNonNull(channel.tlGet).index());
		ctx.emit(Opcode.ASTORE);
		ctx.emit(arrSlot);
		// if (_nleTl == null) rethrow
		ctx.emit(Opcode.ALOAD);
		ctx.emit(arrSlot);
		int ifNullPos = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0);
		// if (triple[0] != caught) rethrow -- identity guards against a stale channel
		emitArrayElement(ctx, arrSlot, objectArrayClass, 0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(excSlot);
		int ifNotSameExPos = ctx.code.size();
		ctx.emit(Opcode.IF_ACMPNE);
		ctx.emitU2(0);
		// if (triple[1] != id) rethrow -- an outer block's exit
		emitArrayElement(ctx, arrSlot, objectArrayClass, 1);
		JvmExprCompiler.compileExpr(idForm, ctx, className);
		int ifNotSameIdPos = ctx.code.size();
		ctx.emit(Opcode.IF_ACMPNE);
		ctx.emitU2(0);
		// Matched: clear the channel, deliver triple[2].
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(Objects.requireNonNull(channel.nleTlField).index());
		ctx.emit(Opcode.ACONST_NULL);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(Objects.requireNonNull(channel.tlSet).index());
		emitArrayElement(ctx, arrSlot, objectArrayClass, 2);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(resultSlot);
		int gotoDone2Pos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		// Rethrow: a real condition, a stale channel, or an outer block's exit.
		int rethrow = ctx.code.size();
		JvmEmitHelper.patchBranch(ctx, ifNullPos, rethrow);
		JvmEmitHelper.patchBranch(ctx, ifNotSameExPos, rethrow);
		JvmEmitHelper.patchBranch(ctx, ifNotSameIdPos, rethrow);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(excSlot);
		ctx.emit(Opcode.ATHROW);
		// Done.
		int done = ctx.code.size();
		JvmEmitHelper.patchBranch(ctx, gotoDonePos, done);
		JvmEmitHelper.patchBranch(ctx, gotoDone2Pos, done);
		if (!spill.live().isEmpty()) {
			ctx.spillScopes.pop();
			spill.restore(ctx);
		}
		ctx.emit(Opcode.ALOAD);
		ctx.emit(resultSlot);
		ctx.exceptionTable.add(new ByteCodeWriter.ExceptionTableEntry(start, end, handler, 0));
		ctx.nextLocal = savedNextLocal;
	}

	/** Loads {@code ((Object[]) arrSlot)[index]} onto the stack. */
	private static void emitArrayElement(JvmLispCompiler.Ctx ctx, int arrSlot, ConstantPool.ClassConstant arrayClass,
			int index) {
		ctx.emit(Opcode.ALOAD);
		ctx.emit(arrSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(arrayClass.index());
		switch (index) {
			case 0 -> ctx.emit(Opcode.ICONST_0);
			case 1 -> ctx.emit(Opcode.ICONST_1);
			default -> ctx.emit(Opcode.ICONST_2);
		}
		ctx.emit(Opcode.AALOAD);
	}

}

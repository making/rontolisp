package am.ik.rontolisp.codegen.jvm;

import am.ik.jvm.ConstantPool;
import am.ik.jvm.Opcode;

/**
 * Shared helper methods for JVM bytecode emission used across all expression compilers.
 */
final class JvmEmitHelper {

	private JvmEmitHelper() {
	}

	static void compileLong(long value, JvmLispCompiler.Ctx ctx) {
		if (value == 0) {
			ctx.emit(Opcode.LCONST_0);
		}
		else if (value == 1) {
			ctx.emit(Opcode.LCONST_1);
		}
		else {
			ConstantPool.LongConstant lc = ctx.cp.addLong(value);
			ctx.emit(Opcode.LDC2_W);
			ctx.emitU2(lc.index());
		}
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.longValueOf.index());
	}

	static void compileDouble(double value, JvmLispCompiler.Ctx ctx) {
		if (value == 0.0) {
			ctx.emit(Opcode.DCONST_0);
		}
		else if (value == 1.0) {
			ctx.emit(Opcode.DCONST_1);
		}
		else {
			ConstantPool.DoubleConstant dc = ctx.cp.addDouble(value);
			ctx.emit(Opcode.LDC2_W);
			ctx.emitU2(dc.index());
		}
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.doubleValueOf.index());
	}

	static void compileStringLiteral(String value, JvmLispCompiler.Ctx ctx) {
		ConstantPool.StringConstant sc = ctx.cp.addString(value);
		if (sc.index() <= 255) {
			ctx.emit(Opcode.LDC);
			ctx.emit(sc.index());
		}
		else {
			ctx.emit(Opcode.LDC_W);
			ctx.emitU2(sc.index());
		}
	}

	static void unboxLong(JvmLispCompiler.Ctx ctx) {
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.longClass.index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(ctx.longValue.index());
	}

	static void boxLong(JvmLispCompiler.Ctx ctx) {
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.longValueOf.index());
	}

	static void unboxDouble(JvmLispCompiler.Ctx ctx) {
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.numberClass.index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(ctx.numberDoubleValue.index());
	}

	static void boxDouble(JvmLispCompiler.Ctx ctx) {
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.doubleValueOf.index());
	}

	static void emitIntConst(JvmLispCompiler.Ctx ctx, int value) {
		if (value >= 0 && value <= 5) {
			ctx.emit(Opcode.ICONST_0 + value);
		}
		else if (value >= -128 && value <= 127) {
			ctx.emit(Opcode.BIPUSH);
			ctx.emit(value & 0xFF);
		}
		else {
			ctx.emit(Opcode.SIPUSH);
			ctx.emitU2(value);
		}
	}

	/**
	 * Converts an i32 (0=false, non-0=true) on the JVM stack into a Lisp boolean
	 * (null=nil or Long(1)=t).
	 */
	static void emitBoolFromInt(JvmLispCompiler.Ctx ctx) {
		int ifPos = ctx.code.size();
		ctx.emit(Opcode.IFNE);
		ctx.emitU2(0);
		ctx.emit(Opcode.ACONST_NULL);
		int gotoEndPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0);
		patchBranch(ctx, ifPos, ctx.code.size());
		compileLong(1, ctx);
		patchBranch(ctx, gotoEndPos, ctx.code.size());
	}

	static void patchBranch(JvmLispCompiler.Ctx ctx, int branchPos, int targetPos) {
		JvmRuntimeBuilder.patchBranch(ctx.code, branchPos, targetPos);
	}

	/**
	 * Boxes a local variable in an Object[1] cell for capture-by-reference.
	 */
	static void emitBoxLocal(JvmLispCompiler.Ctx ctx, int slot) {
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.ANEWARRAY);
		ctx.emitU2(ctx.objectClass.index());
		ctx.emit(Opcode.DUP);
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(slot);
		ctx.emit(Opcode.AASTORE);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(slot);
	}

}

package am.ik.femtolisp.codegen.jvm;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import am.ik.femtolisp.LispCons;
import am.ik.femtolisp.LispInteger;
import am.ik.femtolisp.LispNil;
import am.ik.femtolisp.LispString;
import am.ik.femtolisp.LispSymbol;
import am.ik.femtolisp.LispTrue;
import am.ik.femtolisp.LispVal;
import am.ik.femtolisp.compiler.LispCompiler;
import org.jspecify.annotations.Nullable;

import am.ik.jvm.AccessFlag;
import am.ik.jvm.ByteCodeWriter;
import am.ik.jvm.ConstantPool;
import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.FieldrefConstant;
import am.ik.jvm.ConstantPool.MethodrefConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;
import am.ik.jvm.Opcode;

/**
 * Compiles Lisp expressions to JVM .class bytecode. Uses class file version 50 (Java 6)
 * to avoid mandatory StackMapTable.
 */
public final class JvmLispCompiler implements LispCompiler {

	private final String className;

	public JvmLispCompiler(String className) {
		this.className = className;
	}

	@Override
	public byte[] compile(List<LispVal> program) {
		ConstantPool cp = new ConstantPool();
		ClassConstant thisClass = cp.addClass(cp.addUtf8(this.className));
		ClassConstant objectClass = cp.addClass(cp.addUtf8("java/lang/Object"));

		ClassConstant systemClass = cp.addClass(cp.addUtf8("java/lang/System"));
		FieldrefConstant systemOut = cp.addFieldref(systemClass,
				cp.addNameAndType(cp.addUtf8("out"), cp.addUtf8("Ljava/io/PrintStream;")));
		ClassConstant printStreamClass = cp.addClass(cp.addUtf8("java/io/PrintStream"));
		MethodrefConstant printlnObj = cp.addMethodref(printStreamClass,
				cp.addNameAndType(cp.addUtf8("println"), cp.addUtf8("(Ljava/lang/Object;)V")));

		ClassConstant longClass = cp.addClass(cp.addUtf8("java/lang/Long"));
		MethodrefConstant longValueOf = cp.addMethodref(longClass,
				cp.addNameAndType(cp.addUtf8("valueOf"), cp.addUtf8("(J)Ljava/lang/Long;")));
		MethodrefConstant longValue = cp.addMethodref(longClass,
				cp.addNameAndType(cp.addUtf8("longValue"), cp.addUtf8("()J")));

		Ctx ctx = new Ctx(cp, systemOut, printlnObj, longClass, longValueOf, longValue);

		for (LispVal expr : program) {
			compileExpr(expr, ctx);
			ctx.emit(Opcode.POP);
		}
		ctx.emit(Opcode.RETURN);

		Utf8Constant mainUtf8 = cp.addUtf8("main");
		Utf8Constant mainDesc = cp.addUtf8("([Ljava/lang/String;)V");
		Utf8Constant codeUtf8 = cp.addUtf8("Code");

		ByteArrayOutputStream classOut = new ByteArrayOutputStream();
		new ByteCodeWriter(classOut) //
			.write(0xCA, 0xFE, 0xBA, 0xBE) //
			.writeVersion(0, 50) // Java 6: StackMapTable not mandatory
			.writeConstantPool(cp) //
			.writeClass(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_SUPER, thisClass, objectClass) //
			.writeInterfaces(i -> {
			})
			.writeFields(f -> {
			})
			.writeMethods(methods -> methods.add(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, mainUtf8, mainDesc,
					method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
						attr.writeU2(ctx.maxStack) // max_stack
							.writeU2(ctx.maxLocals) // max_locals
							.writeCode((Object[]) ctx.code.toArray(new Integer[0]))
							.writeU2(0) // exception_table_length
							.writeU2(0); // attributes_count (no StackMapTable)
					})))) //
			.writeAttributes(a -> {
			});
		return classOut.toByteArray();
	}

	private void compileExpr(LispVal expr, Ctx ctx) {
		switch (expr) {
			case LispInteger i -> compileLong(i.value(), ctx);
			case LispNil ignored -> ctx.emit(Opcode.ACONST_NULL);
			case LispTrue ignored -> compileLong(1, ctx);
			case LispSymbol sym -> compileSymbolRef(sym, ctx);
			case LispCons cons -> compileCons(cons, ctx);
			default -> throw new UnsupportedOperationException("Cannot compile: " + expr.print());
		}
	}

	private void compileLong(long value, Ctx ctx) {
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

	private void compileSymbolRef(LispSymbol sym, Ctx ctx) {
		Integer slot = ctx.locals.get(sym.name());
		if (slot != null) {
			ctx.emit(Opcode.ALOAD);
			ctx.emit(slot);
		}
		else {
			throw new UnsupportedOperationException("Cannot compile symbol reference: " + sym.name());
		}
	}

	private void compileCons(LispCons cons, Ctx ctx) {
		LispVal head = cons.car();
		if (head instanceof LispSymbol sym) {
			switch (sym.name()) {
				case "+" -> compileArith(cons, ctx, Opcode.LADD);
				case "-" -> compileArith(cons, ctx, Opcode.LSUB);
				case "*" -> compileArith(cons, ctx, Opcode.LMUL);
				case "/" -> compileArith(cons, ctx, Opcode.LDIV);
				case "mod" -> compileArith(cons, ctx, Opcode.LREM);
				case "print" -> compilePrint(cons, ctx);
				case "if" -> compileIf(cons, ctx);
				case "let" -> compileLet(cons, ctx);
				case "progn" -> compileProgn(cons, ctx);
				default -> throw new UnsupportedOperationException("Cannot compile: " + sym.name());
			}
		}
		else {
			throw new UnsupportedOperationException("Cannot compile: " + cons.print());
		}
	}

	private void compileArith(LispCons cons, Ctx ctx, int opcode) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx);
		unboxLong(ctx);
		for (int i = 2; i < args.size(); i++) {
			compileExpr(args.get(i), ctx);
			unboxLong(ctx);
			ctx.emit(opcode);
		}
		boxLong(ctx);
	}

	private void compilePrint(LispCons cons, Ctx ctx) {
		List<LispVal> args = cons.toList();
		ctx.emit(Opcode.GETSTATIC);
		ctx.emitU2(ctx.systemOut.index());
		compileExpr(args.get(1), ctx);
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(ctx.printlnObj.index());
		ctx.emit(Opcode.ACONST_NULL);
	}

	private void compileIf(LispCons cons, Ctx ctx) {
		List<LispVal> parts = cons.toList();
		// Compile condition
		compileExpr(parts.get(1), ctx);
		// null = nil = false
		int ifNullPos = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0); // placeholder

		// Then branch
		compileExpr(parts.get(2), ctx);
		int gotoEndPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0); // placeholder

		// Else branch
		int elseStart = ctx.code.size();
		patchBranch(ctx, ifNullPos, elseStart);
		if (parts.size() > 3) {
			compileExpr(parts.get(3), ctx);
		}
		else {
			ctx.emit(Opcode.ACONST_NULL);
		}

		// End
		int endPos = ctx.code.size();
		patchBranch(ctx, gotoEndPos, endPos);
	}

	private void compileLet(LispCons cons, Ctx ctx) {
		List<LispVal> parts = cons.toList();
		LispVal bindings = parts.get(1);
		Map<String, Integer> savedLocals = new HashMap<>(ctx.locals);
		int savedNextLocal = ctx.nextLocal;
		if (bindings instanceof LispCons bindingsCons) {
			for (LispVal binding : bindingsCons.toList()) {
				LispCons pair = (LispCons) binding;
				List<LispVal> pairList = pair.toList();
				String name = ((LispSymbol) pairList.get(0)).name();
				compileExpr(pairList.get(1), ctx);
				int slot = ctx.allocLocal(name);
				ctx.emit(Opcode.ASTORE);
				ctx.emit(slot);
			}
		}
		for (int i = 2; i < parts.size(); i++) {
			if (i > 2) {
				ctx.emit(Opcode.POP);
			}
			compileExpr(parts.get(i), ctx);
		}
		ctx.locals = savedLocals;
		ctx.nextLocal = savedNextLocal;
	}

	private void compileProgn(LispCons cons, Ctx ctx) {
		List<LispVal> parts = cons.toList();
		for (int i = 1; i < parts.size(); i++) {
			if (i > 1) {
				ctx.emit(Opcode.POP);
			}
			compileExpr(parts.get(i), ctx);
		}
	}

	private void unboxLong(Ctx ctx) {
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.longClass.index());
		ctx.emit(Opcode.INVOKEVIRTUAL);
		ctx.emitU2(ctx.longValue.index());
	}

	private void boxLong(Ctx ctx) {
		ctx.emit(Opcode.INVOKESTATIC);
		ctx.emitU2(ctx.longValueOf.index());
	}

	private void patchBranch(Ctx ctx, int branchPos, int targetPos) {
		int offset = targetPos - branchPos;
		byte[] bytes = ByteBuffer.allocate(2).putShort((short) offset).array();
		ctx.code.set(branchPos + 1, (int) bytes[0]);
		ctx.code.set(branchPos + 2, (int) bytes[1]);
	}

	private static final class Ctx {

		final ConstantPool cp;

		final FieldrefConstant systemOut;

		final MethodrefConstant printlnObj;

		final ClassConstant longClass;

		final MethodrefConstant longValueOf;

		final MethodrefConstant longValue;

		final List<Integer> code = new ArrayList<>();

		Map<String, Integer> locals = new HashMap<>();

		int nextLocal = 1; // slot 0 = args

		int maxLocals = 1;

		int maxStack = 8;

		Ctx(ConstantPool cp, FieldrefConstant systemOut, MethodrefConstant printlnObj, ClassConstant longClass,
				MethodrefConstant longValueOf, MethodrefConstant longValue) {
			this.cp = cp;
			this.systemOut = systemOut;
			this.printlnObj = printlnObj;
			this.longClass = longClass;
			this.longValueOf = longValueOf;
			this.longValue = longValue;
		}

		void emit(int opcode) {
			this.code.add(opcode);
		}

		void emitU2(int value) {
			byte[] bytes = ByteBuffer.allocate(2).putShort((short) value).array();
			this.code.add((int) bytes[0]);
			this.code.add((int) bytes[1]);
		}

		int allocLocal(String name) {
			int slot = this.nextLocal++;
			this.locals.put(name, slot);
			if (this.nextLocal > this.maxLocals) {
				this.maxLocals = this.nextLocal;
			}
			return slot;
		}

	}

}

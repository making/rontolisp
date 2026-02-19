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

		// Pass 1: Collect defun declarations and top-level expressions
		List<DefunDecl> defuns = new ArrayList<>();
		List<LispVal> topLevelExprs = new ArrayList<>();
		for (LispVal expr : program) {
			if (expr instanceof LispCons cons && cons.car() instanceof LispSymbol sym && "defun".equals(sym.name())) {
				List<LispVal> parts = cons.toList();
				String funcName = ((LispSymbol) parts.get(1)).name();
				LispVal paramsVal = parts.get(2);
				List<String> paramNames;
				if (paramsVal instanceof LispNil) {
					paramNames = List.of();
				}
				else {
					paramNames = ((LispCons) paramsVal).toList().stream().map(p -> ((LispSymbol) p).name()).toList();
				}
				defuns.add(new DefunDecl(funcName, paramNames, parts.subList(3, parts.size())));
			}
			else {
				topLevelExprs.add(expr);
			}
		}

		// Register all functions in the constant pool
		Map<String, FunctionInfo> functions = new HashMap<>();
		for (DefunDecl defun : defuns) {
			String descriptor = "(" + "Ljava/lang/Object;".repeat(defun.paramNames.size()) + ")Ljava/lang/Object;";
			Utf8Constant nameUtf8 = cp.addUtf8(defun.name);
			Utf8Constant descUtf8 = cp.addUtf8(descriptor);
			MethodrefConstant methodref = cp.addMethodref(thisClass, cp.addNameAndType(nameUtf8, descUtf8));
			functions.put(defun.name, new FunctionInfo(defun.paramNames.size(), methodref, nameUtf8, descUtf8));
		}

		// Pass 2a: Compile each defun body as a static method
		List<Ctx> funcCtxs = new ArrayList<>();
		for (DefunDecl defun : defuns) {
			Ctx funcCtx = new Ctx(cp, systemOut, printlnObj, longClass, longValueOf, longValue);
			funcCtx.functions = functions;
			funcCtx.nextLocal = defun.paramNames.size();
			funcCtx.maxLocals = defun.paramNames.size();
			for (int i = 0; i < defun.paramNames.size(); i++) {
				funcCtx.locals.put(defun.paramNames.get(i), i);
			}
			for (int i = 0; i < defun.bodyExprs.size(); i++) {
				if (i > 0) {
					funcCtx.emit(Opcode.POP);
				}
				compileExpr(defun.bodyExprs.get(i), funcCtx);
			}
			funcCtx.emit(Opcode.ARETURN);
			funcCtxs.add(funcCtx);
		}

		// Pass 2b: Compile top-level expressions as main() body
		Ctx mainCtx = new Ctx(cp, systemOut, printlnObj, longClass, longValueOf, longValue);
		mainCtx.functions = functions;
		for (LispVal expr : topLevelExprs) {
			compileExpr(expr, mainCtx);
			mainCtx.emit(Opcode.POP);
		}
		mainCtx.emit(Opcode.RETURN);

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
			.writeMethods(methods -> {
				// main method
				methods.add(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, mainUtf8, mainDesc,
						method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
							attr.writeU2(mainCtx.maxStack)
								.writeU2(mainCtx.maxLocals)
								.writeCode((Object[]) mainCtx.code.toArray(new Integer[0]))
								.writeU2(0) // exception_table_length
								.writeU2(0); // attributes_count
						})));
				// defun methods
				for (int i = 0; i < defuns.size(); i++) {
					FunctionInfo fi = java.util.Objects.requireNonNull(functions.get(defuns.get(i).name));
					final Ctx funcCtx = funcCtxs.get(i);
					methods.add(AccessFlag.ACC_PUBLIC | AccessFlag.ACC_STATIC, fi.nameUtf8, fi.descUtf8,
							method -> method.writeAttributes(attrs -> attrs.add(codeUtf8, attr -> {
								attr.writeU2(funcCtx.maxStack)
									.writeU2(funcCtx.maxLocals)
									.writeCode((Object[]) funcCtx.code.toArray(new Integer[0]))
									.writeU2(0) // exception_table_length
									.writeU2(0); // attributes_count
							})));
				}
			}) //
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
				case "=" -> compileComparison(cons, ctx, Opcode.IFEQ);
				case "<" -> compileComparison(cons, ctx, Opcode.IFLT);
				case ">" -> compileComparison(cons, ctx, Opcode.IFGT);
				case "<=" -> compileComparison(cons, ctx, Opcode.IFLE);
				case ">=" -> compileComparison(cons, ctx, Opcode.IFGE);
				case "print" -> compilePrint(cons, ctx);
				case "if" -> compileIf(cons, ctx);
				case "let" -> compileLet(cons, ctx);
				case "progn" -> compileProgn(cons, ctx);
				case "setq" -> compileSetq(cons, ctx);
				case "defun" -> {
					// defun at non-top-level is a no-op (already processed in pass 1)
					ctx.emit(Opcode.ACONST_NULL);
				}
				default -> compileFunctionCall(sym.name(), cons, ctx);
			}
		}
		else if (head instanceof LispCons headCons && headCons.car() instanceof LispSymbol headSym
				&& "lambda".equals(headSym.name())) {
			compileLambdaCall(headCons, cons, ctx);
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

	private void compileComparison(LispCons cons, Ctx ctx, int branchOpcode) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx);
		unboxLong(ctx);
		compileExpr(args.get(2), ctx);
		unboxLong(ctx);
		ctx.emit(Opcode.LCMP);
		// IFxx jumps to true_label when condition is met
		int ifPos = ctx.code.size();
		ctx.emit(branchOpcode);
		ctx.emitU2(0); // placeholder
		// False: push nil
		ctx.emit(Opcode.ACONST_NULL);
		int gotoEndPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		ctx.emitU2(0); // placeholder
		// True: push Long(1)
		int trueLabel = ctx.code.size();
		patchBranch(ctx, ifPos, trueLabel);
		compileLong(1, ctx);
		int endLabel = ctx.code.size();
		patchBranch(ctx, gotoEndPos, endLabel);
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

	private void compileSetq(LispCons cons, Ctx ctx) {
		List<LispVal> parts = cons.toList();
		String name = ((LispSymbol) parts.get(1)).name();
		compileExpr(parts.get(2), ctx);
		ctx.emit(Opcode.DUP);
		Integer slot = ctx.locals.get(name);
		if (slot == null) {
			slot = ctx.allocLocal(name);
		}
		ctx.emit(Opcode.ASTORE);
		ctx.emit(slot);
	}

	private void compileFunctionCall(String name, LispCons cons, Ctx ctx) {
		FunctionInfo fi = ctx.functions.get(name);
		if (fi != null) {
			List<LispVal> args = cons.toList();
			for (int i = 1; i < args.size(); i++) {
				compileExpr(args.get(i), ctx);
			}
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(fi.methodref.index());
		}
		else {
			throw new UnsupportedOperationException("Cannot compile: " + name);
		}
	}

	private void compileLambdaCall(LispCons lambda, LispCons call, Ctx ctx) {
		List<LispVal> lambdaParts = lambda.toList();
		LispVal paramsVal = lambdaParts.get(1);
		List<String> paramNames;
		if (paramsVal instanceof LispNil) {
			paramNames = List.of();
		}
		else {
			paramNames = ((LispCons) paramsVal).toList().stream().map(p -> ((LispSymbol) p).name()).toList();
		}
		List<LispVal> bodyExprs = lambdaParts.subList(2, lambdaParts.size());
		List<LispVal> callArgs = call.toList();

		Map<String, Integer> savedLocals = new HashMap<>(ctx.locals);
		int savedNextLocal = ctx.nextLocal;

		// Evaluate arguments and store in local variables
		for (int i = 0; i < paramNames.size(); i++) {
			compileExpr(callArgs.get(i + 1), ctx);
			int slot = ctx.allocLocal(paramNames.get(i));
			ctx.emit(Opcode.ASTORE);
			ctx.emit(slot);
		}

		// Compile body
		for (int i = 0; i < bodyExprs.size(); i++) {
			if (i > 0) {
				ctx.emit(Opcode.POP);
			}
			compileExpr(bodyExprs.get(i), ctx);
		}

		ctx.locals = savedLocals;
		ctx.nextLocal = savedNextLocal;
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

	private record DefunDecl(String name, List<String> paramNames, List<LispVal> bodyExprs) {
	}

	private record FunctionInfo(int paramCount, MethodrefConstant methodref, Utf8Constant nameUtf8,
			Utf8Constant descUtf8) {
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

		Map<String, FunctionInfo> functions = Map.of();

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

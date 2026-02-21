package am.ik.rontolisp.codegen.jvm;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;

import am.ik.jvm.Opcode;

/**
 * Compiles Lisp expressions to JVM bytecode. Serves as the entry point and dispatcher,
 * delegating to specialized compiler classes for each built-in function and special form.
 */
final class JvmExprCompiler {

	private JvmExprCompiler() {
	}

	static void compileExpr(LispVal expr, JvmLispCompiler.Ctx ctx, String className) {
		switch (expr) {
			case LispInteger i -> JvmEmitHelper.compileLong(i.value(), ctx);
			case LispDouble d -> JvmEmitHelper.compileDouble(d.value(), ctx);
			case LispNil ignored -> ctx.emit(Opcode.ACONST_NULL);
			case LispTrue ignored -> JvmEmitHelper.compileLong(1, ctx);
			case LispString s -> JvmEmitHelper.compileStringLiteral(s.print(), ctx);
			case LispSymbol sym -> compileSymbolRef(sym, ctx);
			case LispCons cons -> compileCons(cons, ctx, className);
			default -> throw new UnsupportedOperationException("Cannot compile: " + expr.print());
		}
	}

	static void compileSymbolRef(LispSymbol sym, JvmLispCompiler.Ctx ctx) {
		String name = sym.name();
		Integer slot = ctx.locals.get(name);
		if (slot != null) {
			if (ctx.boxedVars.contains(name)) {
				ctx.emit(Opcode.ALOAD);
				ctx.emit(slot);
				ctx.emit(Opcode.CHECKCAST);
				ctx.emitU2(ctx.objectArrayClass.index());
				ctx.emit(Opcode.ICONST_0);
				ctx.emit(Opcode.AALOAD);
			}
			else {
				ctx.emit(Opcode.ALOAD);
				ctx.emit(slot);
			}
		}
		else if (ctx.captures.containsKey(name)) {
			int captureIdx = ctx.captures.get(name);
			ctx.emit(Opcode.ALOAD);
			ctx.emit(ctx.closureEnvSlot);
			JvmEmitHelper.emitIntConst(ctx, 1 + captureIdx);
			ctx.emit(Opcode.AALOAD);
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(ctx.objectArrayClass.index());
			ctx.emit(Opcode.ICONST_0);
			ctx.emit(Opcode.AALOAD);
		}
		else if (ctx.functions.containsKey(name)) {
			JvmLispCompiler.FunctionInfo fi = ctx.functions.get(name);
			ctx.emit(Opcode.ICONST_1);
			ctx.emit(Opcode.ANEWARRAY);
			ctx.emitU2(ctx.objectClass.index());
			ctx.emit(Opcode.DUP);
			ctx.emit(Opcode.ICONST_0);
			JvmEmitHelper.emitIntConst(ctx, fi.funcId());
			ctx.emit(Opcode.INVOKESTATIC);
			ctx.emitU2(ctx.integerValueOf.index());
			ctx.emit(Opcode.AASTORE);
		}
		else {
			throw new UnsupportedOperationException("Cannot compile symbol reference: " + name);
		}
	}

	private static void compileCons(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		LispVal head = cons.car();
		if (head instanceof LispSymbol sym) {
			switch (sym.name()) {
				case "+" -> JvmArithCompiler.compile(cons, ctx, Opcode.LADD, Opcode.DADD, className);
				case "-" -> JvmArithCompiler.compile(cons, ctx, Opcode.LSUB, Opcode.DSUB, className);
				case "*" -> JvmArithCompiler.compile(cons, ctx, Opcode.LMUL, Opcode.DMUL, className);
				case "/" -> JvmArithCompiler.compile(cons, ctx, Opcode.LDIV, Opcode.DDIV, className);
				case "mod" -> JvmArithCompiler.compile(cons, ctx, Opcode.LREM, Opcode.DREM, className);
				case "=" -> JvmComparisonCompiler.compile(cons, ctx, Opcode.IFEQ, className);
				case "<" -> JvmComparisonCompiler.compile(cons, ctx, Opcode.IFLT, className);
				case ">" -> JvmComparisonCompiler.compile(cons, ctx, Opcode.IFGT, className);
				case "<=" -> JvmComparisonCompiler.compile(cons, ctx, Opcode.IFLE, className);
				case ">=" -> JvmComparisonCompiler.compile(cons, ctx, Opcode.IFGE, className);
				case "print" -> JvmPrintCompiler.compile(cons, ctx, className);
				case "quote" -> JvmQuoteCompiler.compile(cons, ctx, className);
				case "if" -> JvmIfCompiler.compile(cons, ctx, className);
				case "let" -> JvmLetCompiler.compile(cons, ctx, className);
				case "progn" -> JvmPrognCompiler.compile(cons, ctx, className);
				case "setq" -> JvmSetqCompiler.compile(cons, ctx, className);
				case "lambda" -> JvmLambdaCompiler.compileValue(cons, ctx, className);
				case "defun" -> ctx.emit(Opcode.ACONST_NULL);
				case "list" -> JvmListCompiler.compile(cons, ctx, className);
				case "car" -> JvmCarCompiler.compile(cons, ctx, className);
				case "cdr" -> JvmCdrCompiler.compile(cons, ctx, className);
				case "cons" -> JvmConsCompiler.compile(cons, ctx, className);
				case "funcall" -> JvmFunctionCallCompiler.compileFuncall(cons, ctx, className);
				case "null" -> JvmNullPredCompiler.compile(cons, ctx, className);
				case "atom" -> JvmAtomCompiler.compile(cons, ctx, className);
				case "numberp" -> JvmNumberpCompiler.compile(cons, ctx, className);
				case "integerp" -> JvmIntegerpCompiler.compile(cons, ctx, className);
				case "floatp" -> JvmFloatpCompiler.compile(cons, ctx, className);
				case "symbolp" -> JvmSymbolpCompiler.compile(cons, ctx, className);
				case "stringp" -> JvmStringpCompiler.compile(cons, ctx, className);
				case "listp" -> JvmListpCompiler.compile(cons, ctx, className);
				case "consp" -> JvmConspCompiler.compile(cons, ctx, className);
				default -> JvmFunctionCallCompiler.compileDefault(sym.name(), cons, ctx, className);
			}
		}
		else if (head instanceof LispCons headCons && headCons.car() instanceof LispSymbol headSym
				&& "lambda".equals(headSym.name())) {
			JvmLambdaCompiler.compileCall(headCons, cons, ctx, className);
		}
		else {
			JvmFunctionCallCompiler.compileGeneralIndirect(cons, ctx, className);
		}
	}

}

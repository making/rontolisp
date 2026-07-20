package am.ik.rontolisp.codegen.jvm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.compiler.FunctionDesignators;
import am.ik.rontolisp.LispVal;
import am.ik.jvm.Opcode;

/**
 * Compiles the {@code reduce} built-in function. Generates an inline loop that applies a
 * binary function to accumulate elements of a list into a single value (left fold).
 * Supports both 2-arg {@code (reduce f list)} and the Common Lisp keyword form
 * {@code (reduce f list :initial-value init)}.
 */
final class JvmReduceCompiler {

	private JvmReduceCompiler() {
	}

	static void compile(LispCons cons, JvmLispCompiler.Ctx ctx, String className) {
		List<LispVal> args = cons.toList();
		ctx.indirectCallArities.add(2);

		boolean withInit = hasInitialValue(args);

		// Compile function expression
		JvmExprCompiler.compileExpr(FunctionDesignators.normalize(args.get(1)), ctx, className);
		int funcSlot = ctx.allocTemp();
		ctx.emit(Opcode.ASTORE);
		ctx.emit(funcSlot);

		int accSlot = ctx.allocTemp();
		int listSlot = ctx.allocTemp();

		if (withInit) {
			// (reduce fn list :initial-value init)
			JvmExprCompiler.compileExpr(args.get(4), ctx, className);
			ctx.emit(Opcode.ASTORE);
			ctx.emit(accSlot);

			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			ctx.emit(Opcode.ASTORE);
			ctx.emit(listSlot);
		}
		else {
			// 2-arg: (reduce fn list) - first element becomes accumulator
			JvmExprCompiler.compileExpr(args.get(2), ctx, className);
			ctx.emit(Opcode.ASTORE);
			ctx.emit(listSlot);

			// acc = car(list)
			ctx.emit(Opcode.ALOAD);
			ctx.emit(listSlot);
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(ctx.objectArrayClass.index());
			ctx.emit(Opcode.ICONST_0);
			ctx.emit(Opcode.AALOAD);
			ctx.emit(Opcode.ASTORE);
			ctx.emit(accSlot);

			// list = cdr(list)
			ctx.emit(Opcode.ALOAD);
			ctx.emit(listSlot);
			ctx.emit(Opcode.CHECKCAST);
			ctx.emitU2(ctx.objectArrayClass.index());
			ctx.emit(Opcode.ICONST_1);
			ctx.emit(Opcode.AALOAD);
			ctx.emit(Opcode.ASTORE);
			ctx.emit(listSlot);
		}

		// loop:
		int loopPos = ctx.code.size();
		// if list == null, goto exit
		ctx.emit(Opcode.ALOAD);
		ctx.emit(listSlot);
		int ifNullPos = ctx.code.size();
		ctx.emit(Opcode.IFNULL);
		ctx.emitU2(0);

		// acc = func(acc, car(list))
		ctx.emit(Opcode.ALOAD);
		ctx.emit(funcSlot);
		ctx.emit(Opcode.ALOAD);
		ctx.emit(accSlot);
		// car(list) = ((Object[]) list)[0]
		ctx.emit(Opcode.ALOAD);
		ctx.emit(listSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_0);
		ctx.emit(Opcode.AALOAD);
		// Call _invoke_2(func, acc, car)
		JvmFunctionCallCompiler.emitDispatchCall(2, ctx, className);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(accSlot);

		// list = cdr(list) = ((Object[]) list)[1]
		ctx.emit(Opcode.ALOAD);
		ctx.emit(listSlot);
		ctx.emit(Opcode.CHECKCAST);
		ctx.emitU2(ctx.objectArrayClass.index());
		ctx.emit(Opcode.ICONST_1);
		ctx.emit(Opcode.AALOAD);
		ctx.emit(Opcode.ASTORE);
		ctx.emit(listSlot);

		// goto loop
		int gotoPos = ctx.code.size();
		ctx.emit(Opcode.GOTO);
		int offset = loopPos - gotoPos;
		ctx.emitU2(offset & 0xFFFF);

		// exit: load accumulator
		JvmEmitHelper.patchBranch(ctx, ifNullPos, ctx.code.size());
		ctx.emit(Opcode.ALOAD);
		ctx.emit(accSlot);
	}

	/**
	 * Determines whether the {@code reduce} form carries a literal {@code :initial-value}
	 * keyword. Args are {@code [reduce, fn, list]} (2-arg) or
	 * {@code [reduce, fn, list, :initial-value, init]}.
	 * @param args the full reduce form parts
	 * @return {@code true} for the keyword initial-value form
	 */
	static boolean hasInitialValue(List<LispVal> args) {
		if (args.size() == 3) {
			return false;
		}
		if (args.size() == 5 && args.get(3) instanceof LispSymbol kw
				&& LispNames.keywordMatches(kw.name(), LispNames.INITIAL_VALUE_KEYWORD)) {
			return true;
		}
		throw new UnsupportedOperationException(
				"reduce expects (reduce fn list) or (reduce fn list :initial-value init)");
	}

}

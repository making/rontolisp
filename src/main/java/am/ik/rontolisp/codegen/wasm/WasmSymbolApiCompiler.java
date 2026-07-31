package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles the runtime symbol API: {@code symbol-name}, {@code intern},
 * {@code find-symbol}, {@code make-symbol}, {@code boundp}, {@code fboundp} and
 * {@code symbol-value}. {@code symbol-name} reuses {@code _princ_to_str} (a symbol's
 * display text IS its name); the others call the always-present helpers built by
 * {@link WasmSymbolApiRuntimeBuilder}. {@code find-symbol} and a literal {@code fboundp}
 * fold at compile time exactly like the JVM backend ({@code JvmSymbolApiCompiler}).
 */
final class WasmSymbolApiCompiler {

	private WasmSymbolApiCompiler() {
	}

	static void compileSymbolName(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileUnaryCall(cons, LispNames.SYMBOL_NAME, WasmLispCompiler.FUNC_PRINC_TO_STR, ctx);
	}

	/**
	 * string: the CL string-designator coercion. On the compiled path this reuses
	 * {@code _princ_to_str} (like {@code symbol-name}); a string is identity, a symbol
	 * yields its name, a character a one-character string. Lenient on non-designators
	 * (the {@code symbol-name} precedent); the interpreter type-checks.
	 */
	static void compileString(LispCons cons, WasmLispCompiler.Ctx ctx) {
		// A keyword's package colon is a marker, not part of its name: (string :html) is
		// "html" (matches CL; cl-who relies on it to emit <html>, not <:html>).
		List<LispVal> parts = requireArgs(cons, LispNames.STRING);
		if (parts.get(1) instanceof LispSymbol sym && sym.isKeyword()) {
			WasmEmitHelper.compileStringLiteral(new LispString(sym.name().substring(1)).print(), ctx);
			return;
		}
		compileUnaryCall(cons, LispNames.STRING, WasmLispCompiler.FUNC_PRINC_TO_STR, ctx);
	}

	static void compileIntern(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> full = cons.toList();
		if (full.size() == 3) {
			// (intern name :keyword) -> (intern (concatenate 'string ":" name)): keeps
			// the
			// keyword lowering backend-neutral. Any other package argument is
			// unsupported.
			if (LispMacroExpander.isKeywordPackageDesignator(full.get(2))) {
				WasmExprCompiler.compileExpr(LispMacroExpander.internKeywordForm(full.get(1)), ctx);
				return;
			}
			// A runtime package argument needs the resolver's package state, which only
			// the interpreter has -- lower to a call-time signal (the jzon stub-lowering
			// precedent) so a library defun merely CONTAINING the form still compiles.
			WasmExprCompiler.compileExpr(LispMacroExpander.internPackageArgumentStub(), ctx);
			return;
		}
		compileUnaryCall(cons, LispNames.INTERN, WasmLispCompiler.FUNC_INTERN_SYM, ctx, true);
	}

	static void compileMakeSymbol(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileUnaryCall(cons, LispNames.MAKE_SYMBOL, WasmLispCompiler.FUNC_MAKE_SYMBOL, ctx, true);
	}

	static void compileBoundp(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileUnaryCall(cons, LispNames.BOUNDP, WasmLispCompiler.FUNC_BOUNDP, ctx);
	}

	static void compileSymbolValue(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileUnaryCall(cons, LispNames.SYMBOL_VALUE, WasmLispCompiler.FUNC_SYMBOL_VALUE, ctx);
	}

	/**
	 * find-symbol: a LITERAL name folds at compile time against the compile-time view of
	 * the image (cl symbols, keywords, Pass-1 user defuns); a computed one lowers to
	 * {@code intern}, which is the lookup under the name-based symbol model.
	 */
	static void compileFindSymbol(LispCons cons, WasmLispCompiler.Ctx ctx) {
		if (cons.toList().size() == 3) {
			LispVal inPackage = LispMacroExpander.expandFindSymbolInPackage(cons, ctx.packageTable);
			if (inPackage == null) {
				throw new UnsupportedOperationException(LispNames.FIND_SYMBOL
						+ " needs a literal package designator in compiled mode: " + cons.print());
			}
			WasmExprCompiler.compileExpr(inPackage, ctx);
			return;
		}
		List<LispVal> parts = requireArgs(cons, LispNames.FIND_SYMBOL);
		if (!(parts.get(1) instanceof LispString str)) {
			WasmExprCompiler.compileExpr(LispMacroExpander.computedFindSymbol(parts.get(1)), ctx);
			return;
		}
		String name = str.value();
		boolean known = PackageRegistry.isClSymbol(name) || (!name.isEmpty() && name.charAt(0) == ':')
				|| ctx.userDefunNames.contains(name);
		if (known) {
			WasmEmitHelper.compileStringLiteral(name, ctx);
		}
		else {
			emitNil(ctx);
		}
	}

	/**
	 * fboundp: a literal quoted symbol folds at compile time (functions, macros, special
	 * forms, car/cdr compositions, user defuns); a computed argument probes the runtime
	 * {@code _fenv} then the compiled-function registry (functions only).
	 *
	 * <p>
	 * When the program calls {@code fmakunbound} the fold is emitted BEHIND a tombstone
	 * probe of {@code GLOBAL_FENV}: a retired name must answer nil even at a literal call
	 * site, and only the runtime knows which names were retired. Programs without
	 * {@code fmakunbound} keep the bare constant.
	 */
	static void compileFboundp(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = requireArgs(cons, LispNames.FBOUNDP);
		if (parts.get(1) instanceof LispCons quoteForm && quoteForm.car() instanceof LispSymbol op
				&& LispNames.QUOTE.equals(op.name()) && ((LispCons) quoteForm.cdr()).car() instanceof LispSymbol sym) {
			String name = sym.name();
			boolean bound = PackageRegistry.specialOperatorNames().contains(name)
					|| PackageRegistry.clFunctionNames().contains(name) || LispNames.isCarCdrComposition(name)
					|| ctx.userDefunNames.contains(name) || ctx.functions.containsKey(name);
			if (ctx.usesFmakunbound) {
				emitTombstoneGuardedFold(name, bound, ctx);
				return;
			}
			if (bound) {
				WasmEmitHelper.emitTrue(ctx);
			}
			else {
				emitNil(ctx);
			}
			return;
		}
		compileUnaryCall(cons, LispNames.FBOUNDP, WasmLispCompiler.FUNC_FBOUNDP, ctx);
	}

	/**
	 * fmakunbound: installs a tombstone in the runtime function namespace, so a
	 * late-bound reference sees the name undefined again
	 * ({@link WasmSymbolApiRuntimeBuilder#buildFmakunbound}).
	 */
	static void compileFmakunbound(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileUnaryCall(cons, LispNames.FMAKUNBOUND, WasmLispCompiler.FUNC_FMAKUNBOUND, ctx);
	}

	/**
	 * Emits {@code fboundp}'s literal answer for a program that also calls
	 * {@code fmakunbound}: when {@code GLOBAL_FENV} holds a binding for the name it
	 * decides (t when the value cell is set, nil when {@code fmakunbound} cleared it),
	 * otherwise the compile-time fold stands. The literal's string-table offset is known
	 * here, so the probe is {@code _env_lookup} inline rather than a helper call.
	 */
	private static void emitTombstoneGuardedFold(String name, boolean folded, WasmLispCompiler.Ctx ctx) {
		int offset = ctx.stringTable.addString(name).offset();
		int bind = ctx.allocTemp();
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(offset);
		ctx.writer.write(Instruction.GET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.GLOBAL_FENV);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.FUNC_ENV_LOOKUP);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(bind);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(bind);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		if (folded) {
			WasmEmitHelper.emitTrue(ctx);
		}
		else {
			emitNil(ctx);
		}
		ctx.writer.write(Instruction.ELSE);
		// A binding exists: its value cell answers, normalized to t/nil.
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(bind);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		emitNil(ctx);
		ctx.writer.write(Instruction.ELSE);
		WasmEmitHelper.emitTrue(ctx);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	private static void compileUnaryCall(LispCons cons, String name, int funcIndex, WasmLispCompiler.Ctx ctx) {
		compileUnaryCall(cons, name, funcIndex, ctx, false);
	}

	// normalizeCharVector: intern/make-symbol expect a STRING argument, so a mutable
	// character vector normalizes through _charvec_to_str first; symbol-name/string go
	// through _princ_to_str, whose print path already normalizes.
	private static void compileUnaryCall(LispCons cons, String name, int funcIndex, WasmLispCompiler.Ctx ctx,
			boolean normalizeCharVector) {
		List<LispVal> parts = requireArgs(cons, name);
		WasmExprCompiler.compileExpr(parts.get(1), ctx);
		if (normalizeCharVector) {
			WasmEmitHelper.emitCharvecToStrCall(ctx);
		}
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(funcIndex);
	}

	private static List<LispVal> requireArgs(LispCons cons, String name) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2) {
			throw new UnsupportedOperationException(name + " expects 1 argument, got " + (parts.size() - 1));
		}
		return parts;
	}

	private static void emitNil(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
	}

}

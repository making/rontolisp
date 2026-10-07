package am.ik.rontolisp.codegen.wasm;

import java.util.List;

import am.ik.rontolisp.ClosRegistry;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.compiler.RuntimeFunctionNames;
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
	 * string: the CL string-designator coercion, and the single definition of it every
	 * designator POSITION routes through (the {@code string=} operands, the
	 * {@code %string-compare} walk behind the {@code string<} family, the
	 * {@code string-trim} / case-fold arguments). A compile-time-known designator folds
	 * to its constant; a computed one gets
	 * {@link LispMacroExpander#strictStringDesignatorForm} -- the guarded
	 * {@code _princ_to_str} coercion, which type-checks like the interpreter instead of
	 * stringifying anything handed to it.
	 */
	static void compileString(LispCons cons, WasmLispCompiler.Ctx ctx) {
		// A keyword's package colon is a marker, not part of its name: (string :html) is
		// "HTML" (matches CL; cl-who relies on it to emit <html>, not <:html>).
		List<LispVal> parts = requireArgs(cons, LispNames.STRING);
		String literal = LispMacroExpander.literalStringDesignator(parts.get(1));
		if (literal != null) {
			WasmEmitHelper.compileStringLiteral(new LispString(literal).literal(), ctx);
			return;
		}
		// Computed: a string answers ITSELF -- a quote-framed literal and a mutable
		// character vector alike (CLHS string of a string, and what the interpreter
		// does); anything else takes the guarded coercion below. One evaluation --
		// the value parks in a local both arms read back.
		WasmExprCompiler.compileExpr(parts.get(1), ctx);
		int nameSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(nameSlot);
		// stringp (proper or charvec)?
		WasmStringpCompiler.emitStringpI32(ctx, nameSlot);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(nameSlot);
		ctx.writer.write(Instruction.ELSE);
		// Slow: nil, an unquoted TYPE_STRING (a symbol -- quoted ones stringp above),
		// or a character render through _princ_to_str; anything else signals through
		// the error machinery (catchable in EH mode, a trap without it), like the
		// strict designator form's error arm -- just without the offending value,
		// which no textless trap could carry anyway.
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(nameSlot);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(nameSlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_PRINC_TO_STR);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(nameSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(nameSlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_PRINC_TO_STR);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(nameSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CHAR);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(nameSlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_PRINC_TO_STR);
		ctx.writer.write(Instruction.ELSE);
		WasmExprCompiler.compileExpr(new LispCons(new LispSymbol(LispNames.ERROR),
				new LispCons(new LispString(LispNames.STRING + " expects a string designator"), LispNil.INSTANCE)),
				ctx);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
		ctx.writer.write(Instruction.END);
	}

	static void compileIntern(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> full = cons.toList();
		if (full.size() == 3) {
			// (intern name pkg): the canonical-spelling lowering shared with the 2-arg
			// find-symbol (an unknown package is a call-time signal, or -- when the
			// program can create packages -- a runtime-table lookup first).
			WasmExprCompiler.compileExpr(LispMacroExpander.expandInternInPackage(cons, ctx.packageTable,
					ctx.usesRuntimePackages, ctx.functions::containsKey, ctx.bakedSymbolAccess), ctx);
			return;
		}
		compileUnaryCall(cons, LispNames.INTERN, WasmLispCompiler.FUNC_INTERN_SYM, ctx, true);
	}

	static void compileMakeSymbol(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileUnaryCall(cons, LispNames.MAKE_SYMBOL, WasmLispCompiler.FUNC_MAKE_SYMBOL, ctx, true);
	}

	/**
	 * boundp. A probed global whose module global carries its bound-ness (it starts as
	 * the UNBOUND marker, {@link WasmLispCompiler.Ctx#probedUnboundGlobals}) is answered
	 * by it ({@link LispMacroExpander#dynamicFirstBoundp}): a literal one by
	 * {@code %global-boundp}, a computed name through the shared dispatch over them.
	 * Every other name, and every name in a program without such a global, probes the
	 * {@code GLOBAL_ENV} mirror.
	 */
	static void compileBoundp(LispCons cons, WasmLispCompiler.Ctx ctx) {
		LispVal tracked = LispMacroExpander.dynamicFirstBoundp(cons, ctx.probedUnboundGlobals, ctx.specialVars,
				ctx.functions.containsKey(LispNames.BOUNDP_DYNAMIC));
		if (tracked != null) {
			WasmExprCompiler.compileExpr(tracked, ctx);
			return;
		}
		compileBoundpRaw(cons, ctx);
	}

	/**
	 * The raw {@code boundp} emission (the {@code GLOBAL_ENV} probe) -- also reachable as
	 * {@code %boundp-raw}, the fallback arm of the dispatch above.
	 */
	static void compileBoundpRaw(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileUnaryCall(cons, LispNames.BOUNDP, WasmLispCompiler.FUNC_BOUNDP, ctx);
	}

	/**
	 * {@code (%global-boundp 'G)}: the global's current value -- a special's per-task
	 * binding under {@code --reentrant}, else the module global, which shallow binding
	 * makes a special's active binding -- compared with the UNBOUND marker: nil when it
	 * is the marker, t otherwise.
	 */
	static void compileGlobalBoundp(LispCons cons, WasmLispCompiler.Ctx ctx) {
		String name = ((LispSymbol) ((LispCons) cons.toList().get(1)).toList().get(1)).name();
		Integer globalIndex = ctx.globalIndices.get(name);
		if (globalIndex == null || !ctx.unboundGlobals.contains(name)) {
			throw new IllegalStateException("global " + name + " does not carry its bound-ness in its module global ("
					+ LispNames.BOUNDP + " of it was lowered to " + LispNames.GLOBAL_BOUNDP + ")");
		}
		WasmExprCompiler.emitRawSpecialRead(ctx, name, globalIndex);
		ctx.writer.write(Instruction.GET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(ctx.rawSentinelGlobalIndex);
		ctx.writer.write(Instruction.REF_EQ);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		ctx.wasmCtrlDepth++;
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.ELSE);
		WasmExprCompiler.compileExpr(LispTrue.INSTANCE, ctx);
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END);
	}

	/**
	 * symbol-value. A special is read through its variable
	 * ({@link LispMacroExpander#dynamicFirstSymbolValue}): a literal special reads the
	 * variable (the module-global / per-task read), a computed name calls the shared
	 * dispatch over the special set, so an active {@code progv}/{@code let} binding --
	 * and a {@code setq} or {@code set} inside its extent -- is answered, never the
	 * {@code GLOBAL_ENV} mirror, which no binding's restore touches. Every other name,
	 * and every name in a program without specials, reads the mirror.
	 */
	static void compileSymbolValue(LispCons cons, WasmLispCompiler.Ctx ctx) {
		if (!ctx.specialVars.isEmpty() && cons.toList().size() == 2) {
			WasmExprCompiler.compileExpr(LispMacroExpander.dynamicFirstSymbolValue(cons, ctx.specialVars,
					ctx.functions.containsKey(LispNames.SYMBOL_VALUE_DYNAMIC)), ctx);
			return;
		}
		compileSymbolValueRaw(cons, ctx);
	}

	/**
	 * The raw {@code symbol-value} emission (the {@code GLOBAL_ENV} probe) -- also
	 * reachable as {@code %symbol-value-raw}, the fallback arm of the dynamic-first
	 * dispatch above.
	 */
	static void compileSymbolValueRaw(LispCons cons, WasmLispCompiler.Ctx ctx) {
		compileUnaryCall(cons, LispNames.SYMBOL_VALUE, WasmLispCompiler.FUNC_SYMBOL_VALUE, ctx);
	}

	/**
	 * find-symbol: a LITERAL name folds at compile time against the compile-time view of
	 * the image (cl symbols, keywords, Pass-1 user defuns); a computed one lowers to
	 * {@code intern}, which is the lookup under the name-based symbol model.
	 */
	static void compileFindSymbol(LispCons cons, WasmLispCompiler.Ctx ctx) {
		if (cons.toList().size() == 3) {
			LispVal inPackage = LispMacroExpander.expandFindSymbolInPackage(cons, ctx.packageTable,
					ctx.usesRuntimePackages, ctx.functions::containsKey, ctx.bakedSymbolAccess);
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
		LispVal found = LispMacroExpander.foldLiteralFindSymbol(str.value(), ctx.userDefunNames);
		if (found instanceof LispSymbol sym) {
			WasmEmitHelper.compileStringLiteral(sym.name(), ctx);
		}
		else {
			WasmExprCompiler.compileExpr(found, ctx);
		}
	}

	/**
	 * {@code %find-symbol-status}: a compile-time constant, decided by the shared fold so
	 * both backends answer the same keyword (see
	 * {@link LispMacroExpander#expandFindSymbolStatus}).
	 */
	static void compileFindSymbolStatus(LispCons cons, WasmLispCompiler.Ctx ctx) {
		// The answer is a keyword or nil, both self-evaluating: no quote needed.
		WasmExprCompiler.compileExpr(LispMacroExpander.expandFindSymbolStatus(cons, ctx.packageTable,
				ctx.userDefunNames, ctx.usesRuntimePackages, ctx.functions::containsKey, ctx.bakedSymbolAccess), ctx);
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
			if (ctx.fenvForwarders.contains(name)) {
				// Bound once the setf ran: the forwarder defun is no definition, so the
				// run-time probe answers (it misses the registry).
				compileUnaryCall(cons, LispNames.FBOUNDP, WasmLispCompiler.FUNC_FBOUNDP, ctx);
				return;
			}
			if (WasmFunctionFormCompiler.nestedDefun(name, ctx)) {
				// Bound once the definition below the top level ran: its global holds
				// the function from then on, nil before.
				Runnable read = () -> emitNestedDefunBound(name, ctx);
				if (ctx.usesFmakunbound) {
					emitTombstoneGuardedFold(name, read, ctx);
				}
				else {
					read.run();
				}
				return;
			}
			boolean bound = PackageRegistry.specialOperatorNames().contains(name)
					|| PackageRegistry.clFunctionNames().contains(name) || LispNames.isCarCdrComposition(name)
					|| ctx.userDefunNames.contains(name) || ctx.functions.containsKey(name);
			if (!bound && ctx.bindsRuntimeFunctionNames) {
				// No definition, but the run time can bind the name: the probe answers.
				compileUnaryCall(cons, LispNames.FBOUNDP, WasmLispCompiler.FUNC_FBOUNDP, ctx);
				return;
			}
			if (ctx.usesFmakunbound) {
				emitTombstoneGuardedFold(name, bound ? () -> WasmEmitHelper.emitTrue(ctx) : () -> emitNil(ctx), ctx);
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
		// A (setf place) list built at run time probes the name its writer is stored
		// under.
		LispVal name = RuntimeFunctionNames.functionNameArgument(parts.get(1),
				ctx.functions.containsKey(LispNames.FUNCTION_NAME_INTERNAL));
		WasmExprCompiler.compileExpr(name, ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_FBOUNDP);
	}

	/**
	 * fmakunbound: installs a tombstone in the runtime function namespace, so a
	 * late-bound reference sees the name undefined again
	 * ({@link WasmSymbolApiRuntimeBuilder#buildFmakunbound}).
	 */
	static void compileFmakunbound(LispCons cons, WasmLispCompiler.Ctx ctx) {
		LispCons computed = RuntimeFunctionNames.fmakunboundCall(cons,
				ctx.functions.containsKey(LispNames.FMAKUNBOUND_INTERNAL));
		if (computed != null) {
			// A name built at run time may be a (setf place) list.
			WasmExprCompiler.compileExpr(computed, ctx);
			return;
		}
		compileUnaryCall(cons, LispNames.FMAKUNBOUND, WasmLispCompiler.FUNC_FMAKUNBOUND, ctx);
	}

	/**
	 * {@code (%set-symbol-function name value)}: the write-side twin of
	 * {@code fmakunbound} behind {@code (setf (symbol-function ...))}
	 * ({@link WasmSymbolApiRuntimeBuilder#buildSetSymbolFunction}); the helper returns
	 * the value, the setf result.
	 */
	static void compileSetSymbolFunction(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 3) {
			throw new UnsupportedOperationException(
					LispNames.SET_SYMBOL_FUNCTION_INTERNAL + " expects 2 arguments, got " + (parts.size() - 1));
		}
		// A name built at run time may be a (setf place) list: its writer's name.
		WasmExprCompiler.compileExpr(RuntimeFunctionNames.functionNameArgument(parts.get(1),
				ctx.functions.containsKey(LispNames.FUNCTION_NAME_INTERNAL)), ctx);
		WasmExprCompiler.compileExpr(parts.get(2), ctx);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_SET_SYMBOL_FUNCTION);
	}

	/**
	 * {@code (%setf-function-symbol place)}: the symbol the {@code (setf place)} function
	 * is stored under. The prefix and the place's spelling are assembled in the heap
	 * scratch and canonicalized through {@code _intern}, so the symbol's offset is the
	 * one the namespace and registry lookups compare -- the {@code _intern_sym} rail,
	 * which is why the program counts as interning ({@code usesIntern}).
	 */
	static void compileSetfFunctionSymbol(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = requireArgs(cons, LispNames.SETF_FUNCTION_SYMBOL_INTERNAL);
		byte[] prefix = ClosRegistry.SETF_FUNCTION_PREFIX.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		// The place's bytes after the prefix (_str_to_mem grows the memory to hold
		// them, the prefix's room included); len = prefix + place.
		WasmExprCompiler.compileExpr(parts.get(1), ctx);
		emitHeapPtr(ctx);
		emitI32(ctx, prefix.length);
		ctx.writer.write(Instruction.I32_ADD);
		WasmEmitHelper.emitStrToMemCall(ctx.writer);
		emitI32(ctx, prefix.length);
		ctx.writer.write(Instruction.I32_ADD);
		int savedI64Locals = ctx.nextI64Local;
		int lenSlot = ctx.allocI64Temp();
		ctx.writer.write(Instruction.I64_EXTEND_U_I32);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writeI64LocalIndex(lenSlot);
		for (int i = 0; i < prefix.length; i++) {
			emitHeapPtr(ctx);
			emitI32(ctx, prefix[i]);
			ctx.writer.write(Instruction.I32_STORE8, 0x00);
			ctx.writer.writeUnsignedLeb128(i);
		}
		// _str_build(_intern(heap, len), len)
		emitHeapPtr(ctx);
		emitLen(ctx, lenSlot);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_INTERN);
		emitLen(ctx, lenSlot);
		WasmEmitHelper.emitStrBuildCall(ctx.writer);
		ctx.nextI64Local = savedI64Locals;
	}

	/**
	 * {@code (%undefined-setf-function place)}: signals what a call of the undefined
	 * {@code (setf place)} function signals -- {@code _undefined_function} of the list
	 * where the module carries it, else the message-only error
	 * ({@link WasmFunctionCallCompiler#emitUndefinedFunctionSignal}).
	 */
	static void compileUndefinedSetfFunction(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = requireArgs(cons, LispNames.UNDEFINED_SETF_FUNCTION_INTERNAL);
		LispVal name = list(new LispSymbol(LispNames.LIST),
				list(new LispSymbol(LispNames.QUOTE), new LispSymbol(LispNames.SETF)), parts.get(1));
		if (ctx.undefinedFunctionFuncIndex >= 0) {
			WasmExprCompiler.compileExpr(name, ctx);
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeUnsignedLeb128(ctx.undefinedFunctionFuncIndex);
			return;
		}
		WasmExprCompiler.compileExpr(list(new LispSymbol(LispNames.ERROR),
				LispMacroExpander.textControlForm(list(new LispSymbol(LispNames.CONCATENATE),
						list(new LispSymbol(LispNames.QUOTE), new LispSymbol(LispNames.STRING)),
						new LispString(ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_PREFIX),
						list(new LispSymbol(LispNames.PRINC_TO_STRING), name),
						new LispString(ClosRegistry.UNDEFINED_FUNCTION_MESSAGE_SUFFIX)))),
				ctx);
	}

	private static LispVal list(LispVal... elements) {
		LispVal out = LispNil.INSTANCE;
		for (int i = elements.length - 1; i >= 0; i--) {
			out = new LispCons(elements[i], out);
		}
		return out;
	}

	private static void emitHeapPtr(WasmLispCompiler.Ctx ctx) {
		emitI32(ctx, WasmLispCompiler.HEAP_PTR_ADDR);
		ctx.writer.write(Instruction.I32_LOAD, 0x02, 0x00);
	}

	private static void emitI32(WasmLispCompiler.Ctx ctx, int value) {
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(value);
	}

	private static void emitLen(WasmLispCompiler.Ctx ctx, int lenSlot) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writeI64LocalIndex(lenSlot);
		ctx.writer.write(Instruction.I32_WRAP_I64);
	}

	/**
	 * {@code (%fenv-function 'name)}: the GLOBAL_FENV-only function read of the
	 * setf-only-alias forwarder defuns ({@link WasmFunctionFormCompiler#emitFenvRead}); a
	 * miss signals the undefined-function a direct call of the name would.
	 */
	static void compileFenvFunction(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = requireArgs(cons, LispNames.FENV_FUNCTION_INTERNAL);
		if (!(parts.get(1) instanceof LispCons quoteForm && quoteForm.car() instanceof LispSymbol op
				&& LispNames.QUOTE.equals(op.name()) && ((LispCons) quoteForm.cdr()).car() instanceof LispSymbol sym)) {
			throw new UnsupportedOperationException(LispNames.FENV_FUNCTION_INTERNAL + " expects a quoted name");
		}
		WasmFunctionFormCompiler.emitFenvRead(sym.name(), ctx);
	}

	/**
	 * {@code (set name value)} -- store {@code value} into the global variable
	 * {@code name} names, creating the binding when unbound: the computed-name
	 * counterpart of {@code setq}. Lowered by
	 * {@link LispMacroExpander#expandSetForCompile}: {@link #compileSetMirror} checks the
	 * name and writes the eval mirror, and a name with a compiled backing store writes
	 * that module global ({@link #compileGlobalStoreSet}) through the shared
	 * {@code %set-global} dispatch over the globals, or the same dispatch inline when the
	 * program lacks it -- matched by canonical string-table offset, so a caller's literal
	 * and a run-time {@code intern} agree. Like {@code setq}, the store assigns an
	 * already-active dynamic binding of a special. Forces {@code usesEval} in
	 * {@link WasmLispCompiler} like the rest of the symbol API.
	 */
	static void compileSet(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 3) {
			throw new UnsupportedOperationException(LispNames.SET + " expects 2 arguments, got " + (parts.size() - 1));
		}
		// Sorted by name: the index map is a hash, and emission must stay deterministic.
		java.util.List<String> orderedGlobals = new java.util.ArrayList<>(ctx.globalIndices.keySet());
		java.util.Collections.sort(orderedGlobals);
		WasmExprCompiler.compileExpr(LispMacroExpander.expandSetForCompile(cons, orderedGlobals, ctx.specialVars,
				ctx.functions.containsKey(LispNames.SET_GLOBAL_RUNTIME)), ctx);
	}

	/**
	 * {@code (%set-mirror name value)} -- the checked half of {@code set}: constants
	 * (nil, t and keywords, by value or by computed name) and non-symbols trap, the
	 * {@code %error} convention of the symbol API; otherwise the eval mirror is written
	 * through {@code _store}, which creates the binding in {@code GLOBAL_ENV} when the
	 * name has none. Answers the stored value.
	 */
	static void compileSetMirror(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		WasmExprCompiler.compileExpr(parts.get(1), ctx);
		int nameSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(nameSlot);
		WasmExprCompiler.compileExpr(parts.get(2), ctx);
		int valueSlot = ctx.allocTemp();
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(valueSlot);
		// null (nil) -> trap
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(nameSlot);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.IF, 0x40);
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.writer.write(Instruction.END);
		// not a string struct -> trap
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(nameSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
		ctx.writer.write(Instruction.I32_EQZ);
		ctx.writer.write(Instruction.IF, 0x40);
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.writer.write(Instruction.END);
		// the symbol t (shared literal offset) is a constant -> trap
		emitNameOffsetEquals(nameSlot, ctx.stringTable.addString("T").offset(), ctx);
		ctx.writer.write(Instruction.IF, 0x40);
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.writer.write(Instruction.END);
		// a computed NIL names the constant, not a binding -> trap
		emitNameOffsetEquals(nameSlot, ctx.stringTable.addString("NIL").offset(), ctx);
		ctx.writer.write(Instruction.IF, 0x40);
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.writer.write(Instruction.END);
		// a keyword (first content byte ':') is a constant -> trap
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(nameSlot);
		WasmEmitHelper.emitStrBytesArray(ctx);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_STR_BYTES);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(':');
		ctx.writer.write(Instruction.I32_EQ);
		ctx.writer.write(Instruction.IF, 0x40);
		ctx.writer.write(Instruction.UNREACHABLE);
		ctx.writer.write(Instruction.END);
		// the mirror: _store creates the binding when the name has none, and answers
		// the stored value
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(nameSlot);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(valueSlot);
		ctx.writer.write(Instruction.GET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.GLOBAL_ENV);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_STORE);
	}

	/**
	 * {@code (%global-store-set NAME value)} -- {@code global.set} into the module global
	 * of the literal global {@code NAME}, which under shallow binding IS a special's
	 * active binding; under {@code --reentrant} a dynamically-bound special writes this
	 * call's task-record cell when one is active ({@link WasmDynVars#emitWrite}), the
	 * store {@code setq} makes. Answers nil.
	 */
	static void compileGlobalStoreSet(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		String name = ((LispSymbol) parts.get(1)).name();
		Integer index = ctx.globalIndices.get(name);
		if (index == null) {
			throw new IllegalStateException("global " + name + " has no module global for " + LispNames.SET);
		}
		WasmExprCompiler.compileExpr(parts.get(2), ctx);
		if (WasmDynVars.handles(ctx, name)) {
			int valueSlot = ctx.allocTemp();
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeUnsignedLeb128(valueSlot);
			WasmDynVars.emitWrite(ctx, name, index, valueSlot);
		}
		else {
			ctx.writer.write(Instruction.SET_GLOBAL);
			ctx.writer.writeUnsignedLeb128(index);
		}
		emitNil(ctx);
	}

	/**
	 * {@code (%symbol-is x 'NAME)} as an i32 truth value (the complement when
	 * {@code negated}): {@code x} is a string struct at {@code NAME}'s canonical
	 * string-table offset -- what {@code eql} answers for a symbol, without building the
	 * literal or calling the structural {@code equal}. A variable operand is read twice;
	 * anything else goes through a temp. The name is not a designator the program
	 * spelled.
	 */
	static void emitSymbolIsTest(LispCons cons, WasmLispCompiler.Ctx ctx, boolean negated) {
		List<LispVal> parts = cons.toList();
		LispCons quoted = (LispCons) parts.get(2);
		int offset = ctx.stringTable.addString(((LispSymbol) ((LispCons) quoted.cdr()).car()).name()).offset();
		LispVal operand = parts.get(1);
		int slot = -1;
		WasmExprCompiler.compileExpr(operand, ctx);
		if (!(operand instanceof LispSymbol)) {
			slot = ctx.allocTemp();
			ctx.writer.write(Instruction.TEE_LOCAL);
			ctx.writer.writeUnsignedLeb128(slot);
		}
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I32);
		if (slot < 0) {
			WasmExprCompiler.compileExpr(operand, ctx);
		}
		else {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(slot);
		}
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_STRING);
		ctx.writer.writeUnsignedLeb128(0);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(offset);
		ctx.writer.write(negated ? Instruction.I32_NE : Instruction.I32_EQ);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(negated ? 1 : 0);
		ctx.writer.write(Instruction.END);
	}

	/** {@code (%symbol-is x 'NAME)} as a Lisp boolean. */
	static void compileSymbolIs(LispCons cons, WasmLispCompiler.Ctx ctx) {
		emitSymbolIsTest(cons, ctx, false);
		WasmEmitHelper.emitBoolFromI32(ctx);
	}

	// The name's canonical string-table offset == the given one, as an i32 condition.
	private static void emitNameOffsetEquals(int nameSlot, int offset, WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(nameSlot);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_STRING);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_STRING);
		ctx.writer.writeUnsignedLeb128(0);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(offset);
		ctx.writer.write(Instruction.I32_EQ);
	}

	/**
	 * Emits {@code fboundp}'s literal answer for a program that also calls
	 * {@code fmakunbound}: when {@code GLOBAL_FENV} holds a binding for the name it
	 * decides (t when the value cell is set, nil when {@code fmakunbound} cleared it),
	 * otherwise the compile-time fold stands. The literal's string-table offset is known
	 * here, so the probe is {@code _env_lookup} inline rather than a helper call.
	 */
	private static void emitTombstoneGuardedFold(String name, Runnable fold, WasmLispCompiler.Ctx ctx) {
		int offset = ctx.stringTable.addString(name).offset();
		int bind = ctx.allocTemp();
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(offset);
		ctx.writer.write(Instruction.GET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.GLOBAL_FENV);
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.FUNC_ENV_LOOKUP);
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(bind);
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(bind);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		fold.run();
		ctx.writer.write(Instruction.ELSE);
		// A binding exists: its value cell answers, normalized to t/nil.
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(bind);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(WasmLispCompiler.TYPE_CONS);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		ctx.writer.writeUnsignedLeb128(1);
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
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
		ctx.writer.writeUnsignedLeb128(funcIndex);
	}

	private static List<LispVal> requireArgs(LispCons cons, String name) {
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2) {
			throw new UnsupportedOperationException(name + " expects 1 argument, got " + (parts.size() - 1));
		}
		return parts;
	}

	/**
	 * Pushes t when the global a function defined below the top level is assigned to
	 * holds it (the definition ran), nil while it is still nil.
	 */
	private static void emitNestedDefunBound(String name, WasmLispCompiler.Ctx ctx) {
		WasmExprCompiler.emitRawSpecialRead(ctx, name, java.util.Objects.requireNonNull(ctx.globalIndices.get(name)));
		ctx.writer.write(Instruction.REF_IS_NULL);
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		emitNil(ctx);
		ctx.writer.write(Instruction.ELSE);
		WasmEmitHelper.emitTrue(ctx);
		ctx.writer.write(Instruction.END);
	}

	private static void emitNil(WasmLispCompiler.Ctx ctx) {
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
	}

}

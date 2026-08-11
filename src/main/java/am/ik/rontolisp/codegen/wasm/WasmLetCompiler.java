package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FreeVarAnalyzer;
import am.ik.wasm.Instruction;

/**
 * Compiles the {@code let} special form.
 *
 * <p>
 * A binding whose name is a special (dynamically bound) variable is not given a lexical
 * local: instead the special's module-level wasm global is saved into a temp local, set
 * to the init value, and restored to its previous value when the body exits normally -- a
 * dynamic binding, whose new value is visible (via {@code global.get}) to any function
 * called during the body. Restore fires on normal completion; an error is a trap that
 * aborts the module (restore moot). A {@code return} that unwinds (a {@code br}) across
 * the {@code let} boundary does not restore the global (a known compile-path limitation;
 * the interpreter restores on every exit).
 */
final class WasmLetCompiler {

	private WasmLetCompiler() {
	}

	static void compile(LispCons cons, WasmLispCompiler.Ctx ctx) {
		List<LispVal> parts = cons.toList();
		// A bare symbol entry is an init-less binding to nil.
		LispVal bindings = LispMacroExpander.normalizeBindingList(parts.get(1));
		Map<String, Integer> savedLocals = new HashMap<>(ctx.locals);
		// State-machine mode (asyncMode, awaits under this let): each init is guarded
		// like a sequence statement (a resume restores the bound locals from the spill
		// and skips the init), and the body routes through the shared guarded progn. A
		// dynamic (special) binding cannot survive a suspension, so it is rejected.
		boolean async = ctx.asyncResume != null && WasmAwaitAnalysis.countAwaits(cons) > 0;

		// Pre-scan body for captured vars. Specials are globals reachable from any
		// function,
		// never captured lexicals, so they are excluded from the capture analysis.
		List<LispVal> bodyExprs = parts.subList(2, parts.size());
		Set<String> letVarNames = new HashSet<>();
		if (bindings instanceof LispCons bindingsCons) {
			for (LispVal binding : bindingsCons.toList()) {
				letVarNames.add(((LispSymbol) ((LispCons) binding).toList().get(0)).name());
			}
		}
		Set<String> capturedInLet = FreeVarAnalyzer.findCapturedVars(bodyExprs, letVarNames, ctx.functions.keySet());

		// Unboxed (dual-representation) locals, todo 194 stage 3: a binding that is
		// never captured or special and has at least one integer-tree-shaped assignment
		// (its init, or a setq/setf pair in the body) gets an i64 slot + a boxed shadow
		// slot instead of an ordinary local. Every assignment funnels through
		// WasmSetqCompiler (psetq/rotatef/incf/... all expand to setq), which stores
		// raw on the fused fast path and boxes into the shadow otherwise. Gated off at
		// top level (the eval-mirror writes boxed slots), under --dynamic and in async
		// bodies, like the rest of the fusion machinery -- and under --optimize=size,
		// which declines both halves of that machinery through the one predicate
		// (WasmIntFusionCompiler.speedTradesEnabled): a local kept raw with fusion off
		// would bail into its boxed shadow on every assignment.
		Set<String> rawEligible = new HashSet<>();
		int savedNextI64Local = ctx.nextI64Local;
		if (WasmIntFusionCompiler.speedTradesEnabled(ctx) && !ctx.dynamic && !ctx.topLevel && !async
				&& ctx.asyncResume == null && !Boolean.getBoolean("rontolisp.debug.norawlocals")
				&& bindings instanceof LispCons rawScan) {
			Map<String, List<LispVal>> assignedValues = new HashMap<>();
			for (LispVal bodyForm : bodyExprs) {
				collectAssignedValues(bodyForm, assignedValues);
			}
			Set<String> seenNames = new HashSet<>();
			Set<String> duplicateNames = new HashSet<>();
			for (LispVal binding : rawScan.toList()) {
				String name = ((LispSymbol) ((LispCons) binding).toList().get(0)).name();
				if (!seenNames.add(name)) {
					duplicateNames.add(name);
				}
			}
			for (LispVal binding : rawScan.toList()) {
				List<LispVal> pairList = ((LispCons) binding).toList();
				String name = ((LispSymbol) pairList.get(0)).name();
				if (ctx.specialVars.contains(name) || capturedInLet.contains(name) || duplicateNames.contains(name)) {
					continue;
				}
				boolean anyRaw = WasmIntFusionCompiler.isRawAssignShaped(pairList.get(1), ctx);
				for (LispVal value : assignedValues.getOrDefault(name, List.of())) {
					anyRaw = anyRaw || WasmIntFusionCompiler.isRawAssignShaped(value, ctx);
				}
				if (anyRaw) {
					rawEligible.add(name);
				}
			}
		}
		Map<String, WasmIntFusionCompiler.RawLocal> rawRegistrations = new HashMap<>();

		// Each dynamic (special) binding established here: {globalIndex, saveSlot}.
		// Restored
		// (reverse order) after the body.
		List<int[]> dynamicRestores = null;
		if (bindings instanceof LispCons bindingsCons) {
			for (LispVal binding : bindingsCons.toList()) {
				LispCons pair = (LispCons) binding;
				List<LispVal> pairList = pair.toList();
				String name = ((LispSymbol) pairList.get(0)).name();
				if (async && ctx.specialVars.contains(name)) {
					throw new UnsupportedOperationException("a dynamic (special) binding of " + name
							+ " around rontolisp:await is not supported on the --component backend"
							+ " (the saved global cannot be restored across a suspension)");
				}
				if (async) {
					compileAsyncBinding(name, pairList.get(1), capturedInLet.contains(name), ctx);
					continue;
				}
				if (ctx.specialVars.contains(name)) {
					// DUAL-BIND (interpreter parity, see JvmLetCompiler): the global is
					// saved and overwritten with the init (the dynamic binding a called
					// function reads), AND the same value gets a lexical slot so a
					// closure built in the body captures it -- the closure may run
					// after this extent ended and restored the global (cl-ppcre's
					// end-string). A setq of the name writes BOTH (WasmSetqCompiler).
					int globalIndex = Objects.requireNonNull(ctx.globalIndices.get(name));
					WasmExprCompiler.compileExpr(pairList.get(1), ctx);
					int dupSlot = ctx.allocTemp();
					ctx.writer.write(Instruction.TEE_LOCAL);
					ctx.writer.writeUnsignedLeb128(dupSlot);
					ctx.writer.write(Instruction.GET_GLOBAL);
					ctx.writer.writeUnsignedLeb128(globalIndex);
					int saveSlot = ctx.allocTemp();
					ctx.writer.write(Instruction.SET_LOCAL);
					ctx.writer.writeUnsignedLeb128(saveSlot);
					ctx.writer.write(Instruction.SET_GLOBAL);
					ctx.writer.writeUnsignedLeb128(globalIndex);
					if (dynamicRestores == null) {
						dynamicRestores = new ArrayList<>();
					}
					dynamicRestores.add(new int[] { globalIndex, saveSlot });
					ctx.specialBindScopes.push(new int[] { globalIndex, saveSlot, ctx.blockMarkers.size() });
					ctx.writer.write(Instruction.GET_LOCAL);
					ctx.writer.writeUnsignedLeb128(dupSlot);
					if (capturedInLet.contains(name)) {
						ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
						ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
					}
					int lexSlot = ctx.allocLocal(name);
					ctx.writer.write(Instruction.SET_LOCAL);
					ctx.writer.writeUnsignedLeb128(lexSlot);
					continue;
				}
				if (rawEligible.contains(name)) {
					// Unboxed dual-representation binding: no ordinary local at all.
					WasmIntFusionCompiler.RawLocal raw = WasmIntFusionCompiler.RawLocal.dual(ctx.allocI64Temp(),
							ctx.allocTemp());
					WasmIntFusionCompiler.compileRawStore(pairList.get(1), ctx, raw);
					rawRegistrations.put(name, raw);
					continue;
				}
				WasmExprCompiler.compileExpr(pairList.get(1), ctx);
				if (capturedInLet.contains(name)) {
					// Box in a cell
					ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
					ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
				}
				int slot = ctx.allocLocal(name);
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeUnsignedLeb128(slot);
			}
		}

		// Register let-bound local functions (the __FLETn_f lambdas the flet lowering
		// produces, .kb/flet-labels.md) whose bodies are closed integer-operation
		// trees, so fused sites in the body substitute them instead of paying the
		// funcall dispatch + box round trip (todo 194 stage 3). The binding is
		// immutable by construction (generated unique names, and the lowering never
		// assigns them); the setf-family scan guards against a hand-written collision.
		// Shadowed outer registrations are removed whatever the new binding's shape.
		Map<String, WasmIntFusionCompiler.LocalIntLambda> savedLocalLambdas = ctx.localIntLambdas;
		Map<String, WasmIntFusionCompiler.LocalIntLambda> newLocalLambdas = null;
		for (String name : letVarNames) {
			if (savedLocalLambdas.containsKey(name)) {
				if (newLocalLambdas == null) {
					newLocalLambdas = new HashMap<>(savedLocalLambdas);
				}
				newLocalLambdas.remove(name);
			}
		}
		if (!ctx.dynamic && !async && bindings instanceof LispCons lambdaBindings) {
			Set<String> assignedInBody = null;
			for (LispVal binding : lambdaBindings.toList()) {
				List<LispVal> pairList = ((LispCons) binding).toList();
				String name = ((LispSymbol) pairList.get(0)).name();
				if (!name.startsWith("__FLET") || ctx.specialVars.contains(name)
						|| !(pairList.get(1) instanceof LispCons init)) {
					continue;
				}
				WasmIntFusionCompiler.LocalIntLambda lambda = WasmIntFusionCompiler.eligibleLocalLambda(init, ctx);
				if (lambda == null) {
					continue;
				}
				if (assignedInBody == null) {
					assignedInBody = new HashSet<>();
					for (LispVal bodyForm : bodyExprs) {
						collectAssignedNames(bodyForm, assignedInBody);
					}
				}
				if (assignedInBody.contains(name)) {
					continue;
				}
				if (newLocalLambdas == null) {
					newLocalLambdas = new HashMap<>(savedLocalLambdas);
				}
				newLocalLambdas.put(name, lambda);
			}
		}
		if (newLocalLambdas != null) {
			ctx.localIntLambdas = newLocalLambdas;
		}

		// Register the unboxed bindings for the body (and unregister every outer raw
		// local this let shadows, whatever the new binding's representation).
		Map<String, WasmIntFusionCompiler.RawLocal> savedRawLocals = ctx.rawLocals;
		Map<String, WasmIntFusionCompiler.RawLocal> newRawLocals = null;
		for (String name : letVarNames) {
			if (savedRawLocals.containsKey(name)) {
				if (newRawLocals == null) {
					newRawLocals = new HashMap<>(savedRawLocals);
				}
				newRawLocals.remove(name);
			}
		}
		if (!rawRegistrations.isEmpty()) {
			if (newRawLocals == null) {
				newRawLocals = new HashMap<>(savedRawLocals);
			}
			newRawLocals.putAll(rawRegistrations);
		}
		if (newRawLocals != null) {
			ctx.rawLocals = newRawLocals;
		}

		// Declared/derived array kinds for the body: a (declare (type <array-spec> v))
		// at the body head pins v's representation (bound OR free names -- a free
		// declaration covers references in this body, which is also where the let*
		// prologue a lambda list generates leaves its parameter declarations); a binding
		// whose INIT already proves a representation (a literal packed make-array, a
		// slot-typed accessor call, a kinded outer variable) derives it with no
		// declaration, but only while the body never reassigns the name -- a declaration
		// needs no such check, it covers assignments too. Shadowed outer entries are
		// removed whatever the new binding proves. Skipped at top level (the eval mirror
		// owns those slots) and in async bodies, like the raw locals.
		Map<String, am.ik.rontolisp.compiler.DeclaredArrayTypes.Kind> savedDeclaredArrays = ctx.declaredArrays;
		Map<String, am.ik.rontolisp.compiler.DeclaredArrayTypes.Kind> newDeclaredArrays = null;
		for (String name : letVarNames) {
			if (savedDeclaredArrays.containsKey(name)) {
				if (newDeclaredArrays == null) {
					newDeclaredArrays = new HashMap<>(savedDeclaredArrays);
				}
				newDeclaredArrays.remove(name);
			}
		}
		if (!ctx.topLevel && !async && ctx.asyncResume == null) {
			Map<String, am.ik.rontolisp.compiler.DeclaredArrayTypes.Kind> declared = am.ik.rontolisp.compiler.DeclaredArrayTypes
				.declaredKinds(bodyExprs, ctx.closRegistry);
			Set<String> assignedInBody = null;
			Map<String, am.ik.rontolisp.compiler.DeclaredArrayTypes.Kind> additions = new HashMap<>();
			for (Map.Entry<String, am.ik.rontolisp.compiler.DeclaredArrayTypes.Kind> entry : declared.entrySet()) {
				if (!letVarNames.contains(entry.getKey()) && !ctx.specialVars.contains(entry.getKey())) {
					// A free declaration: applies to the outer binding within this body.
					additions.put(entry.getKey(), entry.getValue());
				}
			}
			if (bindings instanceof LispCons kindScan) {
				for (LispVal binding : kindScan.toList()) {
					List<LispVal> pairList = ((LispCons) binding).toList();
					String name = ((LispSymbol) pairList.get(0)).name();
					if (ctx.specialVars.contains(name)) {
						continue;
					}
					am.ik.rontolisp.compiler.DeclaredArrayTypes.Kind kind = declared.get(name);
					if (kind == null) {
						kind = WasmArrayCompiler.initExprKind(pairList.get(1), ctx);
						if (kind != null) {
							if (assignedInBody == null) {
								assignedInBody = new HashSet<>();
								for (LispVal bodyForm : bodyExprs) {
									collectAssignedNames(bodyForm, assignedInBody);
								}
							}
							if (assignedInBody.contains(name)) {
								kind = null;
							}
						}
					}
					if (kind != null) {
						additions.put(name, kind);
					}
					else {
						additions.remove(name);
					}
				}
			}
			if (!additions.isEmpty()) {
				if (newDeclaredArrays == null) {
					newDeclaredArrays = new HashMap<>(savedDeclaredArrays);
				}
				newDeclaredArrays.putAll(additions);
			}
		}
		if (newDeclaredArrays != null) {
			ctx.declaredArrays = newDeclaredArrays;
		}

		// Save and adjust boxedVars for the let body. The set tracks names, so each
		// binding's boxedness must REPLACE a shadowed outer binding's: a raw value
		// stored under a name whose outer binding was boxed would otherwise be
		// cell-read in the body.
		Set<String> savedBoxed = ctx.boxedVars;
		Set<String> newBoxed = new HashSet<>(savedBoxed);
		newBoxed.removeAll(letVarNames);
		newBoxed.addAll(capturedInLet);
		ctx.boxedVars = newBoxed;

		if (async) {
			WasmAsyncEmit.compileGuardedProgn(parts.subList(2, parts.size()), ctx);
		}
		else {
			// Non-tail statements compile for effect: a statement-position setq of an
			// unboxed local (or a packed-array setf) then materializes no value.
			for (int i = 2; i < parts.size(); i++) {
				if (i < parts.size() - 1) {
					WasmExprCompiler.compileForEffect(parts.get(i), ctx);
				}
				else {
					WasmExprCompiler.compileExpr(parts.get(i), ctx);
				}
			}
		}

		// Restore each dynamically bound special to its saved value. Runs with the body's
		// result on top of the stack; each restore is stack-neutral (local.get;
		// global.set).
		if (dynamicRestores != null) {
			for (int i = dynamicRestores.size() - 1; i >= 0; i--) {
				int[] restore = dynamicRestores.get(i);
				ctx.writer.write(Instruction.GET_LOCAL);
				ctx.writer.writeUnsignedLeb128(restore[1]);
				ctx.writer.write(Instruction.SET_GLOBAL);
				ctx.writer.writeUnsignedLeb128(restore[0]);
				ctx.specialBindScopes.pop();
			}
		}

		ctx.boxedVars = savedBoxed;
		ctx.locals = savedLocals;
		ctx.localIntLambdas = savedLocalLambdas;
		ctx.rawLocals = savedRawLocals;
		ctx.declaredArrays = savedDeclaredArrays;
		ctx.nextI64Local = savedNextI64Local;
	}

	/**
	 * Collects the value expression of every {@code setq}/{@code setf} pair whose
	 * assignment target is a plain symbol, anywhere in the tree (shadowing-blind: an
	 * inner binding's setq also lands here, which only widens the eligibility heuristic,
	 * never the compiled scoping). Feeds the unboxed-local eligibility check above.
	 */
	private static void collectAssignedValues(LispVal form, Map<String, List<LispVal>> out) {
		if (!(form instanceof LispCons cons)) {
			return;
		}
		if (cons.car() instanceof LispSymbol head && cons.isProperList()) {
			String name = head.name();
			if (LispNames.SETQ.equals(name) || LispNames.SETF.equals(name)) {
				List<LispVal> parts = cons.toList();
				for (int i = 1; i + 1 < parts.size(); i += 2) {
					if (parts.get(i) instanceof LispSymbol target) {
						out.computeIfAbsent(target.name(), k -> new java.util.ArrayList<>()).add(parts.get(i + 1));
					}
				}
			}
		}
		LispVal cur = cons;
		while (cur instanceof LispCons cell) {
			collectAssignedValues(cell.car(), out);
			cur = cell.cdr();
		}
	}

	/**
	 * Collects every name that appears in an assignment position of a setf-family form
	 * ({@code setq}/{@code psetq}/{@code setf}/{@code psetf}/
	 * {@code multiple-value-setq}) anywhere in the tree -- conservatively including
	 * quoted data. Used to refuse local-function registration for a name that the body
	 * could reassign.
	 */
	private static void collectAssignedNames(LispVal form, Set<String> out) {
		if (!(form instanceof LispCons cons)) {
			return;
		}
		if (cons.car() instanceof LispSymbol head && cons.isProperList()) {
			List<LispVal> parts = cons.toList();
			switch (head.name()) {
				case LispNames.SETQ, LispNames.PSETQ, LispNames.SETF, LispNames.PSETF -> {
					for (int i = 1; i < parts.size(); i += 2) {
						if (parts.get(i) instanceof LispSymbol name) {
							out.add(name.name());
						}
					}
				}
				case LispNames.MULTIPLE_VALUE_SETQ -> {
					if (parts.size() > 1 && parts.get(1) instanceof LispCons names) {
						for (LispVal name : names.toList()) {
							if (name instanceof LispSymbol sym) {
								out.add(sym.name());
							}
						}
					}
				}
				default -> {
				}
			}
		}
		LispVal cur = cons;
		while (cur instanceof LispCons cell) {
			collectAssignedNames(cell.car(), out);
			cur = cell.cdr();
		}
	}

	/**
	 * State-machine mode: one guarded binding -- {@code init; [box]; local.set} runs when
	 * executing normally or when the resume target lies inside the init (it dispatches
	 * there); otherwise it is skipped, the local's value coming back from the frame's
	 * spill restore.
	 */
	private static void compileAsyncBinding(String name, LispVal init, boolean boxed, WasmLispCompiler.Ctx ctx) {
		int n = WasmAwaitAnalysis.countAwaits(init);
		// The slot is reserved up front (the guard needs it) but the NAME binds only
		// after the init compiles, so the init still sees an outer binding of the same
		// name (let, not let*).
		int slot = ctx.allocTemp();
		int lo = java.util.Objects.requireNonNull(ctx.asyncResume).nextState;
		if (n == 0) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeUnsignedLeb128(WasmAsyncEmit.RT_SLOT);
			ctx.writer.write(Instruction.I32_EQZ);
			ctx.writer.write(Instruction.IF, WasmLispCompiler.BLOCKTYPE_EMPTY);
		}
		else {
			ctx.writer.write(Instruction.BLOCK, WasmLispCompiler.BLOCKTYPE_EMPTY);
		}
		ctx.wasmCtrlDepth++;
		if (n > 0) {
			WasmAsyncEmit.emitRangeGuard(ctx, lo, lo + n - 1);
			ctx.writer.write(Instruction.I32_EQZ);
			ctx.writer.write(Instruction.BR_IF, 0);
		}
		WasmAsyncEmit.spine(init, ctx);
		if (boxed) {
			ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			ctx.writer.writeUnsignedLeb128(WasmLispCompiler.TYPE_CELL);
		}
		ctx.writer.write(Instruction.SET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		ctx.wasmCtrlDepth--;
		ctx.writer.write(Instruction.END);
		ctx.locals.put(name, slot);
		if (n > 0) {
			WasmAsyncEmit.assertStates(ctx, lo, n, init);
		}
	}

}

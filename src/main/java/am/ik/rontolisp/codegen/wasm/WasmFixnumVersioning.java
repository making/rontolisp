package am.ik.rontolisp.codegen.wasm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.FreeVarAnalyzer;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import org.jspecify.annotations.Nullable;

/**
 * Compiles {@code (if (and (integerp a) (integerp b) rest..) then else)} twice at the
 * speed levels ({@code .kb/wasm-int-fusion.md}, "An integer test compiled twice"): once
 * for the run in which every tested variable holds a fixnum, where each is an exact
 * unboxed local of the i31 range ({@link WasmIntFusionCompiler.RawLocal#exact}) over the
 * rest of the test and the then-arm, and once as written for any other value. What the
 * test proved is otherwise asked again by every operator that reads the variable -- the
 * fused tree's leaf guard, the comparison's, the subscript's -- about a dozen
 * instructions each time, and a sum of two such variables is one call of the generic
 * addition where the fixnum copy is an {@code i64.add} with no check.
 *
 * <pre>
 * block $end (result eqref)
 *   block $else
 *     block $miss (result eqref)
 *       a; br_on_cast_fail $miss i31; i31.get_s; i64.extend; local.set a'   ;; each variable
 *       rest (complemented); br_if $else
 *       then; br $end
 *     end
 *     drop
 *     test (complemented); br_if $else
 *     then; br $end
 *   end
 *   else
 * end
 * </pre>
 *
 * <p>
 * A variable qualifies while it is an ordinary local of the function (no cell, no
 * special, not unboxed already) that the copied forms neither assign nor capture, and the
 * copied forms -- the rest of the test and the then-arm -- are small: they are emitted
 * twice. Not at the size level, under {@code --dynamic}, in an async body or at top
 * level, where no unboxed local is made either ({@link WasmLetCompiler}); and not inside
 * the copy for any other value, so a nest of such tests is copied once per level.
 */
final class WasmFixnumVersioning {

	/**
	 * The most conses the copied forms may hold: what a tested operator, an element
	 * access or a comparison takes, and far below what would be felt in a body's size.
	 */
	private static final int MAX_COPIED_CONSES = 32;

	private WasmFixnumVersioning() {
	}

	/**
	 * An {@code if} to compile twice.
	 *
	 * @param test the test as written
	 * @param names the variables its {@code integerp} conjuncts test
	 * @param rest the conjuncts that test nothing of them, in order
	 * @param thenForm the then-arm
	 * @param elseForm the else-arm, or null for none
	 */
	record Plan(LispVal test, List<String> names, List<LispVal> rest, LispVal thenForm, @Nullable LispVal elseForm) {
	}

	/**
	 * The plan for {@code form}, or null when it is compiled once.
	 * @param form an {@code if} form
	 * @param ctx the compile context
	 * @return the plan or null
	 */
	static @Nullable Plan plan(LispCons form, WasmLispCompiler.Ctx ctx) {
		if (ctx.declinesFixnumVersions || ctx.dynamic || ctx.topLevel || ctx.asyncResume != null
				|| !WasmIntFusionCompiler.speedTradesEnabled(ctx) || !form.isProperList()) {
			return null;
		}
		List<LispVal> parts = form.toList();
		if (parts.size() < 3 || parts.size() > 4) {
			return null;
		}
		LispVal test = parts.get(1);
		List<LispVal> conjuncts = conjuncts(test);
		if (conjuncts == null) {
			return null;
		}
		Set<String> names = new LinkedHashSet<>();
		List<LispVal> rest = new ArrayList<>();
		for (LispVal conjunct : conjuncts) {
			String name = testedLocal(conjunct, ctx);
			if (name == null) {
				rest.add(conjunct);
			}
			else {
				names.add(name);
			}
		}
		if (names.isEmpty()) {
			return null;
		}
		List<LispVal> copied = new ArrayList<>(rest);
		copied.add(parts.get(2));
		int[] conses = new int[1];
		Set<String> read = new HashSet<>();
		for (LispVal copy : copied) {
			if (!measure(copy, conses, read)) {
				return null;
			}
		}
		names.retainAll(read);
		Set<String> assigned = new HashSet<>();
		for (LispVal copy : copied) {
			WasmLetCompiler.collectAssignedNames(copy, assigned);
		}
		names.removeAll(assigned);
		if (names.isEmpty()) {
			return null;
		}
		names.removeAll(FreeVarAnalyzer.findCapturedVars(copied, names, ctx.functions.keySet(), ctx.captureMemo));
		if (names.isEmpty()) {
			return null;
		}
		// A test of a variable the copy does not unbox stays in the copy's test.
		List<LispVal> copiedRest = new ArrayList<>();
		for (LispVal conjunct : conjuncts) {
			String name = testedLocal(conjunct, ctx);
			if (name == null || !names.contains(name)) {
				copiedRest.add(conjunct);
			}
		}
		return new Plan(test, List.copyOf(names), copiedRest, parts.get(2), parts.size() > 3 ? parts.get(3) : null);
	}

	// The conjuncts of a test: its own operands for an `and`, itself for an `integerp`.
	private static @Nullable List<LispVal> conjuncts(LispVal test) {
		if (!(test instanceof LispCons cons) || !cons.isProperList() || !(cons.car() instanceof LispSymbol head)) {
			return null;
		}
		if (LispNames.AND.equals(head.name())) {
			List<LispVal> parts = cons.toList();
			return parts.subList(1, parts.size());
		}
		return LispNames.INTEGERP.equals(head.name()) ? List.of(test) : null;
	}

	// The variable of an (integerp v) over an ordinary local of this function, or null.
	private static @Nullable String testedLocal(LispVal conjunct, WasmLispCompiler.Ctx ctx) {
		if (!(conjunct instanceof LispCons cons) || !cons.isProperList() || !(cons.car() instanceof LispSymbol head)
				|| !LispNames.INTEGERP.equals(head.name())) {
			return null;
		}
		List<LispVal> parts = cons.toList();
		if (parts.size() != 2 || !(parts.get(1) instanceof LispSymbol variable)) {
			return null;
		}
		String name = variable.name();
		return ctx.locals.containsKey(name) && !ctx.boxedVars.contains(name) && !ctx.specialVars.contains(name)
				&& !ctx.rawLocals.containsKey(name) ? name : null;
	}

	// Counts the conses of a form and collects the symbols it holds; false once the
	// count passes the limit.
	private static boolean measure(LispVal form, int[] conses, Set<String> symbols) {
		LispVal cur = form;
		while (cur instanceof LispCons cons) {
			if (++conses[0] > MAX_COPIED_CONSES) {
				return false;
			}
			if (cons.car() instanceof LispSymbol symbol) {
				symbols.add(symbol.name());
			}
			else if (!measure(cons.car(), conses, symbols)) {
				return false;
			}
			cur = cons.cdr();
		}
		if (cur instanceof LispSymbol symbol) {
			symbols.add(symbol.name());
		}
		return true;
	}

	/**
	 * Compiles the planned {@code if}, leaving its value, or nothing when
	 * {@code forEffect}.
	 * @param plan the plan
	 * @param ctx the compile context
	 * @param tail whether the arms are in tail position
	 * @param forEffect whether the value is discarded
	 */
	static void compile(Plan plan, WasmLispCompiler.Ctx ctx, boolean tail, boolean forEffect) {
		compile(plan, ctx, forEffect ? Position.EFFECT : tail ? Position.TAIL : Position.VALUE);
	}

	/**
	 * Compiles the planned {@code if} as a test: an i32 that is non-0 exactly when its
	 * value is true (false when {@code negated}), each arm a test itself
	 * ({@link WasmConditionCompiler}).
	 * @param plan the plan
	 * @param ctx the compile context
	 * @param negated whether to answer the complement
	 */
	static void compileTest(Plan plan, WasmLispCompiler.Ctx ctx, boolean negated) {
		compile(plan, ctx, negated ? Position.FALSITY : Position.TRUTH);
	}

	/** Where the {@code if} stands, which is what its arms leave. */
	private enum Position {

		VALUE, TAIL, EFFECT, TRUTH, FALSITY

	}

	private static void compile(Plan plan, WasmLispCompiler.Ctx ctx, Position position) {
		am.ik.wasm.WasmWriter w = ctx.writer;
		w.write(Instruction.BLOCK);
		switch (position) {
			case EFFECT -> w.write(WasmLispCompiler.BLOCKTYPE_EMPTY);
			case TRUTH, FALSITY -> w.write(Type.I32);
			default -> w.writeRefType(true, Type.EQ.code());
		}
		w.write(Instruction.BLOCK, WasmLispCompiler.BLOCKTYPE_EMPTY);
		w.write(Instruction.BLOCK);
		w.writeRefType(true, Type.EQ.code());
		ctx.wasmCtrlDepth += 3;

		// The copy for fixnums: each variable an exact unboxed local over it.
		Map<String, Integer> savedLocals = ctx.locals;
		Map<String, WasmIntFusionCompiler.RawLocal> savedRawLocals = ctx.rawLocals;
		Map<String, am.ik.rontolisp.compiler.DeclaredArrayTypes.Kind> savedDeclaredArrays = ctx.declaredArrays;
		Set<String> savedArrayLocals = ctx.arrayLocals;
		Map<String, WasmIntFusionCompiler.LocalIntLambda> savedLocalLambdas = ctx.localIntLambdas;
		int savedNextI64Local = ctx.nextI64Local;
		Map<String, Integer> locals = new HashMap<>(savedLocals);
		Map<String, WasmIntFusionCompiler.RawLocal> rawLocals = new HashMap<>(savedRawLocals);
		Map<String, am.ik.rontolisp.compiler.DeclaredArrayTypes.Kind> declaredArrays = new HashMap<>(
				savedDeclaredArrays);
		Set<String> arrayLocals = new HashSet<>(savedArrayLocals);
		Map<String, WasmIntFusionCompiler.LocalIntLambda> localLambdas = new HashMap<>(savedLocalLambdas);
		for (String name : plan.names()) {
			int slot = java.util.Objects.requireNonNull(savedLocals.get(name));
			WasmIntFusionCompiler.RawLocal raw = WasmIntFusionCompiler.RawLocal.exact(ctx.allocI64Temp(),
					WasmBignumRuntimeBuilder.I31_MIN, WasmBignumRuntimeBuilder.I31_MAX);
			w.write(Instruction.GET_LOCAL);
			w.writeUnsignedLeb128(slot);
			w.write(Instruction.GC_PREFIX, Instruction.BR_ON_CAST_FAIL);
			w.write(0x01); // the operand nullable, the cast not
			w.writeUnsignedLeb128(0);
			w.writeHeapType(Type.EQ.code());
			w.writeHeapType(Type.I31.code());
			w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
			w.write(Instruction.I64_EXTEND_S_I32);
			w.write(Instruction.SET_LOCAL);
			ctx.writeI64LocalIndex(raw.i64Slot());
			locals.remove(name);
			rawLocals.put(name, raw);
			declaredArrays.remove(name);
			arrayLocals.remove(name);
			localLambdas.remove(name);
		}
		ctx.locals = locals;
		ctx.rawLocals = rawLocals;
		ctx.declaredArrays = declaredArrays;
		ctx.arrayLocals = arrayLocals;
		ctx.localIntLambdas = localLambdas;
		if (!plan.rest().isEmpty()) {
			ctx.tailPosition = false;
			WasmConditionCompiler.compile(conjunction(plan.rest()), ctx, true);
			w.write(Instruction.BR_IF, 1);
		}
		compileArm(plan.thenForm(), ctx, position);
		w.write(Instruction.BR, 2);
		ctx.locals = savedLocals;
		ctx.rawLocals = savedRawLocals;
		ctx.declaredArrays = savedDeclaredArrays;
		ctx.arrayLocals = savedArrayLocals;
		ctx.localIntLambdas = savedLocalLambdas;
		ctx.nextI64Local = savedNextI64Local;
		w.write(Instruction.END);
		w.write(Instruction.DROP);
		ctx.wasmCtrlDepth--;

		// The copy for any other value: the test and the arm as written.
		boolean savedDeclines = ctx.declinesFixnumVersions;
		ctx.declinesFixnumVersions = true;
		ctx.tailPosition = false;
		WasmConditionCompiler.compile(plan.test(), ctx, true);
		w.write(Instruction.BR_IF, 0);
		compileArm(plan.thenForm(), ctx, position);
		ctx.declinesFixnumVersions = savedDeclines;
		w.write(Instruction.BR, 1);
		w.write(Instruction.END);
		ctx.wasmCtrlDepth--;

		compileArm(plan.elseForm() == null ? LispNil.INSTANCE : plan.elseForm(), ctx, position);
		w.write(Instruction.END);
		ctx.wasmCtrlDepth--;
	}

	private static void compileArm(LispVal form, WasmLispCompiler.Ctx ctx, Position position) {
		ctx.tailPosition = position == Position.TAIL;
		switch (position) {
			case EFFECT -> WasmExprCompiler.compileForEffect(form, ctx);
			case TRUTH -> WasmConditionCompiler.compile(form, ctx, false);
			case FALSITY -> WasmConditionCompiler.compile(form, ctx, true);
			default -> WasmExprCompiler.compileExpr(form, ctx);
		}
	}

	private static LispVal conjunction(List<LispVal> conjuncts) {
		if (conjuncts.size() == 1) {
			return conjuncts.get(0);
		}
		LispVal tail = LispNil.INSTANCE;
		for (int i = conjuncts.size() - 1; i >= 0; i--) {
			tail = new LispCons(conjuncts.get(i), tail);
		}
		return new LispCons(new LispSymbol(LispNames.AND), tail);
	}

}

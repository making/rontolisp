package am.ik.rontolisp.codegen.wasm;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.macro.LispMacroExpander;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;

/**
 * Compiles a LEAF defun twice into one function ({@code .kb/wasm-int-fusion.md}, "A leaf
 * compiled twice"). A program holding {@code eval} gives every defun a cell
 * ({@link WasmFunctionCells}), so a fused site over ironclad's {@code mod32+} checks the
 * cell and keeps a fallback calling through it
 * ({@code WasmIntFusionCompiler.CelledCall}), and it can name a variable at run time, so
 * no constant is known to hold its packed vector
 * ({@link WasmArrayCompiler#constantArrays}): no exact local, no site free of bails, no
 * array parameter, no direct read of a table. A leaf runs nothing but arithmetic, array
 * and cons operations, calls of the fusion-inlinable defuns, whose compiled definitions
 * are closed integer trees, and calls of the local functions it binds: it can neither run
 * user code, nor write a cell or a variable by name, and an error it signals never
 * returns into it. What holds when it starts holds until it returns, so the function
 * tests once that every cell of a name it calls is empty and that every such constant's
 * global holds a vector of its kind and length, and then runs the body compiled on those
 * facts ({@code Ctx.uncelled}, the constants joining {@code Ctx.constantArrays});
 * otherwise the body with the speed trades declined, as at {@code --optimize=size}
 * ({@code Ctx.declinesSpeedTrades}), the small copy: ironclad's SHA-256 block is 28 KB of
 * the 60 KB the two take, where its one copy over the cells took 156 KB.
 */
final class WasmLeafVersioning {

	/**
	 * The operators whose compiled form evaluates the arguments and runs nothing else
	 * that can run user code or write a function cell: arithmetic and comparison, the
	 * array access a {@code setf} of {@code aref} expands to, conses,
	 * {@code eq}/{@code eql}, {@code values}.
	 */
	private static final Set<String> PURE_OPERATORS = Set.of(LispNames.ADD, LispNames.SUB, LispNames.MUL,
			LispNames.ONE_PLUS, LispNames.ONE_MINUS, LispNames.MOD, LispNames.REM, LispNames.LOGAND, LispNames.LOGIOR,
			LispNames.LOGXOR, LispNames.LOGNOT, LispNames.ASH, LispNames.LDB, LispNames.DPB, LispNames.BYTE,
			LispNames.ABS, LispNames.MIN, LispNames.MAX, LispNames.ZEROP, LispNames.PLUSP, LispNames.MINUSP,
			LispNames.EVENP, LispNames.ODDP, LispNames.EQ, LispNames.NE, LispNames.LT, LispNames.GT, LispNames.LE,
			LispNames.GE, LispNames.NOT, LispNames.NULL, LispNames.EQ_GENERAL, LispNames.EQL, LispNames.STRINGP,
			LispNames.AREF, LispNames.ASET, LispNames.SCHAR_SET, LispNames.CONS, LispNames.CAR, LispNames.CDR,
			LispNames.LIST, LispNames.VALUES);

	/**
	 * The forms that evaluate every element after the head, a name or a tag there being a
	 * symbol the scan passes over.
	 */
	private static final Set<String> SEQUENCING = Set.of(LispNames.PROGN, LispNames.PROG1, LispNames.PROG2,
			LispNames.IF, LispNames.WHEN, LispNames.UNLESS, LispNames.AND, LispNames.OR, LispNames.WHILE,
			LispNames.BLOCK, LispNames.BLOCK_INTERNAL, LispNames.FN_BLOCK_INTERNAL, LispNames.RETURN_FROM,
			LispNames.RETURN, LispNames.TAGBODY, LispNames.GO, LispNames.LOCALLY);

	/**
	 * What a leaf's first copy is compiled on, tested when the function starts.
	 *
	 * @param cells the inlinable defuns with a cell it may call, compiled as if they had
	 * none
	 * @param constants the constants it reads as the packed vector their definition
	 * builds
	 */
	record Assumptions(Set<String> cells, Map<String, WasmArrayCompiler.ConstantArray> constants) {

		static final Assumptions NONE = new Assumptions(Set.of(), Map.of());

		boolean isEmpty() {
			return this.cells.isEmpty() && this.constants.isEmpty();
		}

	}

	private WasmLeafVersioning() {
	}

	/**
	 * What a defun's body is compiled twice over when it is a leaf: every inlinable defun
	 * with a cell it may call, directly or through an inlinable body or a local
	 * function's, and every constant of {@code Ctx.leafConstantArrays} it names.
	 * {@link Assumptions#NONE} when it is no leaf or has neither. For a leaf, the
	 * {@code flet} expansions the scan made stay in {@code Ctx.fletExpansions} for the
	 * copies to compile.
	 * @param body the defun's body forms
	 * @param ctx the defun's compile context
	 * @return the assumptions, each set in the order the body first names its members
	 */
	static Assumptions assumptions(List<LispVal> body, WasmLispCompiler.Ctx ctx) {
		if (!WasmIntFusionCompiler.speedTradesEnabled(ctx) || ctx.asyncResume != null || ctx.dynamic
				|| ctx.functionCells.isEmpty() && ctx.leafConstantArrays.isEmpty()) {
			return Assumptions.NONE;
		}
		Scan scan = new Scan(ctx);
		ctx.fletExpansions = new java.util.IdentityHashMap<>();
		try {
			for (LispVal form : body) {
				if (!scan.form(form, Set.of())) {
					ctx.fletExpansions = null;
					return Assumptions.NONE;
				}
			}
		}
		catch (RuntimeException malformed) {
			// A form an expander refuses is no leaf: the compile meets it where the
			// refusal is reported with its position.
			ctx.fletExpansions = null;
			return Assumptions.NONE;
		}
		Assumptions assumptions = new Assumptions(scan.cells, scan.constants);
		if (assumptions.isEmpty()) {
			ctx.fletExpansions = null;
		}
		return assumptions;
	}

	/**
	 * Emits the body twice: {@code if} every assumption holds, compiled on them,
	 * {@code else} with the speed trades declined. Each copy compiles the whole body, its
	 * last form in tail position; a lambda in it registers once
	 * ({@code Ctx.lambdaRegistrations}).
	 * @param assumptions what {@link #assumptions} answered
	 * @param body what compiles the body forms
	 * @param ctx the defun's compile context
	 */
	static void compile(Assumptions assumptions, Runnable body, WasmLispCompiler.Ctx ctx) {
		boolean first = true;
		for (String name : assumptions.cells()) {
			ctx.writer.write(Instruction.GET_GLOBAL);
			ctx.writer.writeUnsignedLeb128(Objects.requireNonNull(ctx.functions.get(name)).cellGlobal());
			ctx.writer.write(Instruction.REF_IS_NULL);
			if (!first) {
				ctx.writer.write(Instruction.I32_AND);
			}
			first = false;
		}
		for (Map.Entry<String, WasmArrayCompiler.ConstantArray> constant : assumptions.constants().entrySet()) {
			emitHoldsVector(Objects.requireNonNull(ctx.globalIndices.get(constant.getKey())), constant.getValue(), ctx);
			if (!first) {
				ctx.writer.write(Instruction.I32_AND);
			}
			first = false;
		}
		Map<String, WasmArrayCompiler.ConstantArray> constantArrays = ctx.constantArrays;
		Map<String, WasmArrayCompiler.ConstantArray> known = new HashMap<>(constantArrays);
		known.putAll(assumptions.constants());
		ctx.lambdaRegistrations = new java.util.IdentityHashMap<>();
		ctx.writer.write(Instruction.IF);
		ctx.writer.writeRefType(true, Type.EQ.code());
		ctx.wasmCtrlDepth++;
		try {
			ctx.uncelled = assumptions.cells();
			ctx.constantArrays = known;
			body.run();
			ctx.uncelled = Set.of();
			ctx.constantArrays = constantArrays;
			ctx.writer.write(Instruction.ELSE);
			ctx.declinesSpeedTrades = true;
			body.run();
		}
		finally {
			ctx.uncelled = Set.of();
			ctx.constantArrays = constantArrays;
			ctx.declinesSpeedTrades = false;
			ctx.wasmCtrlDepth--;
			ctx.lambdaRegistrations = null;
			ctx.fletExpansions = null;
		}
		ctx.writer.write(Instruction.END);
	}

	/**
	 * The i32 truth of "the global holds a packed vector of the constant's kind, at least
	 * as long as the one its definition builds": a fused read of it then needs neither
	 * the test nor a bound check its range rules out.
	 */
	private static void emitHoldsVector(int global, WasmArrayCompiler.ConstantArray constant,
			WasmLispCompiler.Ctx ctx) {
		int type = WasmArrayCompiler.intArrType(constant.kind().packedIntWidth());
		ctx.writer.write(Instruction.GET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(global);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		ctx.writer.writeHeapType(type);
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.I32);
		ctx.writer.write(Instruction.GET_GLOBAL);
		ctx.writer.writeUnsignedLeb128(global);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(type);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(constant.length());
		ctx.writer.write(Instruction.I32_GE_U);
		ctx.writer.write(Instruction.ELSE);
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(0);
		ctx.writer.write(Instruction.END);
	}

	/**
	 * The walk deciding whether a body is a leaf, over the forms the compilers meet: a
	 * built-in macro expands as they expand it, anything it does not know is no leaf.
	 */
	private static final class Scan {

		private final WasmLispCompiler.Ctx ctx;

		/** The inlinable defuns with a cell the body may call. */
		final Set<String> cells = new LinkedHashSet<>();

		/** The constants of {@code Ctx.leafConstantArrays} the body names. */
		final Map<String, WasmArrayCompiler.ConstantArray> constants = new LinkedHashMap<>();

		/** The inlinable defuns whose bodies were walked for their callees. */
		private final Set<String> walked = new HashSet<>();

		Scan(WasmLispCompiler.Ctx ctx) {
			this.ctx = ctx;
		}

		/**
		 * Whether evaluating {@code form} runs nothing outside the leaf's own code.
		 * {@code locals} are the variables in scope bound to a {@code lambda} the scan
		 * has walked and nothing assigns: what a {@code funcall} may call.
		 */
		boolean form(LispVal form, Set<String> locals) {
			if (!(form instanceof LispCons cons)) {
				if (form instanceof LispSymbol symbol && this.ctx.leafConstantArrays
					.get(symbol.name()) instanceof WasmArrayCompiler.ConstantArray array) {
					this.constants.put(symbol.name(), array);
				}
				return true;
			}
			if (!cons.isProperList() || !(cons.car() instanceof LispSymbol head)) {
				return false;
			}
			List<LispVal> parts = cons.toList();
			String op = head.name();
			if (SEQUENCING.contains(op)) {
				return forms(parts.subList(1, parts.size()), locals);
			}
			switch (op) {
				case LispNames.DECLARE -> {
					return true;
				}
				case LispNames.QUOTE -> {
					// An aggregate datum is one constant per quote site, which a site
					// compiled twice would split in two (.kb/quoted-data.md).
					return parts.size() == 2 && (parts.get(1) instanceof LispSymbol || parts.get(1) instanceof LispNil
							|| parts.get(1) instanceof LispTrue || parts.get(1) instanceof LispInteger);
				}
				case LispNames.THE -> {
					return parts.size() == 3 && form(parts.get(2), locals);
				}
				case LispNames.COND -> {
					for (LispVal clause : parts.subList(1, parts.size())) {
						if (!(clause instanceof LispCons clauseCons) || !clauseCons.isProperList()
								|| !forms(clauseCons.toList(), locals)) {
							return false;
						}
					}
					return true;
				}
				case LispNames.SETQ, LispNames.PSETQ -> {
					for (int i = 2; i < parts.size(); i += 2) {
						if (!form(parts.get(i), locals)) {
							return false;
						}
					}
					return true;
				}
				case LispNames.SETF -> {
					LispVal expansion = LispMacroExpander.expandSetf(cons, this.ctx.structAccessors,
							this.ctx.closRegistry);
					return expansion != cons && form(expansion, locals);
				}
				case LispNames.LOOP -> {
					return form(LispMacroExpander.expandLoop(cons), locals);
				}
				case LispNames.DOTIMES -> {
					// (dotimes (var count [result]) body...): the count outside the
					// variable's scope, the result and the body inside it.
					if (parts.size() < 2 || !(parts.get(1) instanceof LispCons spec) || !spec.isProperList()
							|| !(spec.car() instanceof LispSymbol variable)) {
						return false;
					}
					List<LispVal> specParts = spec.toList();
					Set<String> inner = new HashSet<>(locals);
					inner.remove(variable.name());
					return specParts.size() >= 2 && form(specParts.get(1), locals)
							&& forms(specParts.subList(2, specParts.size()), inner)
							&& forms(parts.subList(2, parts.size()), inner);
				}
				case LispNames.FLET -> {
					return form(fletExpansion(cons, this.ctx), locals);
				}
				case LispNames.LET, LispNames.LET_STAR -> {
					return let(parts, LispNames.LET_STAR.equals(op), locals);
				}
				case LispNames.LAMBDA -> {
					return lambda(parts, locals);
				}
				case LispNames.FUNCTION -> {
					return parts.size() == 2 && (parts.get(1) instanceof LispSymbol
							|| parts.get(1) instanceof LispCons lambda && lambda.car() instanceof LispSymbol lambdaHead
									&& LispNames.LAMBDA.equals(lambdaHead.name()) && form(lambda, locals));
				}
				case LispNames.FUNCALL -> {
					return parts.size() >= 2 && parts.get(1) instanceof LispSymbol function
							&& locals.contains(function.name()) && forms(parts.subList(2, parts.size()), locals);
				}
				default -> {
				}
			}
			WasmLispCompiler.DefunDecl inlinable = this.ctx.inlinableDefuns.get(op);
			if (inlinable != null) {
				if (inlinable.paramNames().size() != parts.size() - 1) {
					return false;
				}
				callee(op);
				return forms(parts.subList(1, parts.size()), locals);
			}
			return PURE_OPERATORS.contains(op) && !this.ctx.userDefunNames.contains(op)
					&& forms(parts.subList(1, parts.size()), locals);
		}

		private boolean forms(List<LispVal> forms, Set<String> locals) {
			for (LispVal form : forms) {
				if (!form(form, locals)) {
					return false;
				}
			}
			return true;
		}

		/**
		 * A {@code let} or {@code let*}: the inits in the scope each is evaluated in,
		 * then the body with each binding's name shadowing an outer local function's, and
		 * a binding of a walked {@code lambda} that nothing assigns callable there.
		 */
		private boolean let(List<LispVal> parts, boolean sequential, Set<String> locals) {
			if (parts.size() < 2) {
				return false;
			}
			LispVal bindings = LispMacroExpander.letBindingList(parts.get(1));
			List<LispVal> body = parts.subList(2, parts.size());
			Set<String> inner = new HashSet<>(locals);
			if (bindings instanceof LispCons bindingList) {
				if (!bindingList.isProperList()) {
					return false;
				}
				List<LispVal> all = bindingList.toList();
				for (LispVal binding : all) {
					if (!(binding instanceof LispCons pair) || !pair.isProperList()
							|| !(pair.car() instanceof LispSymbol name)) {
						return false;
					}
					List<LispVal> pairParts = pair.toList();
					LispVal init = pairParts.size() > 1 ? pairParts.get(1) : LispNil.INSTANCE;
					if (!form(init, sequential ? inner : locals)) {
						return false;
					}
					inner.remove(name.name());
					if (init instanceof LispCons initCons && initCons.car() instanceof LispSymbol initHead
							&& LispNames.LAMBDA.equals(initHead.name()) && !this.ctx.specialVars.contains(name.name())
							&& !WasmCountedLoopCompiler.assignsName(body, name.name())
							&& !WasmCountedLoopCompiler.assignsName(all, name.name())) {
						inner.add(name.name());
					}
				}
			}
			else if (!(bindings instanceof LispNil)) {
				return false;
			}
			return forms(body, inner);
		}

		/**
		 * A {@code lambda}: its body, which a {@code funcall} of a local function runs
		 * while the leaf does. Plain required parameters only, each shadowing an outer
		 * local function.
		 */
		private boolean lambda(List<LispVal> parts, Set<String> locals) {
			if (parts.size() < 2) {
				return false;
			}
			Set<String> inner = new HashSet<>(locals);
			if (parts.get(1) instanceof LispCons params) {
				if (!params.isProperList()) {
					return false;
				}
				for (LispVal param : params.toList()) {
					if (!(param instanceof LispSymbol symbol) || symbol.isLambdaListKeyword()) {
						return false;
					}
					inner.remove(symbol.name());
				}
			}
			else if (!(parts.get(1) instanceof LispNil)) {
				return false;
			}
			return forms(parts.subList(2, parts.size()), inner);
		}

		/**
		 * Notes an inlinable defun the body calls, and the ones its body calls: a call
		 * that is not substituted runs its compiled definition through the named entry,
		 * which runs what a write left in the cell once it is not empty.
		 */
		private void callee(String name) {
			if (!this.walked.add(name)) {
				return;
			}
			WasmLispCompiler.WasmFunctionInfo function = this.ctx.functions.get(name);
			if (function != null && function.entryIndex() >= 0 && function.cellGlobal() >= 0) {
				this.cells.add(name);
			}
			WasmLispCompiler.DefunDecl defun = this.ctx.inlinableDefuns.get(name);
			LispVal body = defun == null ? null : WasmIntFusionCompiler.inlinableBody(defun);
			if (body != null) {
				callees(body);
			}
		}

		private void callees(LispVal form) {
			if (!(form instanceof LispCons cons) || !cons.isProperList()) {
				return;
			}
			if (cons.car() instanceof LispSymbol head && this.ctx.inlinableDefuns.containsKey(head.name())) {
				callee(head.name());
			}
			for (LispVal part : cons.toList()) {
				callees(part);
			}
		}

	}

	/**
	 * The expansion of a {@code flet} in the defun being compiled: one per form while
	 * {@link #assumptions} decides and the copies compile, so all of them meet the same
	 * {@code lambda} conses, which is what lets a copy reuse the other's registration of
	 * one.
	 * @param cons the {@code flet} form
	 * @param ctx the compile context
	 * @return the expansion
	 */
	static LispVal fletExpansion(LispCons cons, WasmLispCompiler.Ctx ctx) {
		return ctx.fletExpansions == null ? LispMacroExpander.expandFlet(cons)
				: ctx.fletExpansions.computeIfAbsent(cons, form -> LispMacroExpander.expandFlet((LispCons) form));
	}

}

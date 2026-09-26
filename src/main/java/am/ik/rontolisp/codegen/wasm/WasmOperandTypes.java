package am.ik.rontolisp.codegen.wasm;

import am.ik.rontolisp.compiler.OperandTypes;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;
import org.jspecify.annotations.Nullable;

/**
 * The wasm-GC half of {@link OperandTypes}: a wrong-type operand reaching the numeric
 * runtime signals a {@code type-error} reporting {@code OP: The value X is not of type T}
 * (EH mode; outside it the landing traps without a message).
 *
 * <p>
 * The runtime's helpers are shared by many operators, so the operator is known only at
 * the call site. A call compiled inside a named operator's form ({@link #emitCall})
 * stores the operator's id in the operator register ({@code Ctx.operandOpGlobalIndex})
 * for the call's duration and clears it after; the {@code _type_err_*} landings
 * ({@link #buildLandingBody}) read the register and clear it too, so a later failure
 * through a call site that set nothing reports unnamed rather than under a stale
 * operator. A module is one thread of control, so a register cannot be raced.
 *
 * <p>
 * The id indexes the operator table ({@link Operators}), one data blob placed before any
 * body compiles (a string added during emission lands after the data segment is fixed): a
 * 12-byte row {@code {text offset from the blob base, text length, type code}} per
 * operator (row 0 unused), then the quote-framed {@code "OP: "} texts. Type code 1 =
 * {@code INTEGER}, 2 = {@code NUMBER}, 3 = {@code REAL}. The landings cite the blob's
 * base, so it is dropped with them.
 */
final class WasmOperandTypes {

	/** Type codes, as the table holds them and as the landing selects its suffix. */
	private static final int INTEGER_CODE = 1;

	private static final int NUMBER_CODE = 2;

	private static final int REAL_CODE = 3;

	/** Bytes per table row. */
	private static final int ROW = 12;

	/** The landing's {@code (ref null eq)} locals, after the two i32 ones. */
	private static final int MSG_LOCAL = 3;

	private static final int SLOTS_LOCAL = 4;

	/**
	 * Operators a compile-time lowering introduces where the source spelled another name:
	 * a {@code coerce} to {@code real} signals through {@code float}, a one-argument
	 * {@code gcd}/{@code lcm} is {@code abs}.
	 */
	private static final java.util.Map<String, String> LOWERED_TO = java.util.Map.of("COERCE", "FLOAT", "GCD", "ABS",
			"LCM", "ABS");

	private WasmOperandTypes() {
	}

	/**
	 * The module's operator table.
	 *
	 * @param ids operator to its row (1-based)
	 * @param base the blob's absolute address
	 */
	record Operators(java.util.Map<String, Integer> ids, int base) {

		static final Operators NONE = new Operators(java.util.Map.of(), -1);

		/**
		 * Places the table for the operators this module can name: the ones the program
		 * spells, directly or through a rewrite, plus the ordering and {@code + - * /}
		 * family every loop and {@code incf} expansion reaches. An operator missing here
		 * reports unnamed.
		 * @param table the module's string table
		 * @param spelled whether the program spells a name
		 * @return the table
		 */
		static Operators place(WasmLispCompiler.StringTable table, java.util.function.Predicate<String> spelled) {
			java.util.Set<String> wanted = new java.util.LinkedHashSet<>(
					java.util.List.of("+", "-", "*", "/", "=", "<", ">", "<=", ">="));
			for (String op : OperandTypes.operators()) {
				if (spelled.test(op)) {
					wanted.add(op);
				}
			}
			OperandTypes.rewritten().forEach((from, to) -> {
				if (spelled.test(from)) {
					wanted.add(to);
				}
			});
			LOWERED_TO.forEach((from, to) -> {
				if (spelled.test(from)) {
					wanted.add(to);
				}
			});
			java.util.Map<String, Integer> ids = new java.util.HashMap<>();
			java.nio.ByteBuffer rows = java.nio.ByteBuffer.allocate((wanted.size() + 1) * ROW)
				.order(java.nio.ByteOrder.LITTLE_ENDIAN);
			java.io.ByteArrayOutputStream texts = new java.io.ByteArrayOutputStream();
			rows.position(ROW);
			for (String op : wanted) {
				byte[] text = ("\"" + op + OperandTypes.OPERATOR_SEPARATOR + "\"")
					.getBytes(java.nio.charset.StandardCharsets.UTF_8);
				ids.put(op, ids.size() + 1);
				rows.putInt(rows.capacity() + texts.size());
				rows.putInt(text.length);
				rows.putInt(typeCode(java.util.Objects.requireNonNull(OperandTypes.operatorType(op))));
				texts.writeBytes(text);
			}
			java.io.ByteArrayOutputStream blob = new java.io.ByteArrayOutputStream();
			blob.writeBytes(rows.array());
			blob.writeBytes(texts.toByteArray());
			return new Operators(java.util.Map.copyOf(ids), table.appendShakeableBlobProbedOnBase(blob.toByteArray()));
		}

	}

	/**
	 * Emits {@code call func}, under the innermost named operator's register value when
	 * there is one.
	 * @param ctx the emission context
	 * @param func the callee's function index
	 */
	static void emitCall(WasmLispCompiler.Ctx ctx, int func) {
		int id = 0;
		if (ctx.operandOpGlobalIndex >= 0 && mayReject(func)) {
			String op = OperandTypes.reportedOperator(ctx.operator);
			Integer row = op == null ? null : ctx.operandOperators.ids().get(op);
			id = row == null ? 0 : row;
		}
		if (id != 0) {
			setRegister(ctx.writer, ctx.operandOpGlobalIndex, id);
		}
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(func);
		if (id != 0) {
			setRegister(ctx.writer, ctx.operandOpGlobalIndex, 0);
		}
	}

	/**
	 * Whether a helper can meet an operand it rejects. The ones that cannot -- boxing an
	 * i64, the fused fast path's raw i64 operations and the f64 fdlibm kernels -- are the
	 * bulk of the calls a numeric form makes, so leaving them bare keeps the register out
	 * of the hot loops that never needed it.
	 */
	private static boolean mayReject(int func) {
		return func != WasmLispCompiler.FUNC_INT_NEW && func != WasmLispCompiler.FUNC_UB_READ
				&& (func < WasmLispCompiler.FUNC_FX_ADD || func > WasmLispCompiler.FUNC_FX_REM)
				&& (func < WasmLispCompiler.FUNC_FD_BASE || func > WasmLispCompiler.FX_FUNC_LAST);
	}

	/**
	 * Runs an emission under a named operator: the fusion paths re-emit an operator's
	 * helper calls away from its form.
	 * @param ctx the emission context
	 * @param operator the operator
	 * @param emission the emission
	 */
	static void withOperator(WasmLispCompiler.Ctx ctx, String operator, Runnable emission) {
		@Nullable String outer = ctx.operator;
		ctx.operator = operator;
		try {
			emission.run();
		}
		finally {
			ctx.operator = outer;
		}
	}

	private static void setRegister(WasmWriter w, int global, int value) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(value);
		w.write(Instruction.SET_GLOBAL);
		w.writeUnsignedLeb128(global);
	}

	private static int typeCode(String type) {
		if (OperandTypes.Kind.INTEGER.name().equals(type)) {
			return INTEGER_CODE;
		}
		return OperandTypes.Kind.NUMBER.name().equals(type) ? NUMBER_CODE : REAL_CODE;
	}

	/**
	 * The quote-framed texts the landings cite, interned before any body compiles.
	 *
	 * @param valuePrefix {@code "The value "}
	 * @param integerSuffix {@code " is not of type INTEGER"}
	 * @param numberSuffix {@code " is not of type NUMBER"}
	 * @param realSuffix {@code " is not of type REAL"}
	 * @param typeNames the three type symbols a {@code type-error}'s
	 * {@code expected-type} holds, or null when the module builds none
	 */
	record Texts(WasmLispCompiler.StringTable.StringEntry valuePrefix,
			WasmLispCompiler.StringTable.StringEntry integerSuffix,
			WasmLispCompiler.StringTable.StringEntry numberSuffix, WasmLispCompiler.StringTable.StringEntry realSuffix,
			@Nullable TypeNames typeNames) {

		/**
		 * Interns the texts.
		 * @param table the module's string table
		 * @param typeError whether the landings build a {@code type-error}
		 * @return the texts
		 */
		static Texts intern(WasmLispCompiler.StringTable table, boolean typeError) {
			return new Texts(table.addBodyString("\"" + OperandTypes.VALUE_PREFIX + "\""),
					table.addBodyString("\"" + OperandTypes.TYPE_INFIX + OperandTypes.Kind.INTEGER.name() + "\""),
					table.addBodyString("\"" + OperandTypes.TYPE_INFIX + OperandTypes.Kind.NUMBER.name() + "\""),
					table.addBodyString("\"" + OperandTypes.TYPE_INFIX + OperandTypes.Kind.REAL.name() + "\""),
					typeError ? new TypeNames(table.addBodyString(OperandTypes.Kind.INTEGER.name()),
							table.addBodyString(OperandTypes.Kind.NUMBER.name()),
							table.addBodyString(OperandTypes.Kind.REAL.name())) : null);
		}

	}

	/**
	 * The type symbols, interned as the names themselves: a symbol's identity is its
	 * entry, so these are the entries a quoted {@code 'number} in the program shares, and
	 * {@code eq} holds between the two.
	 *
	 * @param integer {@code INTEGER}
	 * @param number {@code NUMBER}
	 * @param real {@code REAL}
	 */
	record TypeNames(WasmLispCompiler.StringTable.StringEntry integer, WasmLispCompiler.StringTable.StringEntry number,
			WasmLispCompiler.StringTable.StringEntry real) {
	}

	/**
	 * The {@code type-error} instance a landing throws: the class's baked shape and the
	 * slots it fills besides {@code format-control}.
	 *
	 * @param instance the baked layout and slot shape
	 * @param datumSlot the index of {@code DATUM}
	 * @param expectedTypeSlot the index of {@code EXPECTED-TYPE}
	 */
	record TypeErrorShape(WasmRuntimeBuilder.ConditionInstance instance, int datumSlot, int expectedTypeSlot) {
	}

	/**
	 * Builds {@code _type_err_int} / {@code _type_err_num} / {@code _type_err_real}: the
	 * landing for a non-number reaching the arithmetic runtime. Signature
	 * {@code ((ref null eq)) -> ()}; it never returns. In EH mode it renders the report
	 * and throws it on {@code $lisp-cond}, the channel {@code %error-cond} uses, as a
	 * {@code type-error} instance whose {@code datum} is the operand and whose
	 * {@code expected-type} is the type the report names -- or, in a module that did not
	 * bake the class ({@code typeError} null: no handler landing pad, so nothing can
	 * observe the class), as the instance-less {@code (nil . message)} payload the entry
	 * landing pad reports the same way. Outside EH mode it is a bare {@code unreachable}:
	 * no tag exists, and citing the prin1 renderer would pin the printer family into
	 * every module.
	 * @param kind what the funnel was coercing to
	 * @param texts the interned texts, non-null in EH mode
	 * @param operatorTable the operator table's address ({@link Operators#base})
	 * @param operatorGlobal the operator register, or -1 outside EH mode
	 * @param typeError the type-error shape, or null for the instance-less payload
	 * @param identityHash whether a cons carries the identity-hash field
	 * @return the function body
	 */
	static byte[] buildLandingBody(OperandTypes.Kind kind, @Nullable Texts texts, int operatorTable, int operatorGlobal,
			@Nullable TypeErrorShape typeError, boolean identityHash) {
		java.io.ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		if (texts == null || operatorGlobal < 0) {
			w.write(0); // no extra locals
			w.write(Instruction.UNREACHABLE);
			w.write(Instruction.END);
			return body.toByteArray();
		}
		// extra locals: $g (i32) the register's id, then the type code; $row (i32) its
		// table row; with a type-error to build, $msg and $slots ((ref null eq))
		w.writeUnsignedLeb128(typeError == null ? 1 : 2);
		w.writeUnsignedLeb128(2);
		w.write(Type.I32);
		if (typeError != null) {
			w.writeUnsignedLeb128(2);
			w.writeRefType(true, Type.EQ.code());
		}
		w.write(Instruction.GET_GLOBAL);
		w.writeUnsignedLeb128(operatorGlobal);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(1);
		setRegister(w, operatorGlobal, 0);
		// $row = table + g * ROW
		getLocal(w, 1);
		i32Const(w, ROW);
		w.write(Instruction.I32_MUL);
		i32Const(w, operatorTable);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(2);
		if (typeError == null) {
			// payload car: the condition instance slot, nil for a message-only throw
			w.write(Instruction.REF_NULL);
			w.writeHeapType(Type.EQ.code());
		}
		// the head: "The value ", after "OP: " when the register named one
		getLocal(w, 1);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		strBuild(w, texts.valuePrefix());
		w.write(Instruction.ELSE);
		loadRow(w, 0);
		i32Const(w, operatorTable);
		w.write(Instruction.I32_ADD);
		loadRow(w, 4);
		call(w, WasmLispCompiler.FUNC_STR_BUILD);
		strBuild(w, texts.valuePrefix());
		call(w, WasmLispCompiler.FUNC_STRING_CONCAT);
		w.write(Instruction.END);
		// the operand as prin1 prints it
		getLocal(w, 0);
		call(w, WasmLispCompiler.FUNC_PRIN1_TO_STR);
		call(w, WasmLispCompiler.FUNC_STRING_CONCAT);
		// the type: the funnel's own kind unnamed, else the operator's (narrowed to REAL
		// for a NUMBER operator where the funnel wanted a real) --
		// OperandTypes.expectedType
		int kindCode = kind == OperandTypes.Kind.INTEGER ? INTEGER_CODE
				: kind == OperandTypes.Kind.NUMBER ? NUMBER_CODE : REAL_CODE;
		getLocal(w, 1);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF);
		w.write(Type.I32);
		i32Const(w, kindCode);
		w.write(Instruction.ELSE);
		loadRow(w, 8);
		if (kind == OperandTypes.Kind.REAL) {
			i32Const(w, INTEGER_CODE);
			w.write(Instruction.I32_EQ);
			w.write(Instruction.IF);
			w.write(Type.I32);
			i32Const(w, INTEGER_CODE);
			w.write(Instruction.ELSE);
			i32Const(w, REAL_CODE);
			w.write(Instruction.END);
		}
		w.write(Instruction.END);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(1);
		getLocal(w, 1);
		i32Const(w, INTEGER_CODE);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		strBuild(w, texts.integerSuffix());
		w.write(Instruction.ELSE);
		getLocal(w, 1);
		i32Const(w, NUMBER_CODE);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		strBuild(w, texts.numberSuffix());
		w.write(Instruction.ELSE);
		strBuild(w, texts.realSuffix());
		w.write(Instruction.END);
		w.write(Instruction.END);
		call(w, WasmLispCompiler.FUNC_STRING_CONCAT);
		if (typeError == null) {
			WasmEmitHelper.emitNewCons(w, identityHash);
			w.write(Instruction.THROW);
			w.writeUnsignedLeb128(WasmLispCompiler.TAG_LISP_COND);
		}
		else {
			TypeNames typeNames = java.util.Objects.requireNonNull(texts.typeNames());
			w.write(Instruction.SET_LOCAL);
			w.writeUnsignedLeb128(MSG_LOCAL);
			WasmRuntimeBuilder.emitConditionThrow(w, typeError.instance(), SLOTS_LOCAL, MSG_LOCAL,
					java.util.Map.of(typeError.datumSlot(), () -> getLocal(w, 0), typeError.expectedTypeSlot(),
							() -> emitTypeSymbol(w, typeNames)));
		}
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/** Pushes the symbol naming the type code in local 1. */
	private static void emitTypeSymbol(WasmWriter w, TypeNames names) {
		getLocal(w, 1);
		i32Const(w, INTEGER_CODE);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		strBuild(w, names.integer());
		w.write(Instruction.ELSE);
		getLocal(w, 1);
		i32Const(w, NUMBER_CODE);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF);
		w.writeRefType(true, Type.EQ.code());
		strBuild(w, names.number());
		w.write(Instruction.ELSE);
		strBuild(w, names.real());
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

	/** Emits {@code i32.load offset=field} of the row in local 2. */
	private static void loadRow(WasmWriter w, int field) {
		getLocal(w, 2);
		w.write(Instruction.I32_LOAD);
		w.writeUnsignedLeb128(2);
		w.writeUnsignedLeb128(field);
	}

	private static void strBuild(WasmWriter w, WasmLispCompiler.StringTable.StringEntry entry) {
		i32Const(w, entry.offset());
		i32Const(w, entry.length());
		call(w, WasmLispCompiler.FUNC_STR_BUILD);
	}

	private static void getLocal(WasmWriter w, int local) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(local);
	}

	private static void i32Const(WasmWriter w, int value) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(value);
	}

	private static void call(WasmWriter w, int func) {
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(func);
	}

}

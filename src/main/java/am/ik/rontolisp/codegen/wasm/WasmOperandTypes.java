package am.ik.rontolisp.codegen.wasm;

import am.ik.rontolisp.LispNames;
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
 * operator (row 0 unused), then the quote-framed {@code "OP: "} texts. Type code
 * {@code i + 1} is {@link #TYPES}' {@code i}th type; 0 marks a funnel-typed operator (the
 * landing's own kind). The landings cite the blob's base, so it is dropped with them.
 */
final class WasmOperandTypes {

	/**
	 * The types a report can name: type code {@code i + 1} is the {@code i}th, as the
	 * table holds them and as the landing selects its suffix and symbol.
	 */
	private static final java.util.List<String> TYPES = java.util.Arrays.stream(OperandTypes.Kind.values())
		.map(Enum::name)
		.toList();

	/** A funnel-typed operator's row type: the landing's own kind decides. */
	private static final int FUNNEL_CODE = 0;

	private static final int NUMBER_CODE = code(OperandTypes.Kind.NUMBER);

	private static final int REAL_CODE = code(OperandTypes.Kind.REAL);

	/** Bytes per table row. */
	private static final int ROW = 12;

	/** The shared landing's parameters and locals. */
	private static final int KIND_LOCAL = 1;

	private static final int CODE_LOCAL = 2;

	private static final int ROW_LOCAL = 3;

	private static final int MSG_LOCAL = 4;

	private static final int SLOTS_LOCAL = 5;

	/** The kinds a {@code _type_err_*} landing stub hands the shared body. */
	private static final java.util.List<OperandTypes.Kind> LANDING_KINDS = java.util.List.of(OperandTypes.Kind.INTEGER,
			OperandTypes.Kind.NUMBER, OperandTypes.Kind.REAL, OperandTypes.Kind.LIST);

	/**
	 * Operators a compile-time lowering introduces where the source spelled another name:
	 * a {@code coerce} to {@code real} signals through {@code float}, a {@code setf} of
	 * an {@code aref} or {@code svref} place through {@code %aset}, {@code nth} and
	 * {@code second}..{@code tenth} through {@code (car (nthcdr ...))}, {@code dolist}
	 * and {@code loop}'s {@code for-in} through {@code endp}, a {@code car}/{@code cdr}
	 * place's store ({@code setf} and the modify macros) through {@code rplaca} /
	 * {@code rplacd}, a {@code setf} of a {@code char}/{@code schar}/
	 * {@code row-major-aref} place through its own store name and of an {@code elt} place
	 * through {@code %aset}'s.
	 */
	private static final java.util.Map<String, java.util.List<String>> LOWERED_TO = loweredTo();

	/**
	 * The operators whose sites hand a landing a kind no {@code _type_err_*} stub has --
	 * {@code STRING}, which {@code _str_char_ref} and {@code %check-string} land with
	 * directly ({@link #emitLanding}) -- so the shared body selects its type only in a
	 * module that can reach one.
	 */
	private static final java.util.List<String> STRING_CHECKED = java.util.List.of("CHAR", "SCHAR",
			OperandTypes.SETF_CHAR, OperandTypes.SETF_SCHAR);

	/**
	 * The function whose presence says a module has string stores, whose
	 * {@code %check-character} lands {@code CHARACTER} directly ({@link #emitLanding}):
	 * every such store calls it ({@code .kb/string-write-runtime.md}), and a module
	 * without it selects no {@code CHARACTER} text.
	 */
	private static final String CHARACTER_CHECKED = LispNames.SCHAR_SET_RUNTIME;

	/**
	 * The element accesses whose sites check a subscript against its bound in EH mode
	 * ({@code _idx_in}): a table naming any of them gives the shared landing its index
	 * arm ({@link Operators#indexed}).
	 */
	private static final java.util.List<String> INDEXED = java.util.List.of("AREF", OperandTypes.SETF_AREF,
			"ROW-MAJOR-AREF", OperandTypes.SETF_ROW_MAJOR_AREF);

	private static java.util.Map<String, java.util.List<String>> loweredTo() {
		java.util.Map<String, java.util.List<String>> map = new java.util.HashMap<>();
		map.put("COERCE", java.util.List.of("FLOAT"));
		map.put("AREF", java.util.List.of(OperandTypes.SETF_AREF));
		map.put("SVREF", java.util.List.of(OperandTypes.SETF_AREF));
		map.put("ELT", java.util.List.of("AREF", OperandTypes.SETF_AREF));
		map.put("CHAR", java.util.List.of(OperandTypes.SETF_CHAR));
		map.put("SCHAR", java.util.List.of(OperandTypes.SETF_SCHAR));
		map.put("ROW-MAJOR-AREF", java.util.List.of(OperandTypes.SETF_ROW_MAJOR_AREF));
		map.put("DOLIST", java.util.List.of("ENDP"));
		map.put("LOOP", java.util.List.of("ENDP"));
		for (String modify : java.util.List.of("SETF", "INCF", "DECF", "PUSH", "POP", "PUSHNEW")) {
			map.put(modify, java.util.List.of("RPLACA", "RPLACD"));
		}
		for (String nth : java.util.List.of("NTH", "SECOND", "THIRD", "FOURTH", "FIFTH", "SIXTH", "SEVENTH", "EIGHTH",
				"NINTH", "TENTH")) {
			map.put(nth, java.util.List.of("NTHCDR", "CAR"));
		}
		return java.util.Map.copyOf(map);
	}

	private WasmOperandTypes() {
	}

	/**
	 * The module's operator table.
	 *
	 * @param ids operator to its row (1-based)
	 * @param base the blob's absolute address
	 * @param rowCodes the type codes the rows hold ({@link #FUNNEL_CODE} included), plus
	 * {@code STRING}'s when a row's sites land with it directly ({@link #STRING_CHECKED})
	 * and {@code CHARACTER}'s when the module stores into strings
	 * ({@link #CHARACTER_CHECKED}): a landing selects among only the types they can name,
	 * so a suffix no row can reach is never cited and drops with the string blob's dead
	 * ranges
	 */
	record Operators(java.util.Map<String, Integer> ids, int base, java.util.Set<Integer> rowCodes,
			WasmLispCompiler.StringTable.@Nullable StringEntry indexPrefix,
			WasmLispCompiler.StringTable.@Nullable StringEntry indexSuffix) {

		static final Operators NONE = new Operators(java.util.Map.of(), -1, java.util.Set.of(), null, null);

		/**
		 * Whether an element access checks its subscript against its bound
		 * ({@code _idx_in}) and the shared landing reports an out-of-range one: exactly
		 * when the table names an element access, so a module that spells none carries
		 * neither the arm nor its two texts.
		 * @return whether the index arm exists
		 */
		boolean indexed() {
			return this.indexPrefix != null;
		}

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
			// In key order: Map.of's iteration order varies from one JVM to the next, and
			// the rows' order is the module's bytes.
			new java.util.TreeMap<>(OperandTypes.rewritten()).forEach((from, to) -> {
				if (spelled.test(from)) {
					wanted.add(to);
				}
			});
			new java.util.TreeMap<>(LOWERED_TO).forEach((from, to) -> {
				if (spelled.test(from)) {
					wanted.addAll(to);
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
			java.util.Set<Integer> rowCodes = new java.util.TreeSet<>();
			for (String op : wanted) {
				rowCodes.add(typeCode(java.util.Objects.requireNonNull(OperandTypes.operatorType(op))));
				if (STRING_CHECKED.contains(op)) {
					rowCodes.add(code(OperandTypes.Kind.STRING));
				}
			}
			if (spelled.test(CHARACTER_CHECKED)) {
				rowCodes.add(code(OperandTypes.Kind.CHARACTER));
			}
			int base = table.appendReaderOwnedBlob(blob.toByteArray());
			// The out-of-range subscript's texts: " is not of type (INTEGER 0 (" and "))"
			// around the printed bound (OperandTypes.indexType).
			boolean indexed = INDEXED.stream().anyMatch(wanted::contains);
			return new Operators(java.util.Map.copyOf(ids), base, java.util.Set.copyOf(rowCodes),
					indexed ? table
						.addBodyString("\"" + OperandTypes.TYPE_INFIX + OperandTypes.INDEX_TYPE_PREFIX + "\"") : null,
					indexed ? table.addBodyString("\"" + OperandTypes.INDEX_TYPE_SUFFIX + "\"") : null);
		}

	}

	/**
	 * Emits {@code call func}, under the innermost named operator's register value when
	 * there is one.
	 * @param ctx the emission context
	 * @param func the callee's function index
	 */
	static void emitCall(WasmLispCompiler.Ctx ctx, int func) {
		int id = mayReject(func) ? operatorId(ctx) : 0;
		if (id != 0) {
			setRegister(ctx.writer, ctx.operandOpGlobalIndex, id);
		}
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeUnsignedLeb128(func);
		if (id != 0 && !isLanding(func)) {
			setRegister(ctx.writer, ctx.operandOpGlobalIndex, 0);
		}
	}

	/**
	 * The operator register's value for a call compiled here: the innermost named
	 * operator's table row, or 0 -- also outside EH mode, where no register exists.
	 * @param ctx the emission context
	 * @return the id
	 */
	static int operatorId(WasmLispCompiler.Ctx ctx) {
		if (ctx.operandOpGlobalIndex < 0) {
			return 0;
		}
		String op = OperandTypes.reportedOperator(ctx.operator);
		Integer row = op == null ? null : ctx.operandOperators.ids().get(op);
		return row == null ? 0 : row;
	}

	/**
	 * Emits the shared landing over the culprit on the stack with a kind no stub has
	 * ({@code STRING}, {@code CHARACTER}):
	 * {@code i32.const kind; call _type_err; unreachable}. It reads the operator from the
	 * register the caller set. EH mode only.
	 * @param w the writer
	 * @param kind what the check was for
	 */
	static void emitLanding(WasmWriter w, OperandTypes.Kind kind) {
		i32Const(w, code(kind));
		call(w, WasmLispCompiler.FUNC_TYPE_ERR);
		w.write(Instruction.UNREACHABLE);
	}

	/**
	 * Emits the innermost named operator's type-error over the value in {@code slot}: the
	 * operator's id into the register, then {@link #emitLanding}. EH mode only.
	 * @param ctx the emission context
	 * @param slot the local holding the culprit
	 * @param kind what the check was for
	 */
	static void emitTypeError(WasmLispCompiler.Ctx ctx, int slot, OperandTypes.Kind kind) {
		setRegister(ctx.writer, ctx.operandOpGlobalIndex, operatorId(ctx));
		ctx.writer.write(Instruction.GET_LOCAL);
		ctx.writer.writeUnsignedLeb128(slot);
		emitLanding(ctx.writer, kind);
	}

	/**
	 * Whether a helper clears the register itself, so nothing after the call needs to:
	 * one of the landings, which never return, or a bound check ({@code _idx_in} and the
	 * {@code _idx_bound} that calls it), which clears it on success.
	 */
	private static boolean isLanding(int func) {
		return func == WasmLispCompiler.FUNC_TYPE_ERR_INT || func == WasmLispCompiler.FUNC_TYPE_ERR_NUM
				|| func == WasmLispCompiler.FUNC_TYPE_ERR_REAL || func == WasmLispCompiler.FUNC_TYPE_ERR_LIST
				|| func == WasmLispCompiler.FUNC_IDX_IN || func == WasmLispCompiler.FUNC_IDX_BOUND;
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
	static void withOperator(WasmLispCompiler.Ctx ctx, @Nullable String operator, Runnable emission) {
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
		return OperandTypes.FUNNEL_TYPE.equals(type) ? FUNNEL_CODE : TYPES.indexOf(type) + 1;
	}

	private static int code(OperandTypes.Kind kind) {
		return kind.ordinal() + 1;
	}

	/**
	 * The quote-framed texts the landings cite, interned before any body compiles.
	 *
	 * @param valuePrefix {@code "The value "}
	 * @param suffixes {@code " is not of type T"} for each of {@link #TYPES}
	 * @param typeNames the type symbols a {@code type-error}'s {@code expected-type}
	 * holds, one per {@link #TYPES} entry, or null when the module builds none --
	 * interned as the names themselves: a symbol's identity is its entry, so these are
	 * the entries a quoted {@code 'number} in the program shares, and {@code eq} holds
	 * between the two
	 */
	record Texts(WasmLispCompiler.StringTable.StringEntry valuePrefix,
			java.util.List<WasmLispCompiler.StringTable.StringEntry> suffixes,
			java.util.@Nullable List<WasmLispCompiler.StringTable.StringEntry> typeNames) {

		/**
		 * Interns the texts.
		 * @param table the module's string table
		 * @param typeError whether the landings build a {@code type-error}
		 * @return the texts
		 */
		static Texts intern(WasmLispCompiler.StringTable table, boolean typeError) {
			return new Texts(table.addBodyString("\"" + OperandTypes.VALUE_PREFIX + "\""),
					TYPES.stream()
						.map(type -> table.addBodyString("\"" + OperandTypes.TYPE_INFIX + type + "\""))
						.toList(),
					typeError ? TYPES.stream().map(table::addBodyString).toList() : null);
		}

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
	 * Builds {@code _type_err_int} / {@code _type_err_num} / {@code _type_err_real} /
	 * {@code _type_err_list}, the landings of a wrong-type argument, signature
	 * {@code ((ref null eq)) -> ()}; none returns. In EH mode each is a stub handing its
	 * kind to the shared {@code _type_err} ({@link #buildSharedLandingBody}), so the
	 * rendering is carried once however many landings a module reaches. Outside EH mode
	 * it is a bare {@code unreachable}: no tag exists, and citing the prin1 renderer
	 * would pin the printer family into every module.
	 * @param kind what the funnel was checking for
	 * @param ehMode whether the module is in EH mode
	 * @return the function body
	 */
	static byte[] buildLandingBody(OperandTypes.Kind kind, boolean ehMode) {
		java.io.ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		w.write(0); // no extra locals
		if (ehMode) {
			getLocal(w, 0);
			i32Const(w, code(kind));
			call(w, WasmLispCompiler.FUNC_TYPE_ERR);
		}
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds {@code _type_err(culprit, kind) -> i32}, the one landing body the
	 * {@code _type_err_*} stubs share ({@code TYPE_STR_TO_MEM}; it never returns). In EH
	 * mode it renders the report and throws it on {@code $lisp-cond}, the channel
	 * {@code %error-cond} uses, as a {@code type-error} instance whose {@code datum} is
	 * the operand and whose {@code expected-type} is the type the report names -- or, in
	 * a module that did not bake the class ({@code typeError} null: no handler landing
	 * pad, so nothing can observe the class), as the instance-less
	 * {@code (nil . message)} payload the entry landing pad reports the same way. Outside
	 * EH mode a bare {@code unreachable}.
	 * @param texts the interned texts, non-null in EH mode
	 * @param operators the operator table
	 * @param operatorGlobal the operator register, or -1 outside EH mode
	 * @param typeError the type-error shape, or null for the instance-less payload
	 * @param identityHash whether a cons carries the identity-hash field
	 * @return the function body
	 */
	static byte[] buildSharedLandingBody(@Nullable Texts texts, Operators operators, int operatorGlobal,
			@Nullable TypeErrorShape typeError, boolean identityHash) {
		int operatorTable = operators.base();
		java.io.ByteArrayOutputStream body = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		if (texts == null || operatorGlobal < 0) {
			w.write(0); // no extra locals
			w.write(Instruction.UNREACHABLE);
			w.write(Instruction.END);
			return body.toByteArray();
		}
		// params: the culprit, its funnel's kind code; extra locals: $g (i32) the
		// register's id, then the type code; $row (i32) its table row; with a type-error
		// to build, $msg and $slots ((ref null eq))
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
		w.writeUnsignedLeb128(CODE_LOCAL);
		setRegister(w, operatorGlobal, 0);
		// $row = table + g * ROW
		getLocal(w, CODE_LOCAL);
		i32Const(w, ROW);
		w.write(Instruction.I32_MUL);
		i32Const(w, operatorTable);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(ROW_LOCAL);
		if (typeError == null) {
			// payload car: the condition instance slot, nil for a message-only throw
			w.write(Instruction.REF_NULL);
			w.writeHeapType(Type.EQ.code());
		}
		// the head: "The value ", after "OP: " when the register named one
		getLocal(w, CODE_LOCAL);
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
		// an out-of-range subscript (a negative kind, -1 - bound: _idx_in): its type
		// is (INTEGER 0 (bound)), the text around the printed bound
		if (operators.indexed()) {
			getLocal(w, KIND_LOCAL);
			i32Const(w, 0);
			w.write(Instruction.I32_LT_S);
			w.write(Instruction.IF);
			w.writeRefType(true, Type.EQ.code());
			strBuild(w, java.util.Objects.requireNonNull(operators.indexPrefix()));
			pushBound(w);
			call(w, WasmLispCompiler.FUNC_PRIN1_TO_STR);
			call(w, WasmLispCompiler.FUNC_STRING_CONCAT);
			strBuild(w, java.util.Objects.requireNonNull(operators.indexSuffix()));
			call(w, WasmLispCompiler.FUNC_STRING_CONCAT);
			w.write(Instruction.ELSE);
		}
		// the type -- OperandTypes.expectedType: the funnel's own kind unnamed; for a
		// funnel-typed operator the kind too, a to-double funnel's read as REAL; else the
		// operator's, narrowed to REAL for a NUMBER operator where the funnel wanted a
		// real
		getLocal(w, CODE_LOCAL);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF);
		w.write(Type.I32);
		getLocal(w, KIND_LOCAL);
		w.write(Instruction.ELSE);
		loadRow(w, 8);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(CODE_LOCAL);
		getLocal(w, CODE_LOCAL);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF);
		w.write(Type.I32);
		selectReal(w, () -> {
			getLocal(w, KIND_LOCAL);
			i32Const(w, NUMBER_CODE);
			w.write(Instruction.I32_EQ);
		}, KIND_LOCAL);
		w.write(Instruction.ELSE);
		selectReal(w, () -> {
			getLocal(w, KIND_LOCAL);
			i32Const(w, REAL_CODE);
			w.write(Instruction.I32_EQ);
			getLocal(w, CODE_LOCAL);
			i32Const(w, NUMBER_CODE);
			w.write(Instruction.I32_EQ);
			w.write(Instruction.I32_AND);
		}, CODE_LOCAL);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(CODE_LOCAL);
		// the codes the type can take: every landing's kind, and what a row type becomes
		java.util.SortedSet<Integer> codes = new java.util.TreeSet<>();
		for (OperandTypes.Kind kind : LANDING_KINDS) {
			codes.add(code(kind));
		}
		for (int rowCode : operators.rowCodes()) {
			if (rowCode != FUNNEL_CODE) {
				codes.add(rowCode);
			}
		}
		emitByCode(w, codes, texts.suffixes());
		if (operators.indexed()) {
			w.write(Instruction.END);
		}
		call(w, WasmLispCompiler.FUNC_STRING_CONCAT);
		if (typeError == null) {
			WasmEmitHelper.emitNewCons(w, identityHash);
			w.write(Instruction.THROW);
			w.writeUnsignedLeb128(WasmLispCompiler.TAG_LISP_COND);
		}
		else {
			java.util.List<WasmLispCompiler.StringTable.StringEntry> typeNames = java.util.Objects
				.requireNonNull(texts.typeNames());
			w.write(Instruction.SET_LOCAL);
			w.writeUnsignedLeb128(MSG_LOCAL);
			Runnable expectedType = () -> emitByCode(w, codes, typeNames);
			if (operators.indexed()) {
				// (INTEGER 0 (bound)) for an out-of-range subscript
				Runnable byCode = expectedType;
				expectedType = () -> {
					getLocal(w, KIND_LOCAL);
					i32Const(w, 0);
					w.write(Instruction.I32_LT_S);
					w.write(Instruction.IF);
					w.writeRefType(true, Type.EQ.code());
					strBuild(w, typeNames.get(code(OperandTypes.Kind.INTEGER) - 1));
					i32Const(w, 0);
					w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
					pushBound(w);
					w.write(Instruction.REF_NULL);
					w.writeHeapType(Type.EQ.code());
					WasmEmitHelper.emitNewCons(w, identityHash);
					w.write(Instruction.REF_NULL);
					w.writeHeapType(Type.EQ.code());
					WasmEmitHelper.emitNewCons(w, identityHash);
					WasmEmitHelper.emitNewCons(w, identityHash);
					WasmEmitHelper.emitNewCons(w, identityHash);
					w.write(Instruction.ELSE);
					byCode.run();
					w.write(Instruction.END);
				};
			}
			WasmRuntimeBuilder.emitConditionThrow(w, typeError.instance(), SLOTS_LOCAL, MSG_LOCAL, java.util.Map
				.of(typeError.datumSlot(), () -> getLocal(w, 0), typeError.expectedTypeSlot(), expectedType));
		}
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Pushes the bound an out-of-range subscript's kind encodes, {@code -1 - kind}, as an
	 * i31.
	 */
	private static void pushBound(WasmWriter w) {
		i32Const(w, -1);
		getLocal(w, KIND_LOCAL);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
	}

	/** Pushes {@code REAL}'s code when {@code test} holds, else the local's value. */
	private static void selectReal(WasmWriter w, Runnable test, int otherwise) {
		test.run();
		w.write(Instruction.IF);
		w.write(Type.I32);
		i32Const(w, REAL_CODE);
		w.write(Instruction.ELSE);
		getLocal(w, otherwise);
		w.write(Instruction.END);
	}

	/**
	 * Pushes the entry the type code in {@link #CODE_LOCAL} selects among {@code codes}
	 * (code {@code c} is entry {@code c - 1}), built as a string; the last code is the
	 * fall-through.
	 */
	private static void emitByCode(WasmWriter w, java.util.SortedSet<Integer> codes,
			java.util.List<WasmLispCompiler.StringTable.StringEntry> entries) {
		java.util.List<Integer> ordered = java.util.List.copyOf(codes);
		for (int i = 0; i < ordered.size() - 1; i++) {
			getLocal(w, CODE_LOCAL);
			i32Const(w, ordered.get(i));
			w.write(Instruction.I32_EQ);
			w.write(Instruction.IF);
			w.writeRefType(true, Type.EQ.code());
			strBuild(w, entries.get(ordered.get(i) - 1));
			w.write(Instruction.ELSE);
		}
		strBuild(w, entries.get(ordered.getLast() - 1));
		for (int i = 0; i < ordered.size() - 1; i++) {
			w.write(Instruction.END);
		}
	}

	/** Emits {@code i32.load offset=field} of the row in {@link #ROW_LOCAL}. */
	private static void loadRow(WasmWriter w, int field) {
		getLocal(w, ROW_LOCAL);
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

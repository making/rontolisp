package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds WASM bytecode for runtime helper functions: dispatch, print_i32, write_str,
 * print_val, and print_f64.
 */
final class WasmRuntimeBuilder {

	private WasmRuntimeBuilder() {
	}

	static byte[] buildDispatchBody(int arity, List<WasmLispCompiler.DefunDecl> defuns,
			List<WasmLispCompiler.LambdaInfo> lambdaDecls, int numDefuns, WasmLispCompiler.StringTable st) {
		// Collect all functions with matching arity
		record Target(int funcId, int funcIndex) {
		}
		List<Target> targets = new ArrayList<>();
		for (int i = 0; i < defuns.size(); i++) {
			if (defuns.get(i).paramNames().size() == arity) {
				targets.add(new Target(i, WasmLispCompiler.FUNC_USER_BASE + i));
			}
		}
		for (int i = 0; i < lambdaDecls.size(); i++) {
			WasmLispCompiler.LambdaInfo lambda = lambdaDecls.get(i);
			if (lambda.paramNames().size() == arity) {
				targets.add(new Target(lambda.funcId(), lambda.funcIndex()));
			}
		}

		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// Locals: param 0 = funcval, params 1..arity = args
		// Extra local for funcId extraction
		w.write(1); // 1 local group
		w.write(1); // 1 local of type i32
		w.write(Type.I32);

		int funcIdLocal = arity + 1; // after params

		if (targets.isEmpty()) {
			w.write(Instruction.UNREACHABLE);
			w.write(Instruction.END);
			return body.toByteArray();
		}

		// Extract funcId from closure struct
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0); // funcval
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CLOSURE);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CLOSURE);
		w.writeSignedLeb128(0); // field 0: funcId
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(funcIdLocal);

		int numCases = targets.size();
		int maxFuncId = 0;
		for (Target t : targets) {
			maxFuncId = Math.max(maxFuncId, t.funcId);
		}

		// Result block (typed)
		w.write(Instruction.BLOCK);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());

		// Default block (void)
		w.write(Instruction.BLOCK, 0x40);

		// Case blocks (void) - outermost case first
		for (int i = 0; i < numCases; i++) {
			w.write(Instruction.BLOCK, 0x40);
		}

		// Load funcId and br_table
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(funcIdLocal);

		// Build funcId -> br_table depth mapping
		Map<Integer, Integer> funcIdToCase = new HashMap<>();
		for (int j = 0; j < targets.size(); j++) {
			funcIdToCase.put(targets.get(j).funcId, j);
		}

		w.write(Instruction.BR_TABLE);
		w.writeUnsignedLeb128(maxFuncId + 1); // label count
		for (int fid = 0; fid <= maxFuncId; fid++) {
			Integer caseJ = funcIdToCase.get(fid);
			if (caseJ != null) {
				w.writeUnsignedLeb128(numCases - 1 - caseJ);
			}
			else {
				w.writeUnsignedLeb128(numCases); // default
			}
		}
		w.writeUnsignedLeb128(numCases); // default label

		// Case bodies: close blocks from innermost to outermost
		for (int k = 0; k < numCases; k++) {
			w.write(Instruction.END); // end of $case_{numCases-1-k}
			int targetIdx = numCases - 1 - k;
			Target target = targets.get(targetIdx);
			// Extract env from closure struct
			w.write(Instruction.GET_LOCAL);
			w.writeSignedLeb128(0); // funcval
			w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			w.writeHeapType(WasmLispCompiler.TYPE_CLOSURE);
			w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
			w.writeSignedLeb128(WasmLispCompiler.TYPE_CLOSURE);
			w.writeSignedLeb128(1); // field 1: env
			// Push args
			for (int a = 1; a <= arity; a++) {
				w.write(Instruction.GET_LOCAL);
				w.writeSignedLeb128(a);
			}
			// Call target function
			w.write(Instruction.CALL);
			w.writeSignedLeb128(target.funcIndex);
			// Break to $result block
			w.write(Instruction.BR);
			w.writeUnsignedLeb128(numCases - k);
		}

		// End default block
		w.write(Instruction.END); // $default
		w.write(Instruction.UNREACHABLE);

		// End result block
		w.write(Instruction.END); // $result

		w.write(Instruction.END); // end function
		return body.toByteArray();
	}

	/**
	 * Builds the print_i32 helper function body.
	 */
	static byte[] buildPrintI32Core(boolean appendNewline) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		w.write(3);
		w.write(1);
		w.write(Type.I32);
		w.write(1);
		w.write(Type.I32);
		w.write(1);
		w.write(Type.I32);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(1);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.END);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PRINT_BUF_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(48);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.ELSE);

		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(10);
		w.write(Instruction.I32_REM_U);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PRINT_BUF_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(48);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(10);
		w.write(Instruction.I32_DIV_U);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(0);

		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);

		w.write(Instruction.END);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.OUT_BUF_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(45);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.END);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3);

		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.OUT_BUF_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.I32_ADD);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.PRINT_BUF_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);

		w.write(Instruction.I32_STORE8, 0x00, 0x00);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3);

		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);

		if (appendNewline) {
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(WasmLispCompiler.OUT_BUF_OFFSET);
			w.write(Instruction.GET_LOCAL);
			w.writeSignedLeb128(1);
			w.write(Instruction.I32_ADD);
			w.write(Instruction.GET_LOCAL);
			w.writeSignedLeb128(2);
			w.write(Instruction.I32_ADD);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(10);
			w.write(Instruction.I32_STORE8, 0x00, 0x00);
		}

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.IOV_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.OUT_BUF_OFFSET);
		w.write(Instruction.I32_STORE, 0x02, 0x00);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.IOV_OFFSET + 4);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_ADD);
		if (appendNewline) {
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(1);
			w.write(Instruction.I32_ADD);
		}
		w.write(Instruction.I32_STORE, 0x02, 0x00);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.IOV_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.NWRITTEN_OFFSET);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_FD_WRITE);
		w.write(Instruction.DROP);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	static byte[] buildWriteStrBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		w.write(0);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.IOV_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_STORE, 0x02, 0x00);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.IOV_OFFSET + 4);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_STORE, 0x02, 0x00);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.IOV_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.NWRITTEN_OFFSET);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_FD_WRITE);
		w.write(Instruction.DROP);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the _print_val helper function that prints any Lisp value without a trailing
	 * newline. Handles null (nil), i31ref (integer), string struct, closure struct, and
	 * cons struct (list).
	 */
	static byte[] buildPrintValBody(WasmLispCompiler.StringTable st) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		w.write(2);
		w.write(1);
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		w.write(1);
		w.write(Type.I32);

		// Check null (nil)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.nil.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.nil.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check i31ref (integer)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_PRINT_I32_NO_NL);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check float struct
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_FLOAT);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		w.writeSignedLeb128(0);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_PRINT_F64_NO_NL);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check string struct
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.writeSignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_STRING);
		w.writeSignedLeb128(1);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Check closure struct -> print "#<function>"
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CLOSURE);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.funcStr.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.funcStr.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// Must be cons struct - print as list
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.lparen.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.lparen.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2);

		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.BR_IF, 1);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.dot.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.dot.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_PRINT_VAL);
		w.write(Instruction.BR, 2);
		w.write(Instruction.END);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.space.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.space.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.END);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(0);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_PRINT_VAL);

		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(1);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2);

		w.write(Instruction.BR, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.rparen.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.rparen.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the print_f64 helper function body. Prints integer part via print_i32_no_nl,
	 * then '.' and fractional digits.
	 */
	static byte[] buildPrintF64Core(boolean appendNewline, WasmLispCompiler.StringTable st) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// Locals: 0=f64 value (param), 1=i32 is_neg, 2=i32 int_part, 3=f64 frac,
		// 4=i32 digit, 5=i32 digit_count
		w.write(5);
		w.write(1);
		w.write(Type.I32); // local 1: is_neg
		w.write(1);
		w.write(Type.I32); // local 2: int_part
		w.write(1);
		w.write(Type.F64); // local 3: frac
		w.write(1);
		w.write(Type.I32); // local 4: digit
		w.write(1);
		w.write(Type.I32); // local 5: digit_count

		// Check if negative
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.F64_CONST);
		w.writeF64(0.0);
		w.write(Instruction.F64_LT);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(1); // is_neg

		// If negative, negate
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.F64_NEG);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.END);

		// Get integer part: int_part = i32(floor(value))
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.F64_FLOOR);
		w.write(Instruction.I32_TRUNC_S_F64);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2); // int_part

		// If negative, write '-'
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.minus.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.minus.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		w.write(Instruction.END);

		// Print integer part
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_PRINT_I32_NO_NL);

		// Print '.'
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.period.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.period.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);

		// Compute fractional part: frac = value - f64(int_part)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.F64_CONVERT_S_I32);
		w.write(Instruction.F64_SUB);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3); // frac

		// digit_count = 0
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(5);

		// Loop to extract fractional digits
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);

		// frac = frac * 10.0
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.F64_CONST);
		w.writeF64(10.0);
		w.write(Instruction.F64_MUL);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3);

		// digit = i32(trunc(frac))
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.I32_TRUNC_S_F64);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(4);

		// Store digit char at OUT_BUF_OFFSET
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.OUT_BUF_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(4);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(48); // '0'
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);

		// Write the single digit
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(WasmLispCompiler.OUT_BUF_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);

		// frac = frac - f64(digit)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(4);
		w.write(Instruction.F64_CONVERT_S_I32);
		w.write(Instruction.F64_SUB);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3);

		// digit_count++
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(5);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(5);

		// Continue if: digit_count < 1 OR (frac > epsilon AND digit_count < 6)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(5);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_LT_S);
		// OR
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.F64_CONST);
		w.writeF64(0.0000001);
		w.write(Instruction.F64_GT);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(5);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(6);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_OR);

		w.write(Instruction.BR_IF, 0); // loop
		w.write(Instruction.END); // end loop
		w.write(Instruction.END); // end block

		// Append newline if needed
		if (appendNewline) {
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(st.newline.offset());
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(st.newline.length());
			w.write(Instruction.CALL);
			w.writeSignedLeb128(WasmLispCompiler.FUNC_WRITE_STR);
		}

		w.write(Instruction.END);
		return body.toByteArray();
	}

}

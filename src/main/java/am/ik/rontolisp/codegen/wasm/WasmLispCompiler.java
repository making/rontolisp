package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.LispCompiler;
import am.ik.wasm.ExternalKind;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Compiles Lisp expressions to WASM binary with wasm-GC and WASI Preview 1. All Lisp
 * values are represented as (ref eq) on the stack: integers use i31ref, nil uses ref.null
 * eq, strings use a string struct, and cons cells use a cons struct.
 */
public final class WasmLispCompiler implements LispCompiler {

	// Function indices (imports come first)
	private static final int FUNC_FD_WRITE = 0; // imported

	private static final int FUNC_START = 1; // _start

	private static final int FUNC_PRINT_I32 = 2; // print_i32 helper (with newline)

	private static final int FUNC_WRITE_STR = 3; // _write_str helper

	private static final int FUNC_PRINT_VAL = 4; // _print_val helper

	private static final int FUNC_PRINT_I32_NO_NL = 5; // print_i32 without newline

	// Type indices
	private static final int TYPE_FD_WRITE = 0;

	private static final int TYPE_START = 1;

	private static final int TYPE_PRINT_I32 = 2; // (i32) -> (), also for _print_i32_no_nl

	private static final int TYPE_CONS = 3; // in rec group

	private static final int TYPE_STRING = 4; // in rec group

	private static final int TYPE_WRITE_STR = 5; // (i32, i32) -> ()

	private static final int TYPE_PRINT_VAL = 6; // ((ref null eq)) -> ()

	// First type/function index for user-defined functions
	private static final int TYPE_USER_BASE = 7;

	private static final int FUNC_USER_BASE = 6;

	// Memory layout
	private static final int PRINT_BUF_OFFSET = 0; // 32 bytes for digit buffer

	private static final int IOV_OFFSET = 32; // iov struct at offset 32

	private static final int NWRITTEN_OFFSET = 48; // nwritten at offset 48

	private static final int OUT_BUF_OFFSET = 64; // output buffer for print_i32

	private static final int DATA_BASE_OFFSET = 128; // start of data section strings

	@Override
	public byte[] compile(List<LispVal> program) {
		// Pass 1: Collect defun declarations and top-level expressions
		List<DefunDecl> defuns = new ArrayList<>();
		List<LispVal> topLevelExprs = new ArrayList<>();
		for (LispVal expr : program) {
			if (expr instanceof LispCons cons && cons.car() instanceof LispSymbol sym && "defun".equals(sym.name())) {
				List<LispVal> parts = cons.toList();
				String funcName = ((LispSymbol) parts.get(1)).name();
				LispVal paramsVal = parts.get(2);
				List<String> paramNames;
				if (paramsVal instanceof LispNil) {
					paramNames = List.of();
				}
				else {
					paramNames = ((LispCons) paramsVal).toList().stream().map(p -> ((LispSymbol) p).name()).toList();
				}
				defuns.add(new DefunDecl(funcName, paramNames, parts.subList(3, parts.size())));
			}
			else if (isSetqLambda(expr)) {
				defuns.add(extractSetqLambda(expr));
			}
			else {
				topLevelExprs.add(expr);
			}
		}

		// Create string table with fixed strings
		StringTable stringTable = new StringTable(DATA_BASE_OFFSET);

		// Build function info map
		Map<String, WasmFunctionInfo> functions = new HashMap<>();
		for (int i = 0; i < defuns.size(); i++) {
			DefunDecl defun = defuns.get(i);
			functions.put(defun.name,
					new WasmFunctionInfo(defun.name, defun.paramNames.size(), TYPE_USER_BASE + i, FUNC_USER_BASE + i));
		}

		// Pass 2a: Compile each defun body
		List<byte[]> userFunctionBodies = new ArrayList<>();
		for (DefunDecl defun : defuns) {
			ByteArrayOutputStream funcBody = new ByteArrayOutputStream();
			WasmWriter funcWriter = new WasmWriter(funcBody);
			Ctx funcCtx = new Ctx(funcWriter, stringTable);
			funcCtx.functions = functions;

			// Parameters are the first N locals
			for (int i = 0; i < defun.paramNames.size(); i++) {
				funcCtx.locals.put(defun.paramNames.get(i), i);
			}
			funcCtx.nextLocal = defun.paramNames.size();

			// Compile body
			for (int i = 0; i < defun.bodyExprs.size(); i++) {
				if (i > 0) {
					funcWriter.write(Instruction.DROP);
				}
				compileExpr(defun.bodyExprs.get(i), funcCtx);
			}
			funcWriter.write(Instruction.END);

			// Rebuild with correct local declarations (only extra locals beyond params)
			ByteArrayOutputStream finalFuncBody = new ByteArrayOutputStream();
			WasmWriter finalFuncWriter = new WasmWriter(finalFuncBody);
			int extraLocals = funcCtx.nextLocal - defun.paramNames.size();
			if (extraLocals > 0) {
				finalFuncWriter.write(1); // 1 local group
				finalFuncWriter.write(extraLocals);
				finalFuncWriter.write(Type.REFNULL.code());
				finalFuncWriter.writeHeapType(Type.EQ.code());
			}
			else {
				finalFuncWriter.write(0); // 0 local groups
			}
			finalFuncWriter.write((Object) funcBody.toByteArray());
			userFunctionBodies.add(finalFuncBody.toByteArray());
		}

		// Pass 2b: Build _start function body
		ByteArrayOutputStream startBody = new ByteArrayOutputStream();
		WasmWriter startWriter = new WasmWriter(startBody);
		Ctx ctx = new Ctx(startWriter, stringTable);
		ctx.functions = functions;

		for (LispVal expr : topLevelExprs) {
			compileExpr(expr, ctx);
			startWriter.write(Instruction.DROP);
		}
		startWriter.write(Instruction.END);

		// Rebuild start body with correct local count
		ByteArrayOutputStream finalStartBody = new ByteArrayOutputStream();
		WasmWriter finalStartWriter = new WasmWriter(finalStartBody);
		int numLocals = ctx.nextLocal;
		if (numLocals > 0) {
			finalStartWriter.write(1); // 1 local group
			finalStartWriter.write(numLocals); // count
			// type: (ref null eq)
			finalStartWriter.write(Type.REFNULL.code());
			finalStartWriter.writeHeapType(Type.EQ.code());
		}
		else {
			finalStartWriter.write(0); // 0 local groups
		}
		finalStartWriter.write((Object) startBody.toByteArray());

		// Build helper function bodies
		byte[] printI32Body = buildPrintI32Core(true);
		byte[] writeStrBody = buildWriteStrBody();
		byte[] printValBody = buildPrintValBody(stringTable);
		byte[] printI32NoNlBody = buildPrintI32Core(false);

		ByteArrayOutputStream out = new ByteArrayOutputStream();
		WasmWriter mainWriter = new WasmWriter(out);
		mainWriter //
			.write("\0asm") // magic
			.writeLittleEndian4(1) // version
			// Type section: plain func types + rec group for structs
			.writeTypeSection(types -> {
				// type 0: fd_write (i32, i32, i32, i32) -> (i32) - plain func
				types.addFunc(new Type[] { Type.I32, Type.I32, Type.I32, Type.I32 }, new Type[] { Type.I32 });
				// type 1: _start () -> () - plain func
				types.addFunc(new Type[] {}, new Type[] {});
				// type 2: print_i32 / _print_i32_no_nl (i32) -> () - plain func
				types.addFunc(new Type[] { Type.I32 }, new Type[] {});
				// types 3-4: cons struct + string struct - in rec group
				types.addRecGroup(rec -> {
					// type 3: cons struct {(ref null eq) car, (ref null eq) cdr}
					rec.addSubFinalStruct(fields -> {
						fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
						fields.addField(true, w -> w.writeRefType(true, Type.EQ.code()));
					});
					// type 4: string struct {i32 offset, i32 length} (immutable)
					rec.addSubFinalStruct(fields -> {
						fields.addField(false, w -> w.write(Type.I32));
						fields.addField(false, w -> w.write(Type.I32));
					});
				});
				// type 5: _write_str (i32, i32) -> () - plain func
				types.addFunc(new Type[] { Type.I32, Type.I32 }, new Type[] {});
				// type 6: _print_val ((ref null eq)) -> () - plain func
				types.add(w -> {
					w.write(Type.FUNC);
					w.write(1); // 1 param
					w.write(Type.REFNULL.code());
					w.writeHeapType(Type.EQ.code());
					w.write(0); // 0 results
				});
				// type 7+: user-defined function types
				for (DefunDecl defun : defuns) {
					int paramCount = defun.paramNames.size();
					types.add(w -> {
						w.write(Type.FUNC);
						w.write(paramCount);
						for (int i = 0; i < paramCount; i++) {
							w.write(Type.REFNULL.code());
							w.writeHeapType(Type.EQ.code());
						}
						w.write(1); // 1 result
						w.write(Type.REFNULL.code());
						w.writeHeapType(Type.EQ.code());
					});
				}
			})
			// Import section
			.writeImportSection(imports -> imports.addImport("wasi_snapshot_preview1", "fd_write",
					ExternalKind.FUNCTION, TYPE_FD_WRITE))
			// Function section
			.writeFunction(fnDef -> {
				fnDef.addFunction(TYPE_START)
					.addFunction(TYPE_PRINT_I32)
					.addFunction(TYPE_WRITE_STR)
					.addFunction(TYPE_PRINT_VAL)
					.addFunction(TYPE_PRINT_I32); // _print_i32_no_nl reuses
													// TYPE_PRINT_I32
				for (DefunDecl defun : defuns) {
					WasmFunctionInfo fi = java.util.Objects.requireNonNull(functions.get(defun.name));
					fnDef.addFunction(fi.typeIndex);
				}
			})
			// Memory section
			.writeMemory(memories -> memories.addMemory(1))
			// Export section
			.writeExport(exports -> exports.addExport("memory", ExternalKind.MEMORY, 0)
				.addExport("_start", ExternalKind.FUNCTION, FUNC_START))
			// Code section
			.writeCode(code -> {
				code.addFunction(finalStartBody.toByteArray())
					.addFunction(printI32Body)
					.addFunction(writeStrBody)
					.addFunction(printValBody)
					.addFunction(printI32NoNlBody);
				for (byte[] body : userFunctionBodies) {
					code.addFunction(body);
				}
			})
			// Data section
			.writeDataSection(data -> {
				byte[] stringData = stringTable.toByteArray();
				if (stringData.length > 0) {
					data.addActiveData(0, DATA_BASE_OFFSET, stringData);
				}
			});
		return out.toByteArray();
	}

	private void compileExpr(LispVal expr, Ctx ctx) {
		switch (expr) {
			case LispInteger i -> {
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128((int) i.value());
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			}
			case LispNil ignored -> {
				// ref.null eq
				ctx.writer.write(Instruction.REF_NULL);
				ctx.writer.writeHeapType(Type.EQ.code());
			}
			case LispTrue ignored -> {
				// i31ref with value 1
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128(1);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			}
			case LispString s -> compileStringLiteral(s.print(), ctx);
			case LispSymbol sym -> compileSymbolRef(sym, ctx);
			case LispCons cons -> compileCons(cons, ctx);
			default -> throw new UnsupportedOperationException("Cannot compile: " + expr.print());
		}
	}

	private void compileSymbolRef(LispSymbol sym, Ctx ctx) {
		Integer slot = ctx.locals.get(sym.name());
		if (slot != null) {
			ctx.writer.write(Instruction.GET_LOCAL);
			ctx.writer.writeSignedLeb128(slot);
		}
		else {
			throw new UnsupportedOperationException("Cannot compile symbol: " + sym.name());
		}
	}

	private void compileCons(LispCons cons, Ctx ctx) {
		LispVal head = cons.car();
		if (head instanceof LispSymbol sym) {
			switch (sym.name()) {
				case "+" -> compileArith(cons, ctx, Instruction.I32_ADD);
				case "-" -> compileArith(cons, ctx, Instruction.I32_SUB);
				case "*" -> compileArith(cons, ctx, Instruction.I32_MUL);
				case "/" -> compileArith(cons, ctx, Instruction.I32_DIV_S);
				case "mod" -> compileArith(cons, ctx, Instruction.I32_REM_S);
				case "=" -> compileComparison(cons, ctx, Instruction.I32_EQ);
				case "<" -> compileComparison(cons, ctx, Instruction.I32_LT_S);
				case ">" -> compileComparison(cons, ctx, Instruction.I32_GT_S);
				case "<=" -> compileComparison(cons, ctx, Instruction.I32_LE_S);
				case ">=" -> compileComparison(cons, ctx, Instruction.I32_GE_S);
				case "print" -> compilePrint(cons, ctx);
				case "quote" -> compileQuote(cons, ctx);
				case "if" -> compileIf(cons, ctx);
				case "let" -> compileLet(cons, ctx);
				case "progn" -> compileProgn(cons, ctx);
				case "setq" -> compileSetq(cons, ctx);
				case "lambda" -> throw new UnsupportedOperationException(
						"lambda as a value is not supported; use (defun name (...) ...) or top-level (setq name (lambda (...) ...))");
				case "defun" -> {
					// defun at non-top-level is a no-op (already processed in pass 1)
					ctx.writer.write(Instruction.REF_NULL);
					ctx.writer.writeHeapType(Type.EQ.code());
				}
				default -> compileFunctionCall(sym.name(), cons, ctx);
			}
		}
		else if (head instanceof LispCons headCons && headCons.car() instanceof LispSymbol headSym
				&& "lambda".equals(headSym.name())) {
			compileLambdaCall(headCons, cons, ctx);
		}
		else {
			throw new UnsupportedOperationException("Cannot compile: " + cons.print());
		}
	}

	private void compileArith(LispCons cons, Ctx ctx, int opcode) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx);
		castI31GetS(ctx);
		for (int i = 2; i < args.size(); i++) {
			compileExpr(args.get(i), ctx);
			castI31GetS(ctx);
			ctx.writer.write(opcode);
		}
		// Box result back to i31ref
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
	}

	private void compileComparison(LispCons cons, Ctx ctx, int opcode) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx);
		castI31GetS(ctx);
		compileExpr(args.get(2), ctx);
		castI31GetS(ctx);
		ctx.writer.write(opcode);
		// Result is i32 (0 or 1). Convert to nil/non-nil for if-form compatibility:
		// if nonzero -> i31ref(1) (truthy), if zero -> ref.null eq (nil/falsy)
		ctx.writer.write(Instruction.IF);
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		// True: push i31ref(1)
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(1);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		ctx.writer.write(Instruction.ELSE);
		// False: push nil
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
		ctx.writer.write(Instruction.END);
	}

	private void compilePrint(LispCons cons, Ctx ctx) {
		List<LispVal> args = cons.toList();
		compileExpr(args.get(1), ctx);
		// Call _print_val to print the value
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(FUNC_PRINT_VAL);
		// Write newline
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(ctx.stringTable.newline.offset());
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(ctx.stringTable.newline.length());
		ctx.writer.write(Instruction.CALL);
		ctx.writer.writeSignedLeb128(FUNC_WRITE_STR);
		// Return nil
		ctx.writer.write(Instruction.REF_NULL);
		ctx.writer.writeHeapType(Type.EQ.code());
	}

	private void compileStringLiteral(String displayForm, Ctx ctx) {
		StringTable.StringEntry entry = ctx.stringTable.addString(displayForm);
		// Create string struct {offset, length}
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(entry.offset());
		ctx.writer.write(Instruction.I32_CONST);
		ctx.writer.writeSignedLeb128(entry.length());
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(TYPE_STRING);
	}

	private void compileQuote(LispCons cons, Ctx ctx) {
		LispVal quoted = ((LispCons) cons.cdr()).car();
		compileQuotedVal(quoted, ctx);
	}

	private void compileQuotedVal(LispVal val, Ctx ctx) {
		switch (val) {
			case LispInteger i -> {
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128((int) i.value());
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			}
			case LispNil ignored -> {
				ctx.writer.write(Instruction.REF_NULL);
				ctx.writer.writeHeapType(Type.EQ.code());
			}
			case LispTrue ignored -> {
				ctx.writer.write(Instruction.I32_CONST);
				ctx.writer.writeSignedLeb128(1);
				ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
			}
			case LispString s -> compileStringLiteral(s.print(), ctx);
			case LispSymbol sym -> compileStringLiteral(sym.name(), ctx);
			case LispCons cons -> compileQuotedCons(cons, ctx);
			default -> throw new UnsupportedOperationException("Cannot quote: " + val.print());
		}
	}

	private void compileQuotedCons(LispCons cons, Ctx ctx) {
		// Push car, push cdr, then struct.new TYPE_CONS
		compileQuotedVal(cons.car(), ctx);
		compileQuotedVal(cons.cdr(), ctx);
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		ctx.writer.writeSignedLeb128(TYPE_CONS);
	}

	private void compileIf(LispCons cons, Ctx ctx) {
		List<LispVal> parts = cons.toList();
		// Compile condition
		compileExpr(parts.get(1), ctx);
		// ref.is_null -> true if nil
		ctx.writer.write(Instruction.REF_IS_NULL);
		// if (result (ref eq)) ... else ... end
		ctx.writer.write(Instruction.IF);
		// Block type: (ref eq) - encoded as a type index using signed LEB128
		// We use an inline type: refnull eq
		ctx.writer.write(Type.REFNULL.code());
		ctx.writer.writeHeapType(Type.EQ.code());
		// If condition is null (nil) -> else branch is "then" (ref.is_null inverts)
		// So: if ref_is_null -> compile else first
		if (parts.size() > 3) {
			compileExpr(parts.get(3), ctx);
		}
		else {
			ctx.writer.write(Instruction.REF_NULL);
			ctx.writer.writeHeapType(Type.EQ.code());
		}
		ctx.writer.write(Instruction.ELSE);
		compileExpr(parts.get(2), ctx);
		ctx.writer.write(Instruction.END);
	}

	private void compileLet(LispCons cons, Ctx ctx) {
		List<LispVal> parts = cons.toList();
		LispVal bindings = parts.get(1);
		Map<String, Integer> savedLocals = new HashMap<>(ctx.locals);
		if (bindings instanceof LispCons bindingsCons) {
			for (LispVal binding : bindingsCons.toList()) {
				LispCons pair = (LispCons) binding;
				List<LispVal> pairList = pair.toList();
				String name = ((LispSymbol) pairList.get(0)).name();
				compileExpr(pairList.get(1), ctx);
				int slot = ctx.allocLocal(name);
				ctx.writer.write(Instruction.SET_LOCAL);
				ctx.writer.writeSignedLeb128(slot);
			}
		}
		for (int i = 2; i < parts.size(); i++) {
			if (i > 2) {
				ctx.writer.write(Instruction.DROP);
			}
			compileExpr(parts.get(i), ctx);
		}
		ctx.locals = savedLocals;
	}

	private void compileProgn(LispCons cons, Ctx ctx) {
		List<LispVal> parts = cons.toList();
		for (int i = 1; i < parts.size(); i++) {
			if (i > 1) {
				ctx.writer.write(Instruction.DROP);
			}
			compileExpr(parts.get(i), ctx);
		}
	}

	private void compileSetq(LispCons cons, Ctx ctx) {
		List<LispVal> parts = cons.toList();
		String name = ((LispSymbol) parts.get(1)).name();
		compileExpr(parts.get(2), ctx);
		Integer slot = ctx.locals.get(name);
		if (slot == null) {
			slot = ctx.allocLocal(name);
		}
		ctx.writer.write(Instruction.TEE_LOCAL);
		ctx.writer.writeSignedLeb128(slot);
	}

	private void compileFunctionCall(String name, LispCons cons, Ctx ctx) {
		WasmFunctionInfo fi = ctx.functions.get(name);
		if (fi != null) {
			List<LispVal> args = cons.toList();
			for (int i = 1; i < args.size(); i++) {
				compileExpr(args.get(i), ctx);
			}
			ctx.writer.write(Instruction.CALL);
			ctx.writer.writeSignedLeb128(fi.funcIndex);
		}
		else {
			throw new UnsupportedOperationException("Cannot compile: " + name);
		}
	}

	private void compileLambdaCall(LispCons lambda, LispCons call, Ctx ctx) {
		List<LispVal> lambdaParts = lambda.toList();
		LispVal paramsVal = lambdaParts.get(1);
		List<String> paramNames;
		if (paramsVal instanceof LispNil) {
			paramNames = List.of();
		}
		else {
			paramNames = ((LispCons) paramsVal).toList().stream().map(p -> ((LispSymbol) p).name()).toList();
		}
		List<LispVal> bodyExprs = lambdaParts.subList(2, lambdaParts.size());
		List<LispVal> callArgs = call.toList();

		Map<String, Integer> savedLocals = new HashMap<>(ctx.locals);

		// Evaluate arguments and store in local variables
		for (int i = 0; i < paramNames.size(); i++) {
			compileExpr(callArgs.get(i + 1), ctx);
			int slot = ctx.allocLocal(paramNames.get(i));
			ctx.writer.write(Instruction.SET_LOCAL);
			ctx.writer.writeSignedLeb128(slot);
		}

		// Compile body
		for (int i = 0; i < bodyExprs.size(); i++) {
			if (i > 0) {
				ctx.writer.write(Instruction.DROP);
			}
			compileExpr(bodyExprs.get(i), ctx);
		}

		ctx.locals = savedLocals;
		// Note: nextLocal is NOT restored because WASM requires local count to be
		// declared upfront
	}

	private void castI31GetS(Ctx ctx) {
		// ref.cast i31
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		ctx.writer.writeHeapType(Type.I31.code());
		// i31.get_s
		ctx.writer.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
	}

	/**
	 * Builds the print_i32 helper function body. When appendNewline is true, a newline
	 * character is appended after the number.
	 */
	private byte[] buildPrintI32Core(boolean appendNewline) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// Locals: param 0 = value (i32)
		// local 1 = is_negative (i32)
		// local 2 = digit_count (i32)
		// local 3 = temp (i32)
		w.write(3); // 3 local groups
		w.write(1); // 1 local of type i32 (is_negative)
		w.write(Type.I32);
		w.write(1); // 1 local of type i32 (digit_count)
		w.write(Type.I32);
		w.write(1); // 1 local of type i32 (temp)
		w.write(Type.I32);

		// Check if value is negative
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0); // value
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(1); // is_negative

		// If negative, negate
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1); // is_negative
		w.write(Instruction.IF, 0x40); // if void
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0); // value
		w.write(Instruction.I32_SUB);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(0); // value = -value
		w.write(Instruction.END);

		// Handle zero case
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0); // value
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40); // if void
		// Store '0' at buffer
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(PRINT_BUF_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(48); // '0'
		w.write(Instruction.I32_STORE8, 0x00, 0x00); // align=0, offset=0
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2); // digit_count = 1
		w.write(Instruction.ELSE);

		// Extract digits (reverse order) into buffer
		w.write(Instruction.BLOCK, 0x40); // block void
		w.write(Instruction.LOOP, 0x40); // loop void
		// if value == 0, break
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0); // value
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF, 1); // br_if to block (exit loop)

		// digit = value % 10
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0); // value
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(10);
		w.write(Instruction.I32_REM_U);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3); // temp = digit

		// Store digit as ASCII at buffer[digit_count]
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(PRINT_BUF_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2); // digit_count
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3); // temp (digit)
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(48); // '0'
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);

		// digit_count++
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2);

		// value /= 10
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(10);
		w.write(Instruction.I32_DIV_U);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(0);

		w.write(Instruction.BR, 0); // continue loop
		w.write(Instruction.END); // end loop
		w.write(Instruction.END); // end block

		w.write(Instruction.END); // end if (zero case else)

		// Copy reversed digits to output area
		// If negative, write '-' first
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1); // is_negative
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(OUT_BUF_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(45); // '-'
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		w.write(Instruction.END);

		// Copy digits in reverse from print buffer to output buffer
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(3); // i = 0

		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2); // digit_count
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);

		// outBuf[is_negative + i] = printBuf[digit_count - 1 - i]
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(OUT_BUF_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1); // is_negative (0 or 1)
		w.write(Instruction.I32_ADD);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3); // i
		w.write(Instruction.I32_ADD);

		// Load printBuf[digit_count - 1 - i]
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(PRINT_BUF_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2); // digit_count
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(3); // i
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);

		w.write(Instruction.I32_STORE8, 0x00, 0x00);

		// i++
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
			// Append newline: outBuf[is_negative + digit_count] = '\n'
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(OUT_BUF_OFFSET);
			w.write(Instruction.GET_LOCAL);
			w.writeSignedLeb128(1);
			w.write(Instruction.I32_ADD);
			w.write(Instruction.GET_LOCAL);
			w.writeSignedLeb128(2);
			w.write(Instruction.I32_ADD);
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(10); // '\n'
			w.write(Instruction.I32_STORE8, 0x00, 0x00);
		}

		// Build iov: {iov_base = outBuf, iov_len = is_negative + digit_count [+ 1]}
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(IOV_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(OUT_BUF_OFFSET);
		w.write(Instruction.I32_STORE, 0x02, 0x00); // align=2, offset=0

		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(IOV_OFFSET + 4);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1); // is_negative
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2); // digit_count
		w.write(Instruction.I32_ADD);
		if (appendNewline) {
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(1); // for newline
			w.write(Instruction.I32_ADD);
		}
		w.write(Instruction.I32_STORE, 0x02, 0x00);

		// fd_write(1, iov_offset, 1, nwritten_offset)
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1); // stdout
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(IOV_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1); // iovs_len
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(NWRITTEN_OFFSET);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(FUNC_FD_WRITE);
		w.write(Instruction.DROP);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the _write_str helper function that writes bytes from linear memory to
	 * stdout.
	 */
	private byte[] buildWriteStrBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		w.write(0); // 0 local groups (only params: offset i32, len i32)

		// Set iov_base = offset
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(IOV_OFFSET);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0); // offset
		w.write(Instruction.I32_STORE, 0x02, 0x00);

		// Set iov_len = len
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(IOV_OFFSET + 4);
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1); // len
		w.write(Instruction.I32_STORE, 0x02, 0x00);

		// fd_write(1, iov_offset, 1, nwritten_offset)
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1); // stdout
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(IOV_OFFSET);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1); // iovs_len
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(NWRITTEN_OFFSET);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(FUNC_FD_WRITE);
		w.write(Instruction.DROP);

		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the _print_val helper function that prints any Lisp value without a trailing
	 * newline. Handles null (nil), i31ref (integer), string struct, and cons struct
	 * (list).
	 */
	private byte[] buildPrintValBody(StringTable st) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);

		// param 0 = val (ref null eq)
		// local 1 = current (ref null eq) - for cons traversal
		// local 2 = first (i32) - for cons traversal
		w.write(2); // 2 local groups
		w.write(1); // 1 local of type (ref null eq)
		w.write(Type.REFNULL.code());
		w.writeHeapType(Type.EQ.code());
		w.write(1); // 1 local of type i32
		w.write(Type.I32);

		// === Check null (nil) ===
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0); // val
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40); // if void
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.nil.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.nil.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(FUNC_WRITE_STR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// === Check i31ref (integer) ===
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
		w.writeSignedLeb128(FUNC_PRINT_I32_NO_NL);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// === Check string struct ===
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(TYPE_STRING);
		w.write(Instruction.IF, 0x40);
		// Get offset
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(TYPE_STRING);
		w.writeSignedLeb128(0); // field 0: offset
		// Get length
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(TYPE_STRING);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(TYPE_STRING);
		w.writeSignedLeb128(1); // field 1: length
		w.write(Instruction.CALL);
		w.writeSignedLeb128(FUNC_WRITE_STR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// === Must be cons struct - print as list ===
		// Print "("
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.lparen.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.lparen.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(FUNC_WRITE_STR);

		// Initialize: current = val, first = 1 (true)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(0); // val
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(1); // current
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(1);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2); // first = true

		// block/loop for cons traversal
		w.write(Instruction.BLOCK, 0x40); // block $break
		w.write(Instruction.LOOP, 0x40); // loop $loop

		// if current is null, break
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1); // current
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.BR_IF, 1); // break to outer block

		// if current is NOT a cons cell (dotted pair)
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(TYPE_CONS);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40); // if void
		// Print " . "
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.dot.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.dot.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(FUNC_WRITE_STR);
		// Print current value
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.CALL);
		w.writeSignedLeb128(FUNC_PRINT_VAL);
		w.write(Instruction.BR, 2); // break to outer block (depth: if=0, loop=1, block=2)
		w.write(Instruction.END); // end if

		// If not first, print " "
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(2); // first
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.space.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.space.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(FUNC_WRITE_STR);
		w.write(Instruction.END);

		// Print car
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1); // current
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(TYPE_CONS);
		w.writeSignedLeb128(0); // field 0: car
		w.write(Instruction.CALL);
		w.writeSignedLeb128(FUNC_PRINT_VAL);

		// current = cdr
		w.write(Instruction.GET_LOCAL);
		w.writeSignedLeb128(1);
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(TYPE_CONS);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeSignedLeb128(TYPE_CONS);
		w.writeSignedLeb128(1); // field 1: cdr
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(1); // current = cdr

		// first = false
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(0);
		w.write(Instruction.SET_LOCAL);
		w.writeSignedLeb128(2);

		w.write(Instruction.BR, 0); // continue loop
		w.write(Instruction.END); // end loop
		w.write(Instruction.END); // end block

		// Print ")"
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.rparen.offset());
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(st.rparen.length());
		w.write(Instruction.CALL);
		w.writeSignedLeb128(FUNC_WRITE_STR);

		w.write(Instruction.END); // end function
		return body.toByteArray();
	}

	/**
	 * Checks if the expression matches the pattern (setq name (lambda (params...)
	 * body...)).
	 */
	private static boolean isSetqLambda(LispVal expr) {
		if (expr instanceof LispCons cons && cons.car() instanceof LispSymbol sym && "setq".equals(sym.name())) {
			List<LispVal> parts = cons.toList();
			if (parts.size() == 3 && parts.get(1) instanceof LispSymbol && parts.get(2) instanceof LispCons valueCons
					&& valueCons.car() instanceof LispSymbol lambdaSym && "lambda".equals(lambdaSym.name())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Extracts a DefunDecl from (setq name (lambda (params...) body...)).
	 */
	private static DefunDecl extractSetqLambda(LispVal expr) {
		List<LispVal> parts = ((LispCons) expr).toList();
		String funcName = ((LispSymbol) parts.get(1)).name();
		List<LispVal> lambdaParts = ((LispCons) parts.get(2)).toList();
		LispVal paramsVal = lambdaParts.get(1);
		List<String> paramNames;
		if (paramsVal instanceof LispNil) {
			paramNames = List.of();
		}
		else {
			paramNames = ((LispCons) paramsVal).toList().stream().map(p -> ((LispSymbol) p).name()).toList();
		}
		return new DefunDecl(funcName, paramNames, lambdaParts.subList(2, lambdaParts.size()));
	}

	private record DefunDecl(String name, List<String> paramNames, List<LispVal> bodyExprs) {
	}

	private record WasmFunctionInfo(String name, int paramCount, int typeIndex, int funcIndex) {
	}

	private static final class Ctx {

		final WasmWriter writer;

		final StringTable stringTable;

		Map<String, Integer> locals = new HashMap<>();

		Map<String, WasmFunctionInfo> functions = Map.of();

		int nextLocal = 0;

		Ctx(WasmWriter writer, StringTable stringTable) {
			this.writer = writer;
			this.stringTable = stringTable;
		}

		int allocLocal(String name) {
			int slot = this.nextLocal++;
			this.locals.put(name, slot);
			return slot;
		}

	}

	/**
	 * Tracks string data for the WASM data section. Fixed strings (nil, parens, etc.) are
	 * pre-allocated; user strings are added during compilation.
	 */
	static final class StringTable {

		private final ByteArrayOutputStream data = new ByteArrayOutputStream();

		private int nextOffset;

		final StringEntry nil;

		final StringEntry lparen;

		final StringEntry rparen;

		final StringEntry space;

		final StringEntry dot;

		final StringEntry newline;

		StringTable(int baseOffset) {
			this.nextOffset = baseOffset;
			this.nil = addString("nil");
			this.lparen = addString("(");
			this.rparen = addString(")");
			this.space = addString(" ");
			this.dot = addString(" . ");
			this.newline = addString("\n");
		}

		StringEntry addString(String s) {
			byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
			int offset = this.nextOffset;
			this.data.write(bytes, 0, bytes.length);
			this.nextOffset += bytes.length;
			return new StringEntry(offset, bytes.length);
		}

		byte[] toByteArray() {
			return this.data.toByteArray();
		}

		record StringEntry(int offset, int length) {
		}

	}

}

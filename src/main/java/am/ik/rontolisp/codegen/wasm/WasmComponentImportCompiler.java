package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.rontolisp.compiler.WitImportDirective;
import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wit.WitItem;
import am.ik.wit.WitParser;
import am.ik.wit.WitResolver;
import am.ik.wit.WitType;

import org.jspecify.annotations.Nullable;

/**
 * Compiles the internal {@code (rontolisp::%component-import "iface-id" "wit text"
 * ("member" "lisp-name") ...)} form a {@code rontolisp:wit-import} directive lowers to
 * under {@code --component}: each bound WIT function becomes a Lisp-callable synthetic
 * defun whose body marshals through the <strong>canonical ABI</strong> &mdash; the guest
 * side of a {@code canon lower}ed component import.
 *
 * <p>
 * The wrapper follows the {@link WasmImportCompiler} pattern exactly: it is registered
 * like a top-level defun (so {@code #'name} / {@code funcall} / {@code eval} work), calls
 * the imported function through a {@code PLACEHOLDER_FUNC_BASE + ordinal} index that the
 * {@link am.ik.wasm.WasmImportInjector} post-pass resolves, and its body is deferred
 * until the memory-helper indices are known. What is new is the marshalling: arguments
 * lower to the canonical flat representation (strings and {@code list<u8>} staged into
 * linear memory, {@code option} discriminants, 64-bit integers through the wide-int
 * convention), and results lift from the flat value or the return area per the canonical
 * ABI's layout rules ({@link WitCanonicalAbi}) into rontolisp values &mdash; a
 * {@code result} lifts to the {@code (:ok . V)} / {@code (:error . E)} envelope the
 * Lisp-side {@code rontolisp::%wit-result} wrapper unwraps (and whose error arm it
 * signals as {@code rontolisp:wit-error}).
 *
 * <p>
 * The wrapper stages per-call bytes above a heap-pointer mark and pops back to it
 * (intern-guarded, the {@code __ronto_alloc_reset} rule) after the lifted value is fully
 * on the GC heap, so a lowered call does not grow linear memory. Result buffers the host
 * allocates (through the {@code canon lower} realloc option, which is the shared memory
 * module's {@code cabi_realloc}) live in that allocator's own region and are not
 * reclaimed &mdash; the same convention as the fixed WASI adapter's lowered calls.
 */
final class WasmComponentImportCompiler {

	/** The number of reserved i32 scratch locals in a wrapper body. */
	private static final int I32_SCRATCH = 16;

	private WasmComponentImportCompiler() {
	}

	/**
	 * A bound WIT function of a component import.
	 *
	 * @param lispName the Lisp-visible synthetic defun name (the raw {@code pkg::%member}
	 * name for a result-returning function, the public name otherwise)
	 * @param module the WASM import module = the interface's canonical id
	 * @param field the WASM import field = the canonical-ABI function name (e.g.
	 * {@code "[method]bucket.get"})
	 * @param func the WIT function
	 * @param abi the layout calculator scoped to the interface
	 * @param sig the flat core signature of the lowered call
	 */
	record Decl(String lispName, String module, String field, WitResolver.Func func, WitCanonicalAbi abi,
			WitCanonicalAbi.FlatSig sig) {
	}

	/**
	 * A parsed {@code %component-import} form: the interface (for the component-level
	 * wiring) plus its bound functions.
	 *
	 * @param ifaceId the interface's canonical id (the component import name)
	 * @param iface the interface definition
	 * @param resolver the resolver over the parsed WIT document
	 * @param decls the bound functions, in WIT order
	 */
	record Import(String ifaceId, WitItem.InterfaceDef iface, WitResolver resolver, List<Decl> decls) {
	}

	/**
	 * Returns whether the form is a {@code (rontolisp::%component-import ...)} form.
	 * @param form the top-level form
	 * @return {@code true} for the internal component-import form
	 */
	static boolean isComponentImportForm(LispVal form) {
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol sym) {
			return sym.name()
				.equals(PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG, LispNames.COMPONENT_IMPORT));
		}
		return false;
	}

	/**
	 * Parses a {@code (rontolisp::%component-import "iface-id" "wit text" ("member"
	 * "lisp-name") ...)} form. The WIT text travels inside the form so this compiler
	 * reads no files (the browser playground has no filesystem).
	 * @param form the internal form
	 * @return the parsed import
	 */
	static Import parse(LispCons form) {
		List<LispVal> items = form.toList();
		if (items.size() < 3 || !(items.get(1) instanceof LispString ifaceId)
				|| !(items.get(2) instanceof LispString witSource)) {
			throw new UnsupportedOperationException("Malformed internal component-import form: " + form.print());
		}
		WitResolver resolver = new WitResolver(WitParser.parse(witSource.value()));
		WitItem.InterfaceDef iface = resolver.findInterface(ifaceId.value());
		if (iface == null) {
			throw new IllegalStateException(
					"Internal component-import form names an unknown interface: " + ifaceId.value());
		}
		WitCanonicalAbi abi = new WitCanonicalAbi(resolver, iface);
		List<WitResolver.Func> funcs = WitResolver.functions(iface);
		List<Decl> decls = new ArrayList<>();
		for (int i = 3; i < items.size(); i++) {
			if (!(items.get(i) instanceof LispCons pair) || !(pair.car() instanceof LispString member)
					|| !(pair.cdr() instanceof LispCons rest) || !(rest.car() instanceof LispString lispName)) {
				throw new UnsupportedOperationException(
						"Malformed internal component-import member: " + items.get(i).print());
			}
			WitResolver.Func func = funcs.stream()
				.filter(f -> member.value().equals(WitImportDirective.memberName(f)))
				.findFirst()
				.orElseThrow(() -> new IllegalStateException(
						"Internal component-import form names an unknown member: " + member.value()));
			decls.add(new Decl(lispName.value(), ifaceId.value(), cabiFieldName(func), func, abi, abi.flatSig(func)));
		}
		return new Import(ifaceId.value(), iface, resolver, decls);
	}

	/**
	 * The canonical-ABI function name a WIT function is imported under (the core import
	 * field, and the imported instance's export name).
	 * @param func the WIT function
	 * @return the canonical name (e.g. {@code "[method]bucket.get"})
	 */
	static String cabiFieldName(WitResolver.Func func) {
		String resource = func.resource();
		if (resource == null) {
			return func.def().name();
		}
		return switch (func.def().kind()) {
			case CONSTRUCTOR -> "[constructor]" + resource;
			case STATIC -> "[static]" + resource + "." + func.def().name();
			case PLAIN -> "[method]" + resource + "." + func.def().name();
		};
	}

	/** Returns the WASM parameter types of the lowered call's core signature. */
	static Type[] hostParamTypes(Decl decl) {
		return decl.sig().params();
	}

	/** Returns the WASM result types of the lowered call's core signature. */
	static Type[] hostResultTypes(Decl decl) {
		return decl.sig().results();
	}

	/**
	 * Returns whether the lowered call touches linear memory -- an indirect result (the
	 * return pointer) or a parameter carrying staged bytes (string / {@code list},
	 * directly or inside an option) -- and therefore whether its {@code canon lower}
	 * needs the canonical memory / realloc / string-encoding options.
	 * @param decl the bound function
	 * @return {@code true} when the lowering needs the canonical memory options
	 */
	static boolean needsMemory(Decl decl) {
		if (decl.sig().retptr()) {
			return true;
		}
		for (var param : decl.func().def().func().params()) {
			if (stagesMemory(param.type(), decl.abi())) {
				return true;
			}
		}
		return false;
	}

	private static boolean stagesMemory(WitType type, WitCanonicalAbi abi) {
		return switch (type) {
			case WitType.Prim prim -> "string".equals(prim.name());
			case WitType.ListOf ignored -> true;
			case WitType.OptionOf opt -> stagesMemory(opt.element(), abi);
			case WitType.Named named ->
				abi.resolveNamed(named) instanceof WitItem.TypeAlias alias && stagesMemory(alias.target(), abi);
			default -> false;
		};
	}

	/** Returns the Lisp parameter count of the synthetic defun. */
	static int lispArity(Decl decl) {
		boolean method = decl.func().resource() != null && decl.func().def().kind() == WitItem.FuncKind.PLAIN;
		return (method ? 1 : 0) + decl.func().def().func().params().size();
	}

	/**
	 * Builds the complete code entry (local declarations + body) of the Lisp-callable
	 * wrapper: lower every argument to its flat representation, call the imported
	 * function through its placeholder index, lift the result, pop the staging area.
	 * @param ctxBuilder the shared context builder
	 * @param decl the bound function
	 * @param ordinal the import's ordinal (shared with {@code rontolisp:wasm-import})
	 * @param allocFuncIndex the function index of {@code __ronto_alloc}
	 * @param strFromMemFuncIndex the function index of {@code _str_from_mem}
	 * @return the code entry bytes
	 */
	static byte[] buildWrapperBody(WasmLispCompiler.Ctx.Builder ctxBuilder, Decl decl, int ordinal, int allocFuncIndex,
			int strFromMemFuncIndex) {
		ByteArrayOutputStream bodyStream = new ByteArrayOutputStream();
		am.ik.wasm.WasmWriter writer = new am.ik.wasm.WasmWriter(bodyStream);
		WasmLispCompiler.Ctx ctx = ctxBuilder.writer(writer).bodyStream(bodyStream).build();
		int numParams = lispArity(decl);
		Gen gen = new Gen(ctx, decl, numParams, ordinal, allocFuncIndex, strFromMemFuncIndex);
		gen.emitBody();
		// Locals: the fixed i32 scratch pool, one i64, then the eq temps allocTemp
		// handed out during emission.
		int numEqTemps = ctx.nextLocal - (numParams + 1 + I32_SCRATCH + 1);
		ByteArrayOutputStream entry = new ByteArrayOutputStream();
		am.ik.wasm.WasmWriter entryWriter = new am.ik.wasm.WasmWriter(entry);
		entryWriter.write(numEqTemps > 0 ? 3 : 2);
		entryWriter.writeUnsignedLeb128(I32_SCRATCH);
		entryWriter.write(Type.I32);
		entryWriter.writeUnsignedLeb128(1);
		entryWriter.write(Type.I64);
		if (numEqTemps > 0) {
			entryWriter.writeUnsignedLeb128(numEqTemps);
			entryWriter.write(Type.REFNULL.code());
			entryWriter.writeHeapType(Type.EQ.code());
		}
		entryWriter.write((Object) bodyStream.toByteArray());
		return entry.toByteArray();
	}

	// The per-body generator: tracks the i32 scratch cursor (stack discipline around
	// recursion) and emits the lower/call/lift sequence.
	private static final class Gen {

		private final WasmLispCompiler.Ctx ctx;

		private final am.ik.wasm.WasmWriter w;

		private final Decl decl;

		private final int i32Base;

		private final int i64Local;

		private final int allocFuncIndex;

		private final int strFromMemFuncIndex;

		private final int ordinal;

		private int i32Cursor;

		private final int mark;

		Gen(WasmLispCompiler.Ctx ctx, Decl decl, int numParams, int ordinal, int allocFuncIndex,
				int strFromMemFuncIndex) {
			this.ctx = ctx;
			this.w = ctx.writer;
			this.decl = decl;
			this.i32Base = numParams + 1;
			this.i64Local = numParams + 1 + I32_SCRATCH;
			this.ordinal = ordinal;
			this.allocFuncIndex = allocFuncIndex;
			this.strFromMemFuncIndex = strFromMemFuncIndex;
			ctx.nextLocal = numParams + 1 + I32_SCRATCH + 1;
			this.mark = allocI32();
		}

		private int allocI32() {
			if (this.i32Cursor >= I32_SCRATCH) {
				throw new IllegalStateException("component-import wrapper ran out of i32 scratch locals for '"
						+ this.decl.lispName() + "' (nesting too deep)");
			}
			return this.i32Base + this.i32Cursor++;
		}

		void emitBody() {
			// mark = align8(HEAP_PTR), and HEAP_PTR = mark: the staging snapshot the
			// epilogue pops back to, AND the alignment floor of everything staged above
			// it. The canonical ABI rejects a misaligned return area outright ("pointer
			// not aligned"), and the bump heap is NOT always 8-aligned when a call
			// arrives: `_intern` copies a first-seen symbol's bytes into the permanent
			// low region and advances the pointer by their exact length. So align here
			// rather than trust the caller.
			i32Const(WasmLispCompiler.HEAP_PTR_ADDR);
			alignedHeapTop(() -> loadCell(WasmLispCompiler.HEAP_PTR_ADDR));
			this.w.write(Instruction.TEE_LOCAL);
			this.w.writeSignedLeb128(this.mark);
			this.w.write(Instruction.I32_STORE, 0x02, 0x00);
			// Lower every Lisp argument onto the stack in flat order.
			WitResolver.Func func = this.decl.func();
			int slot = 1;
			if (func.resource() != null && func.def().kind() == WitItem.FuncKind.PLAIN) {
				getLocal(slot++);
				WasmEmitHelper.castI31GetS(this.ctx);
			}
			for (var param : func.def().func().params()) {
				emitLowerParam(param.type(), slot++);
			}
			// The return pointer, when the results are indirect: bump-allocated above the
			// staged arguments (__ronto_alloc aligns to 8, satisfying every canonical
			// alignment), reclaimed by the epilogue's pop.
			int rp = -1;
			WitCanonicalAbi.FlatSig sig = this.decl.sig();
			if (sig.retptr()) {
				rp = allocI32();
				i32Const(sig.retSize());
				this.w.write(Instruction.CALL);
				this.w.writeSignedLeb128(this.allocFuncIndex);
				this.w.write(Instruction.TEE_LOCAL);
				this.w.writeSignedLeb128(rp);
			}
			this.w.write(Instruction.CALL);
			this.w.writeUnsignedLeb128(WasmImportCompiler.PLACEHOLDER_FUNC_BASE + this.ordinal);
			// Lift the result into one boxed value.
			WitType result = this.decl.abi().resultType(func);
			if (result == null) {
				refNullEq();
			}
			else if (sig.retptr()) {
				emitLiftAt(result, rp, 0);
			}
			else {
				emitLiftFlat(result);
			}
			int resultTmp = this.ctx.allocTemp();
			setLocal(resultTmp);
			// HEAP_PTR = align8(max(mark, intern high-water)): pop the per-call staging,
			// but never below the permanent interned-symbol region (the
			// __ronto_alloc_reset rule) -- and keep the pointer 8-aligned, because that
			// region's high-water is not (see the entry prologue).
			int water = allocI32();
			loadCell(WasmLispCompiler.RT_INTERN_HEAP_ADDR);
			setLocal(water);
			i32Const(WasmLispCompiler.HEAP_PTR_ADDR);
			alignedHeapTop(() -> {
				getLocal(this.mark);
				getLocal(water);
				getLocal(this.mark);
				getLocal(water);
				this.w.write(Instruction.I32_GT_U);
				this.w.write(Instruction.SELECT);
			});
			this.w.write(Instruction.I32_STORE, 0x02, 0x00);
			getLocal(resultTmp);
			this.w.write(Instruction.END);
		}

		// Pushes align8(the i32 the given emitter pushes).
		private void alignedHeapTop(Runnable pushValue) {
			pushValue.run();
			i32Const(7);
			this.w.write(Instruction.I32_ADD);
			i32Const(-8);
			this.w.write(Instruction.I32_AND);
		}

		// --- argument lowering ---

		// Pushes the flat value(s) of the boxed Lisp argument in the given local slot.
		private void emitLowerParam(WitType type, int slot) {
			WitType t = resolveAlias(type);
			switch (t) {
				case WitType.Prim prim -> {
					switch (prim.name()) {
						case "bool" -> {
							getLocal(slot);
							this.w.write(Instruction.REF_IS_NULL);
							this.w.write(Instruction.I32_EQZ);
						}
						case "s8", "u8", "s16", "u16" -> {
							getLocal(slot);
							WasmEmitHelper.castI31GetS(this.ctx);
						}
						case "s32" -> {
							getLocal(slot);
							WasmEmitHelper.castFloatGetF64(this.ctx);
							this.w.write(Instruction.I32_TRUNC_S_F64);
						}
						case "u32" -> {
							getLocal(slot);
							WasmEmitHelper.castFloatGetF64(this.ctx);
							this.w.write(Instruction.I32_TRUNC_U_F64);
						}
						case "s64" -> {
							getLocal(slot);
							WasmEmitHelper.castFloatGetF64(this.ctx);
							this.w.write(Instruction.I64_TRUNC_S_F64);
						}
						case "u64" -> {
							getLocal(slot);
							WasmEmitHelper.castFloatGetF64(this.ctx);
							this.w.write(Instruction.I64_TRUNC_U_F64);
						}
						case "f32" -> {
							getLocal(slot);
							WasmEmitHelper.castFloatGetF64(this.ctx);
							this.w.write(Instruction.F32_DEMOTE_F64);
						}
						case "f64" -> {
							getLocal(slot);
							WasmEmitHelper.castFloatGetF64(this.ctx);
						}
						case "string" -> emitStageStringParam(slot);
						default -> throw paramUnsupported(prim.name());
					}
				}
				case WitType.ListOf list when isU8(list.element()) -> emitStageStringParam(slot);
				case WitType.BorrowOf ignored -> {
					getLocal(slot);
					WasmEmitHelper.castI31GetS(this.ctx);
				}
				case WitType.OwnOf ignored -> {
					getLocal(slot);
					WasmEmitHelper.castI31GetS(this.ctx);
				}
				case WitType.OptionOf opt -> emitLowerOptionParam(opt, slot);
				case WitType.Named named -> {
					if (this.decl.abi().resolveNamed(named) instanceof WitItem.ResourceDef) {
						getLocal(slot);
						WasmEmitHelper.castI31GetS(this.ctx);
					}
					else {
						throw paramUnsupported(named.name());
					}
				}
				default -> throw paramUnsupported(t.getClass().getSimpleName());
			}
		}

		// option<T> parameter: flats = [disc i32] + flats(T). The payload flats are
		// computed into scratch locals on the some-branch (zeroed on the none-branch) so
		// both branches leave the stack untouched, then pushed in order.
		private void emitLowerOptionParam(WitType.OptionOf opt, int slot) {
			List<Type> payloadFlats = this.decl.abi().flatTypes(opt.element());
			int disc = allocI32();
			List<Integer> payloadLocals = new ArrayList<>();
			for (Type flat : payloadFlats) {
				if (flat == Type.I32) {
					payloadLocals.add(allocI32());
				}
				else if (flat == Type.I64) {
					payloadLocals.add(this.i64Local);
				}
				else {
					throw paramUnsupported("option over a float payload");
				}
			}
			getLocal(slot);
			this.w.write(Instruction.REF_IS_NULL);
			this.w.write(Instruction.IF, 0x40);
			i32Const(0);
			setLocal(disc);
			for (int i = 0; i < payloadFlats.size(); i++) {
				if (payloadFlats.get(i) == Type.I64) {
					this.w.write(Instruction.I64_CONST);
					this.w.writeSignedLeb128(0);
				}
				else {
					i32Const(0);
				}
				setLocal(payloadLocals.get(i));
			}
			this.w.write(Instruction.ELSE);
			i32Const(1);
			setLocal(disc);
			emitLowerParam(opt.element(), slot);
			for (int i = payloadFlats.size() - 1; i >= 0; i--) {
				setLocal(payloadLocals.get(i));
			}
			this.w.write(Instruction.END);
			getLocal(disc);
			for (int local : payloadLocals) {
				getLocal(local);
			}
		}

		// Stages a Lisp string / byte string into linear memory (advancing HEAP_PTR so a
		// later staged parameter cannot clobber it -- the epilogue pops the whole
		// region) and pushes its canonical (content ptr, content len) pair.
		private void emitStageStringParam(int slot) {
			int dst = allocI32();
			int total = allocI32();
			// dst = HEAP_PTR; grow-guard to dst + framed byte length
			loadCell(WasmLispCompiler.HEAP_PTR_ADDR);
			setLocal(dst);
			getLocal(slot);
			WasmEmitHelper.emitStrBytesArray(this.ctx);
			this.w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
			setLocal(total);
			WasmEmitHelper.emitGrowHeapTo(this.w, () -> {
				getLocal(dst);
				getLocal(total);
				this.w.write(Instruction.I32_ADD);
			});
			// total = _str_to_mem(value, dst) (the framed byte count, quotes included)
			getLocal(slot);
			getLocal(dst);
			WasmEmitHelper.emitStrToMemCall(this.w);
			setLocal(total);
			// HEAP_PTR = align8(dst + total)
			i32Const(WasmLispCompiler.HEAP_PTR_ADDR);
			getLocal(dst);
			getLocal(total);
			this.w.write(Instruction.I32_ADD);
			i32Const(7);
			this.w.write(Instruction.I32_ADD);
			i32Const(-8);
			this.w.write(Instruction.I32_AND);
			this.w.write(Instruction.I32_STORE, 0x02, 0x00);
			// (content ptr, content len) strips the frame quotes
			getLocal(dst);
			i32Const(1);
			this.w.write(Instruction.I32_ADD);
			getLocal(total);
			i32Const(2);
			this.w.write(Instruction.I32_SUB);
		}

		// --- result lifting ---

		// Lifts a single-flat result already on the stack into a boxed value.
		private void emitLiftFlat(WitType type) {
			WitType t = resolveAlias(type);
			switch (t) {
				case WitType.Prim prim -> {
					switch (prim.name()) {
						case "bool" -> WasmEmitHelper.emitBoolFromI32(this.ctx);
						case "s8", "u8", "s16", "u16", "s32" -> boxI31();
						case "u32" -> {
							this.w.write(Instruction.I64_EXTEND_U_I32);
							boxI64(false);
						}
						case "s64" -> boxI64(true);
						case "u64" -> boxI64(false);
						case "f32" -> {
							this.w.write(Instruction.F64_PROMOTE_F32);
							boxFloat();
						}
						case "f64" -> boxFloat();
						case "char" -> boxChar();
						default -> throw resultUnsupported(prim.name());
					}
				}
				case WitType.BorrowOf ignored -> boxI31();
				case WitType.OwnOf ignored -> boxI31();
				case WitType.ResultOf ignored -> emitLiftVariantFromStackDisc(t);
				case WitType.OptionOf ignored -> emitLiftVariantFromStackDisc(t);
				case WitType.Named named -> {
					switch (this.decl.abi().resolveNamed(named)) {
						case WitItem.ResourceDef ignored -> boxI31();
						case WitItem.EnumDef ignored -> emitLiftVariantFromStackDisc(t);
						case WitItem.VariantDef ignored -> emitLiftVariantFromStackDisc(t);
						default -> throw resultUnsupported(named.name());
					}
				}
				default -> throw resultUnsupported(t.getClass().getSimpleName());
			}
		}

		// A single-flat variant-shaped result (every arm payload-less): the flat i32 IS
		// the discriminant.
		private void emitLiftVariantFromStackDisc(WitType type) {
			int disc = allocI32();
			setLocal(disc);
			emitVariantDispatch(type, disc, -1, 0);
		}

		// Lifts the canonical memory representation of `type` at [base + offset] into a
		// boxed value.
		private void emitLiftAt(WitType type, int base, int offset) {
			WitType t = resolveAlias(type);
			switch (t) {
				case WitType.Prim prim -> {
					switch (prim.name()) {
						case "bool" -> {
							load(base, offset, Instruction.I32_LOAD8_U, 0);
							WasmEmitHelper.emitBoolFromI32(this.ctx);
						}
						case "s8" -> {
							load(base, offset, Instruction.I32_LOAD8_S, 0);
							boxI31();
						}
						case "u8" -> {
							load(base, offset, Instruction.I32_LOAD8_U, 0);
							boxI31();
						}
						case "s16" -> {
							load(base, offset, Instruction.I32_LOAD16_S, 1);
							boxI31();
						}
						case "u16" -> {
							load(base, offset, Instruction.I32_LOAD16_U, 1);
							boxI31();
						}
						case "s32" -> {
							load(base, offset, Instruction.I64_LOAD32_S, 2);
							boxI64(true);
						}
						case "u32" -> {
							load(base, offset, Instruction.I64_LOAD32_U, 2);
							boxI64(false);
						}
						case "s64" -> {
							load(base, offset, Instruction.I64_LOAD, 3);
							boxI64(true);
						}
						case "u64" -> {
							load(base, offset, Instruction.I64_LOAD, 3);
							boxI64(false);
						}
						case "f32" -> {
							load(base, offset, Instruction.F32_LOAD, 2);
							this.w.write(Instruction.F64_PROMOTE_F32);
							boxFloat();
						}
						case "f64" -> {
							load(base, offset, Instruction.F64_LOAD, 3);
							boxFloat();
						}
						case "char" -> {
							load(base, offset, Instruction.I32_LOAD, 2);
							boxChar();
						}
						case "string" -> emitLiftString(base, offset);
						default -> throw resultUnsupported(prim.name());
					}
				}
				case WitType.ListOf list when isU8(list.element()) -> emitLiftString(base, offset);
				case WitType.ListOf list -> emitLiftList(list.element(), base, offset);
				case WitType.BorrowOf ignored -> {
					load(base, offset, Instruction.I32_LOAD, 2);
					boxI31();
				}
				case WitType.OwnOf ignored -> {
					load(base, offset, Instruction.I32_LOAD, 2);
					boxI31();
				}
				case WitType.OptionOf ignored -> emitLiftVariantAt(t, base, offset);
				case WitType.ResultOf ignored -> emitLiftVariantAt(t, base, offset);
				case WitType.TupleOf ignored -> emitLiftRecordAt(t, base, offset, false);
				case WitType.Named named -> {
					switch (this.decl.abi().resolveNamed(named)) {
						case WitItem.ResourceDef ignored -> {
							load(base, offset, Instruction.I32_LOAD, 2);
							boxI31();
						}
						case WitItem.RecordDef ignored -> emitLiftRecordAt(t, base, offset, true);
						case WitItem.VariantDef ignored -> emitLiftVariantAt(t, base, offset);
						case WitItem.EnumDef ignored -> emitLiftVariantAt(t, base, offset);
						default -> throw resultUnsupported(named.name());
					}
				}
				default -> throw resultUnsupported(t.getClass().getSimpleName());
			}
		}

		// string / list<u8> at [base + offset]: (ptr @+0, byte len @+4) -> a fresh Lisp
		// string over the host-written bytes.
		private void emitLiftString(int base, int offset) {
			load(base, offset, Instruction.I32_LOAD, 2);
			load(base, offset + 4, Instruction.I32_LOAD, 2);
			this.w.write(Instruction.CALL);
			this.w.writeSignedLeb128(this.strFromMemFuncIndex);
		}

		// A variant-shaped value (variant / enum / option / result) in memory: load the
		// discriminant, dispatch to the per-case constructors.
		private void emitLiftVariantAt(WitType type, int base, int offset) {
			WitCanonicalAbi.VariantInfo info = this.decl.abi().variantInfo(type);
			int disc = allocI32();
			int discOp = switch (info.discSize()) {
				case 1 -> Instruction.I32_LOAD8_U;
				case 2 -> Instruction.I32_LOAD16_U;
				default -> Instruction.I32_LOAD;
			};
			load(base, offset, discOp, info.discSize() == 1 ? 0 : info.discSize() == 2 ? 1 : 2);
			setLocal(disc);
			emitVariantDispatch(type, disc, base, offset + info.payloadOffset());
		}

		// Emits the if-chain over a variant's discriminant local. payloadBase = -1 means
		// no memory payload exists (the single-flat, all-arms-payload-less shape).
		private void emitVariantDispatch(WitType type, int discLocal, int payloadBase, int payloadOffset) {
			WitCanonicalAbi.VariantInfo info = this.decl.abi().variantInfo(type);
			int n = info.names().size();
			for (int i = 0; i < n - 1; i++) {
				getLocal(discLocal);
				i32Const(i);
				this.w.write(Instruction.I32_EQ);
				this.w.write(Instruction.IF);
				this.w.write(Type.REFNULL.code());
				this.w.writeHeapType(Type.EQ.code());
				emitVariantCase(type, info, i, payloadBase, payloadOffset);
				this.w.write(Instruction.ELSE);
			}
			emitVariantCase(type, info, n - 1, payloadBase, payloadOffset);
			for (int i = 0; i < n - 1; i++) {
				this.w.write(Instruction.END);
			}
		}

		// One case's value: option -> nil / payload; result -> the (:ok . V) /
		// (:error . E) envelope; enum -> the case keyword; variant -> the keyword, or
		// (keyword . payload) when the case carries one.
		private void emitVariantCase(WitType type, WitCanonicalAbi.VariantInfo info, int index, int payloadBase,
				int payloadOffset) {
			WitType payload = info.payloads().get(index);
			WitType resolved = resolveAlias(type);
			if (resolved instanceof WitType.OptionOf) {
				if (index == 0) {
					refNullEq();
				}
				else {
					emitPayloadOrNil(payload, payloadBase, payloadOffset);
				}
				return;
			}
			if (resolved instanceof WitType.ResultOf) {
				WasmEmitHelper.compileStringLiteral(index == 0 ? ":ok" : ":error", this.ctx);
				emitPayloadOrNil(payload, payloadBase, payloadOffset);
				newCons();
				return;
			}
			// enum / variant: the case keyword, dotted with the payload when present.
			WasmEmitHelper.compileStringLiteral(":" + info.names().get(index), this.ctx);
			if (payload != null) {
				emitPayloadOrNil(payload, payloadBase, payloadOffset);
				newCons();
			}
		}

		private void emitPayloadOrNil(@Nullable WitType payload, int payloadBase, int payloadOffset) {
			if (payload == null) {
				refNullEq();
			}
			else {
				if (payloadBase < 0) {
					throw new IllegalStateException("a payload-bearing case cannot be single-flat");
				}
				emitLiftAt(payload, payloadBase, payloadOffset);
			}
		}

		// record -> (:field value ...) keyword plist; tuple -> (v0 v1 ...) proper list.
		private void emitLiftRecordAt(WitType type, int base, int offset, boolean plist) {
			WitCanonicalAbi.RecordInfo info = this.decl.abi().recordInfo(type);
			int conses = 0;
			for (int i = 0; i < info.names().size(); i++) {
				if (plist) {
					WasmEmitHelper.compileStringLiteral(":" + info.names().get(i), this.ctx);
					conses++;
				}
				emitLiftAt(info.types().get(i), base, offset + info.offsets().get(i));
				conses++;
			}
			refNullEq();
			for (int i = 0; i < conses; i++) {
				newCons();
			}
		}

		// list<T> at [base + offset]: (element base @+0, count @+4) -> a proper list,
		// built back to front so the accumulator is the cdr.
		private void emitLiftList(WitType element, int base, int offset) {
			int save = this.i32Cursor;
			int elems = allocI32();
			int idx = allocI32();
			int elemBase = allocI32();
			int acc = this.ctx.allocTemp();
			int elemSize = this.decl.abi().size(element);
			load(base, offset, Instruction.I32_LOAD, 2);
			setLocal(elems);
			load(base, offset + 4, Instruction.I32_LOAD, 2);
			setLocal(idx);
			refNullEq();
			setLocal(acc);
			this.w.write(Instruction.BLOCK, 0x40);
			this.w.write(Instruction.LOOP, 0x40);
			// while (idx != 0) { idx--; acc = cons(lift(elems[idx]), acc) }
			getLocal(idx);
			this.w.write(Instruction.I32_EQZ);
			this.w.write(Instruction.BR_IF);
			this.w.writeSignedLeb128(1);
			getLocal(idx);
			i32Const(1);
			this.w.write(Instruction.I32_SUB);
			setLocal(idx);
			getLocal(elems);
			getLocal(idx);
			i32Const(elemSize);
			this.w.write(Instruction.I32_MUL);
			this.w.write(Instruction.I32_ADD);
			setLocal(elemBase);
			emitLiftAt(element, elemBase, 0);
			getLocal(acc);
			newCons();
			setLocal(acc);
			this.w.write(Instruction.BR);
			this.w.writeSignedLeb128(0);
			this.w.write(Instruction.END);
			this.w.write(Instruction.END);
			getLocal(acc);
			this.i32Cursor = save;
		}

		// --- tiny emission helpers ---

		private WitType resolveAlias(WitType type) {
			if (type instanceof WitType.Named named
					&& this.decl.abi().resolveNamed(named) instanceof WitItem.TypeAlias alias) {
				return resolveAlias(alias.target());
			}
			return type;
		}

		private boolean isU8(WitType type) {
			return resolveAlias(type) instanceof WitType.Prim prim && "u8".equals(prim.name());
		}

		private void boxI31() {
			this.w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		}

		private void boxFloat() {
			this.w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			this.w.writeSignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		}

		private void boxChar() {
			this.w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			this.w.writeSignedLeb128(WasmLispCompiler.TYPE_CHAR);
		}

		// Boxes the i64 on the stack: an i31 when it fits, else the wide-int float
		// convention (the time built-ins' precedent for values beyond the i31 range).
		private void boxI64(boolean signed) {
			setLocal64();
			getLocal64();
			this.w.write(Instruction.I64_CONST);
			this.w.writeSignedLeb128(1 << 30);
			this.w.write(signed ? Instruction.I64_LT_S : Instruction.I64_LT_U);
			if (signed) {
				getLocal64();
				this.w.write(Instruction.I64_CONST);
				this.w.writeSignedLeb128(-(1 << 30));
				this.w.write(Instruction.I64_GE_S);
				this.w.write(Instruction.I32_AND);
			}
			this.w.write(Instruction.IF);
			this.w.write(Type.REFNULL.code());
			this.w.writeHeapType(Type.EQ.code());
			getLocal64();
			this.w.write(Instruction.I32_WRAP_I64);
			boxI31();
			this.w.write(Instruction.ELSE);
			getLocal64();
			this.w.write(signed ? Instruction.F64_CONVERT_S_I64 : Instruction.F64_CONVERT_U_I64);
			boxFloat();
			this.w.write(Instruction.END);
		}

		private void newCons() {
			this.w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			this.w.writeSignedLeb128(WasmLispCompiler.TYPE_CONS);
		}

		private void refNullEq() {
			this.w.write(Instruction.REF_NULL);
			this.w.writeHeapType(Type.EQ.code());
		}

		private void load(int baseLocal, int offset, int loadOp, int align) {
			getLocal(baseLocal);
			this.w.write(loadOp, align);
			this.w.writeUnsignedLeb128(offset);
		}

		private void loadCell(int addr) {
			i32Const(addr);
			this.w.write(Instruction.I32_LOAD, 0x02, 0x00);
		}

		private void getLocal(int slot) {
			this.w.write(Instruction.GET_LOCAL);
			this.w.writeSignedLeb128(slot);
		}

		private void setLocal(int slot) {
			this.w.write(Instruction.SET_LOCAL);
			this.w.writeSignedLeb128(slot);
		}

		private void setLocal64() {
			setLocal(this.i64Local);
		}

		private void getLocal64() {
			getLocal(this.i64Local);
		}

		private void i32Const(int value) {
			this.w.write(Instruction.I32_CONST);
			this.w.writeSignedLeb128(value);
		}

		private UnsupportedOperationException paramUnsupported(String what) {
			return new UnsupportedOperationException("'" + this.decl.lispName() + "': the WIT parameter type '" + what
					+ "' does not cross the component import boundary yet");
		}

		private UnsupportedOperationException resultUnsupported(String what) {
			return new UnsupportedOperationException("'" + this.decl.lispName() + "': the WIT result type '" + what
					+ "' does not cross the component import boundary yet");
		}

	}

}

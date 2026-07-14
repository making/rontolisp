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
 * linear memory, a {@code variant} / {@code enum} / {@code result} / {@code option} to
 * its discriminant plus the joined payload flats, a {@code record} / {@code tuple} to its
 * fields' flats, 64-bit integers through the wide-int convention), and results lift from
 * the flat value or the return area per the canonical ABI's layout rules
 * ({@link WitCanonicalAbi}) into rontolisp values &mdash; a {@code result} lifts to the
 * {@code (:ok . V)} / {@code (:error . E)} envelope the Lisp-side
 * {@code rontolisp::%wit-result} wrapper unwraps (and whose error arm it signals as
 * {@code rontolisp:wit-error}).
 *
 * <p>
 * Lowering and lifting are mirror images of one shape, which is what makes a lifted value
 * passable straight back into another call: a variant case is the keyword {@code :get}
 * (or {@code (:other . "PATCH")} when it carries a payload), an enum is a keyword, a
 * record is a keyword plist, an option is the value or {@code nil}. The one thing
 * lowering refuses that lifting does not is a {@code list<T>} (other than
 * {@code list<u8>}): writing a canonical array into linear memory is a different
 * mechanism from flattening, and nothing needs it yet.
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

	/**
	 * The ceiling on a wrapper's scratch locals of one core type. The pools are SIZED BY
	 * MEASUREMENT ({@link #buildWrapperBody} emits the body twice), so this is only a
	 * runaway guard -- no real WIT comes near it, and a program that did would get a
	 * clear error rather than a mis-sized frame.
	 */
	private static final int MAX_SCRATCH = 512;

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
	 * @param ifaceId the interface's canonical id (the component import name)
	 * @param iface the interface definition
	 * @param resolver the resolver over the parsed WIT document
	 * @param decls the bound functions, in WIT order
	 */
	/**
	 * A bound resource {@code drop}.
	 * <p>
	 * Not a {@link Decl}: {@code canon resource.drop} is a different emission kind. It
	 * produces a CORE function directly, with no component function alias and no
	 * {@code canon lower} behind it -- which is why the user imports' core-function count
	 * and their component-function count stop being the same number the moment a drop
	 * exists.
	 *
	 * @param lispName the Lisp-visible synthetic defun name ({@code kv:bucket-drop})
	 * @param module the WASM import module = the interface's canonical id
	 * @param field the WASM import field = {@code "[resource-drop]bucket"}
	 * @param resource the resource's name in its defining interface
	 */
	record Drop(String lispName, String module, String field, String resource) {
	}

	record Import(String ifaceId, WitItem.InterfaceDef iface, WitResolver resolver, List<Decl> decls,
			List<Drop> drops) {
	}

	/**
	 * Orders the imports so that an interface is imported <strong>before</strong> any
	 * interface that {@code use}s its resources.
	 * <p>
	 * The component wiring has no choice about this: a used resource is projected out of
	 * the DEFINING interface's imported instance and pointed at by an {@code alias outer}
	 * inside the dependent's instance type, so the provider's instance must already
	 * exist. (Applied once, where the imports are collected, so that every consumer --
	 * the type / import / alias emission, the synthesized core instances and the core
	 * module's instantiation arguments -- agrees on one order.) The sort is stable, so a
	 * program whose interfaces use nothing from each other keeps its source order and its
	 * bytes.
	 * @param imports the imports in program order
	 * @return the imports in dependency order
	 * @throws UnsupportedOperationException when two interfaces use each other's
	 * resources
	 */
	static List<Import> inDependencyOrder(List<Import> imports) {
		if (imports.size() < 2) {
			return imports;
		}
		final java.util.Map<String, Import> byId = new java.util.LinkedHashMap<>();
		for (Import imported : imports) {
			byId.put(imported.ifaceId(), imported);
		}
		final List<Import> ordered = new ArrayList<>();
		final java.util.Set<String> done = new java.util.LinkedHashSet<>();
		final java.util.Set<String> visiting = new java.util.LinkedHashSet<>();
		for (Import imported : imports) {
			visit(imported, byId, ordered, done, visiting);
		}
		return ordered;
	}

	private static void visit(Import imported, java.util.Map<String, Import> byId, List<Import> ordered,
			java.util.Set<String> done, java.util.Set<String> visiting) {
		if (done.contains(imported.ifaceId())) {
			return;
		}
		if (!visiting.add(imported.ifaceId())) {
			throw new UnsupportedOperationException(
					"the WIT interfaces " + visiting + " use each other's resources, so neither can be imported first");
		}
		for (WitComponentTypeEncoder.ForeignResource foreign : WitComponentTypeEncoder.foreignResourcesOf(imported)) {
			Import owner = byId.get(foreign.ownerIfaceId());
			// An owner the program did not import is reported where the whole picture is
			// known (WasmComponentBuilder.appendUserImports), with the fix to make.
			if (owner != null) {
				visit(owner, byId, ordered, done, visiting);
			}
		}
		visiting.remove(imported.ifaceId());
		done.add(imported.ifaceId());
		ordered.add(imported);
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
		List<Drop> drops = new ArrayList<>();
		for (int i = 3; i < items.size(); i++) {
			if (!(items.get(i) instanceof LispCons pair) || !(pair.cdr() instanceof LispCons rest)) {
				throw new UnsupportedOperationException(
						"Malformed internal component-import member: " + items.get(i).print());
			}
			// (:drop "bucket" "kv:bucket-drop") -- the keyword head is what tells a drop
			// apart from a ("member" "lisp-name") function binding.
			if (pair.car() instanceof LispSymbol keyword && keyword.isKeyword()) {
				if (!":drop".equals(keyword.name()) || !(rest.car() instanceof LispString resource)
						|| !(rest.cdr() instanceof LispCons tail) || !(tail.car() instanceof LispString dropName)) {
					throw new UnsupportedOperationException(
							"Malformed internal component-import member: " + items.get(i).print());
				}
				drops.add(new Drop(dropName.value(), ifaceId.value(), "[resource-drop]" + resource.value(),
						resource.value()));
				continue;
			}
			if (!(pair.car() instanceof LispString member) || !(rest.car() instanceof LispString lispName)) {
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
		return new Import(ifaceId.value(), iface, resolver, decls, drops);
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

	// Whether lowering a value of this type stages bytes into linear memory -- a string
	// or
	// a list<u8> ANYWHERE inside it, including as a variant case's payload or a record
	// field (miss one of those and the canon lower would carry no memory options, so the
	// staged pointer would have nothing to point into).
	private static boolean stagesMemory(WitType type, WitCanonicalAbi abi) {
		return switch (type) {
			case WitType.Prim prim -> "string".equals(prim.name());
			case WitType.ListOf ignored -> true;
			case WitType.OptionOf opt -> stagesMemory(opt.element(), abi);
			case WitType.ResultOf res -> (res.ok() != null && stagesMemory(res.ok(), abi))
					|| (res.err() != null && stagesMemory(res.err(), abi));
			case WitType.TupleOf tuple -> tuple.elements().stream().anyMatch(element -> stagesMemory(element, abi));
			case WitType.Named named -> {
				// A named type's fields / cases are written in the interface that DEFINES
				// it,
				// so the walk continues there -- not in the interface that merely uses
				// it.
				WitCanonicalAbi in = abi.scopeOf(named);
				yield switch (abi.resolveNamed(named)) {
					case WitItem.TypeAlias alias -> stagesMemory(alias.target(), in);
					case WitItem.RecordDef record ->
						record.fields().stream().anyMatch(field -> stagesMemory(field.type(), in));
					case WitItem.VariantDef variant ->
						variant.cases().stream().anyMatch(c -> c.payload() != null && stagesMemory(c.payload(), in));
					default -> false;
				};
			}
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
		int numParams = lispArity(decl);
		// How much scratch a wrapper needs is a property of how deeply its parameter
		// types
		// NEST -- `wasi:http`'s `response-outparam.set` reaches through result -> variant
		// ->
		// option -> record -> option<string> -- so no fixed pool size is defensible: a
		// deeper WIT walks past any constant. Emit the body ONCE into a throwaway stream
		// to
		// measure the high-water marks, then emit it again with pools of exactly that
		// size.
		// (The locals declaration is written after the body either way; what the first
		// pass
		// buys is the local INDICES, which must already be right while the body is
		// written.)
		Body probe = emitBody(ctxBuilder, decl, numParams, ordinal, allocFuncIndex, strFromMemFuncIndex, MAX_SCRATCH,
				MAX_SCRATCH);
		Body body = emitBody(ctxBuilder, decl, numParams, ordinal, allocFuncIndex, strFromMemFuncIndex, probe.i32Pool(),
				probe.i64Pool());
		ByteArrayOutputStream entry = new ByteArrayOutputStream();
		am.ik.wasm.WasmWriter entryWriter = new am.ik.wasm.WasmWriter(entry);
		entryWriter.write(body.eqTemps() > 0 ? 3 : 2);
		entryWriter.writeUnsignedLeb128(body.i32Pool());
		entryWriter.write(Type.I32);
		entryWriter.writeUnsignedLeb128(body.i64Pool());
		entryWriter.write(Type.I64);
		if (body.eqTemps() > 0) {
			entryWriter.writeUnsignedLeb128(body.eqTemps());
			entryWriter.write(Type.REFNULL.code());
			entryWriter.writeHeapType(Type.EQ.code());
		}
		entryWriter.write((Object) body.bytes());
		return entry.toByteArray();
	}

	/**
	 * Builds the code entry of a resource {@code drop} wrapper: unbox the i31 handle to
	 * an {@code i32}, hand it to the host, return nil. The simplest wrapper there is --
	 * no memory, no staging, no lift -- and it needs no locals beyond its one parameter.
	 * @param ctxBuilder the shared context builder
	 * @param drop the bound drop
	 * @param ordinal the import's ordinal (shared with the function imports)
	 * @return the code entry bytes
	 */
	static byte[] buildDropBody(WasmLispCompiler.Ctx.Builder ctxBuilder, Drop drop, int ordinal) {
		ByteArrayOutputStream entry = new ByteArrayOutputStream();
		am.ik.wasm.WasmWriter writer = new am.ik.wasm.WasmWriter(entry);
		writer.write(0); // no local groups
		WasmLispCompiler.Ctx ctx = ctxBuilder.writer(writer).bodyStream(entry).build();
		// Local 1, not 0: every compiled function carries an implicit closure environment
		// in
		// slot 0 and starts its parameters at 1. Reading slot 0 here casts the null env
		// to
		// an i31 and traps.
		writer.write(Instruction.GET_LOCAL);
		writer.writeSignedLeb128(1);
		WasmEmitHelper.castI31GetS(ctx);
		writer.write(Instruction.CALL);
		writer.writeUnsignedLeb128(WasmImportCompiler.PLACEHOLDER_FUNC_BASE + ordinal);
		writer.write(Instruction.REF_NULL);
		writer.writeHeapType(Type.EQ.code());
		writer.write(Instruction.END);
		return entry.toByteArray();
	}

	/** Returns the WASM parameter types of a drop's core signature: the i32 handle. */
	static Type[] dropParamTypes() {
		return new Type[] { Type.I32 };
	}

	// One emission of a wrapper body, and what it cost in locals.
	private record Body(byte[] bytes, int i32Pool, int i64Pool, int eqTemps) {
	}

	// Emits the body with scratch pools of the given sizes, and reports the sizes it
	// actually used. Re-emitting is safe and repeatable: the only state it shares with
	// the
	// caller is the string table, whose entries are content-keyed (adding the same
	// literal
	// twice yields the same offset).
	private static Body emitBody(WasmLispCompiler.Ctx.Builder ctxBuilder, Decl decl, int numParams, int ordinal,
			int allocFuncIndex, int strFromMemFuncIndex, int i32Pool, int i64Pool) {
		ByteArrayOutputStream bodyStream = new ByteArrayOutputStream();
		am.ik.wasm.WasmWriter writer = new am.ik.wasm.WasmWriter(bodyStream);
		WasmLispCompiler.Ctx ctx = ctxBuilder.writer(writer).bodyStream(bodyStream).build();
		Gen gen = new Gen(ctx, decl, numParams, ordinal, allocFuncIndex, strFromMemFuncIndex, i32Pool, i64Pool);
		gen.emitBody();
		// Locals: the i32 scratch pool, the i64 scratch pool, then the eq temps allocTemp
		// handed out during emission.
		int eqTemps = ctx.nextLocal - (numParams + 1 + i32Pool + i64Pool);
		return new Body(bodyStream.toByteArray(), gen.i32High, gen.i64High, eqTemps);
	}

	// The per-body generator: tracks the i32 scratch cursor (stack discipline around
	// recursion) and emits the lower/call/lift sequence.
	private static final class Gen {

		private final WasmLispCompiler.Ctx ctx;

		private final am.ik.wasm.WasmWriter w;

		private final Decl decl;

		private final int i32Base;

		private final int i64Base;

		private final int i64Local;

		private final int i32Pool;

		private final int i64Pool;

		private final int allocFuncIndex;

		private final int strFromMemFuncIndex;

		private final int ordinal;

		private int i32Cursor;

		private int i64Cursor;

		// The pool sizes this body actually needs: the high-water marks of the cursors.
		private int i32High;

		private int i64High;

		private final int mark;

		Gen(WasmLispCompiler.Ctx ctx, Decl decl, int numParams, int ordinal, int allocFuncIndex,
				int strFromMemFuncIndex, int i32Pool, int i64Pool) {
			this.ctx = ctx;
			this.w = ctx.writer;
			this.decl = decl;
			this.i32Pool = i32Pool;
			this.i64Pool = i64Pool;
			this.i32Base = numParams + 1;
			this.i64Base = numParams + 1 + i32Pool;
			this.i64Local = this.i64Base; // slot 0 of the pool: boxI64's permanent
											// scratch
			this.i64Cursor = 1;
			this.i64High = 1;
			this.ordinal = ordinal;
			this.allocFuncIndex = allocFuncIndex;
			this.strFromMemFuncIndex = strFromMemFuncIndex;
			ctx.nextLocal = numParams + 1 + i32Pool + i64Pool;
			this.mark = allocI32();
		}

		private int allocI32() {
			if (this.i32Cursor >= this.i32Pool) {
				throw new UnsupportedOperationException("'" + this.decl.lispName()
						+ "': the WIT parameter and result types nest deeper than the component import boundary can "
						+ "marshal (more than " + MAX_SCRATCH + " scratch locals)");
			}
			int slot = this.i32Base + this.i32Cursor++;
			this.i32High = Math.max(this.i32High, this.i32Cursor);
			return slot;
		}

		private int allocI64() {
			if (this.i64Cursor >= this.i64Pool) {
				throw new UnsupportedOperationException("'" + this.decl.lispName()
						+ "': the WIT parameter and result types nest deeper than the component import boundary can "
						+ "marshal (more than " + MAX_SCRATCH + " scratch locals)");
			}
			int slot = this.i64Base + this.i64Cursor++;
			this.i64High = Math.max(this.i64High, this.i64Cursor);
			return slot;
		}

		// The scratch a lowered ARGUMENT used dies the moment its flats are on the stack,
		// so the next argument starts from the floor again (slot 0 of each pool is
		// permanent: the staging mark, and boxI64's scratch). Without this, N arguments
		// would cost the SUM of their scratch rather than the deepest one's.
		private void resetScratch() {
			this.i32Cursor = 1;
			this.i64Cursor = 1;
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
				emitLowerParam(this.decl.abi(), param.type(), slot++);
				resetScratch();
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
				emitLiftAt(this.decl.abi(), result, rp, 0);
			}
			else {
				emitLiftFlat(this.decl.abi(), result);
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
		private void emitLowerParam(WitCanonicalAbi outer, WitType type, int slot) {
			WitCanonicalAbi.Scoped scoped = outer.resolveAliases(type);
			WitCanonicalAbi abi = scoped.abi();
			WitType t = scoped.type();
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
						case "char" -> {
							getLocal(slot);
							structGet(WasmLispCompiler.TYPE_CHAR, 0);
						}
						case "string" -> emitStageStringParam(slot);
						default -> throw paramUnsupported(prim.name());
					}
				}
				case WitType.ListOf list when abi.isU8(list.element()) -> emitStageStringParam(slot);
				case WitType.ListOf ignored -> throw paramUnsupported("list<T>");
				case WitType.BorrowOf ignored -> emitLowerHandleParam(slot);
				case WitType.OwnOf ignored -> emitLowerHandleParam(slot);
				case WitType.OptionOf ignored -> emitLowerVariantParam(abi, t, slot);
				case WitType.ResultOf ignored -> emitLowerVariantParam(abi, t, slot);
				case WitType.TupleOf ignored -> emitLowerRecordParam(abi, t, slot, false);
				case WitType.Named named -> {
					switch (abi.resolveNamed(named)) {
						case WitItem.ResourceDef ignored -> emitLowerHandleParam(slot);
						case WitItem.EnumDef ignored -> emitLowerVariantParam(abi, t, slot);
						case WitItem.VariantDef ignored -> emitLowerVariantParam(abi, t, slot);
						case WitItem.RecordDef ignored -> emitLowerRecordParam(abi, t, slot, true);
						default -> throw paramUnsupported(named.name());
					}
				}
				default -> throw paramUnsupported(t.getClass().getSimpleName());
			}
		}

		private void emitLowerHandleParam(int slot) {
			getLocal(slot);
			WasmEmitHelper.castI31GetS(this.ctx);
		}

		// A variant-shaped parameter (variant / enum / option / result): flats are
		// [disc i32] + the JOINED payload flats (one slot per position, widened across
		// the
		// cases -- WitCanonicalAbi.flatTypes). Every branch computes the discriminant and
		// the joined flats into scratch locals and leaves the stack untouched, so the
		// values can be pushed in flat order afterwards.
		//
		// The Lisp shape is the settled mapping, and it is EXACTLY what the lifting side
		// builds, so a lifted value can be passed straight back: an option is
		// nil-or-value;
		// anything else is the case keyword (`:get`), or a `(keyword . payload)` cons
		// when
		// the case carries one -- for a result, the (:ok . V) / (:error . E) envelope.
		private void emitLowerVariantParam(WitCanonicalAbi abi, WitType type, int slot) {
			WitCanonicalAbi.VariantInfo info = abi.variantInfo(type);
			List<Type> flats = abi.flatTypes(type);
			List<Type> joined = flats.subList(1, flats.size());
			int disc = allocI32();
			List<Integer> payloadLocals = new ArrayList<>();
			for (Type flat : joined) {
				payloadLocals.add(storageOf(flat) == Type.I64 ? allocI64() : allocI32());
			}
			if (abi.resolveAliases(type).type() instanceof WitType.OptionOf opt) {
				// none = nil, some(v) = the value itself: no tag to read.
				getLocal(slot);
				this.w.write(Instruction.REF_IS_NULL);
				this.w.write(Instruction.IF, 0x40);
				i32Const(0);
				setLocal(disc);
				emitCasePayload(info.abi(), null, -1, joined, payloadLocals);
				this.w.write(Instruction.ELSE);
				i32Const(1);
				setLocal(disc);
				emitCasePayload(info.abi(), opt.element(), slot, joined, payloadLocals);
				this.w.write(Instruction.END);
			}
			else {
				int tagId = emitTagId(slot);
				int payload = joined.isEmpty() ? -1 : emitPayload(slot);
				int n = info.names().size();
				for (int i = 0; i < n; i++) {
					getLocal(tagId);
					i32Const(this.ctx.stringTable.addString(":" + info.names().get(i)).offset());
					this.w.write(Instruction.I32_EQ);
					this.w.write(Instruction.IF, 0x40);
					i32Const(i);
					setLocal(disc);
					emitCasePayload(info.abi(), info.payloads().get(i), payload, joined, payloadLocals);
					this.w.write(Instruction.ELSE);
				}
				// The argument names no case of this variant: a type error, and it traps
				// exactly as every other type error does on this backend (a ref.cast on a
				// value of the wrong shape).
				this.w.write(Instruction.UNREACHABLE);
				for (int i = 0; i < n; i++) {
					this.w.write(Instruction.END);
				}
			}
			getLocal(disc);
			for (int i = 0; i < joined.size(); i++) {
				getLocal(payloadLocals.get(i));
				if (joined.get(i) == Type.F32) {
					this.w.write(Instruction.F32_REINTERPRET_I32);
				}
				else if (joined.get(i) == Type.F64) {
					this.w.write(Instruction.F64_REINTERPRET_I64);
				}
			}
		}

		// One case of a lowered variant: compute the payload's flats into the joined
		// locals (coerced to the joined type, the canonical ABI's rule), and zero the
		// joined positions this case does not reach. The scratch cursors are rolled back
		// afterwards: the cases are mutually exclusive, so each may reuse the same
		// locals.
		private void emitCasePayload(WitCanonicalAbi abi, @Nullable WitType payload, int payloadSlot, List<Type> joined,
				List<Integer> payloadLocals) {
			int save32 = this.i32Cursor;
			int save64 = this.i64Cursor;
			int lowered = 0;
			// A payload can FLATTEN to nothing (an empty record, a tuple of none), in
			// which
			// case there is nothing to lower and nothing to read the payload from -- the
			// case is payload-less as far as the ABI is concerned.
			if (payload != null && !abi.flatTypes(payload).isEmpty()) {
				if (payloadSlot < 0) {
					throw new IllegalStateException("a payload-bearing case has no payload value");
				}
				List<Type> payloadFlats = abi.flatTypes(payload);
				emitLowerParam(abi, payload, payloadSlot);
				// The flats are on the stack in order, so they pop in reverse.
				for (int i = payloadFlats.size() - 1; i >= 0; i--) {
					emitCoerceToStorage(payloadFlats.get(i), joined.get(i));
					setLocal(payloadLocals.get(i));
				}
				lowered = payloadFlats.size();
			}
			for (int i = lowered; i < joined.size(); i++) {
				if (storageOf(joined.get(i)) == Type.I64) {
					this.w.write(Instruction.I64_CONST);
					this.w.writeSignedLeb128(0);
				}
				else {
					i32Const(0);
				}
				setLocal(payloadLocals.get(i));
			}
			this.i32Cursor = save32;
			this.i64Cursor = save64;
		}

		// The core type a joined flat is held in between the branches and the call: a
		// float
		// flat rides in an integer local as its bit pattern, which keeps the scratch
		// pools
		// to two (and costs nothing -- the reinterpret pair is free).
		private static Type storageOf(Type flat) {
			return flat == Type.I64 || flat == Type.F64 ? Type.I64 : Type.I32;
		}

		// Converts the flat value on the stack (of a case's own flat type) to the joined
		// flat type, then to that type's storage type. The widening pairs are the
		// canonical
		// ABI's, and nothing else can occur: the join only ever widens i32 -> i64 and
		// reinterprets a float into the integer that covers it.
		private void emitCoerceToStorage(Type have, Type want) {
			if (have == want) {
				if (want == Type.F32) {
					this.w.write(Instruction.I32_REINTERPRET_F32);
				}
				else if (want == Type.F64) {
					this.w.write(Instruction.I64_REINTERPRET_F64);
				}
				return;
			}
			if (have == Type.F32 && want == Type.I32) {
				this.w.write(Instruction.I32_REINTERPRET_F32);
				return;
			}
			if (have == Type.I32 && want == Type.I64) {
				this.w.write(Instruction.I64_EXTEND_U_I32);
				return;
			}
			if (have == Type.F32 && want == Type.I64) {
				this.w.write(Instruction.I32_REINTERPRET_F32);
				this.w.write(Instruction.I64_EXTEND_U_I32);
				return;
			}
			if (have == Type.F64 && want == Type.I64) {
				this.w.write(Instruction.I64_REINTERPRET_F64);
				return;
			}
			throw new IllegalStateException(
					"'" + this.decl.lispName() + "': a variant case's flat " + have + " does not join into " + want);
		}

		// tagId = the interned string id of the value's tag -- car when it is a
		// (keyword . payload) cons, the value itself otherwise -- or -1 when the tag is
		// not
		// a symbol at all (so no case can match it and the dispatch traps).
		private int emitTagId(int slot) {
			int tag = this.ctx.allocTemp();
			getLocal(slot);
			refTest(WasmLispCompiler.TYPE_CONS);
			this.w.write(Instruction.IF);
			this.w.write(Type.REFNULL.code());
			this.w.writeHeapType(Type.EQ.code());
			getLocal(slot);
			structGet(WasmLispCompiler.TYPE_CONS, 0);
			this.w.write(Instruction.ELSE);
			getLocal(slot);
			this.w.write(Instruction.END);
			setLocal(tag);
			int tagId = allocI32();
			getLocal(tag);
			refTest(WasmLispCompiler.TYPE_STRING);
			this.w.write(Instruction.IF, Type.I32);
			getLocal(tag);
			structGet(WasmLispCompiler.TYPE_STRING, 0);
			this.w.write(Instruction.ELSE);
			i32Const(-1);
			this.w.write(Instruction.END);
			setLocal(tagId);
			return tagId;
		}

		// The payload of a tagged value: cdr when it is a cons, nil otherwise (a
		// payload-less case may be written as the bare keyword).
		private int emitPayload(int slot) {
			int payload = this.ctx.allocTemp();
			getLocal(slot);
			refTest(WasmLispCompiler.TYPE_CONS);
			this.w.write(Instruction.IF);
			this.w.write(Type.REFNULL.code());
			this.w.writeHeapType(Type.EQ.code());
			getLocal(slot);
			structGet(WasmLispCompiler.TYPE_CONS, 1);
			this.w.write(Instruction.ELSE);
			refNullEq();
			this.w.write(Instruction.END);
			setLocal(payload);
			return payload;
		}

		// A record parameter (a keyword plist) or a tuple parameter (a positional list):
		// the fields' flats concatenate, in WIT order.
		private void emitLowerRecordParam(WitCanonicalAbi abi, WitType type, int slot, boolean plist) {
			WitCanonicalAbi.RecordInfo info = abi.recordInfo(type);
			for (int i = 0; i < info.names().size(); i++) {
				int save32 = this.i32Cursor;
				int save64 = this.i64Cursor;
				int field = this.ctx.allocTemp();
				getLocal(slot);
				if (plist) {
					// _plist_get(plist, ":field") -- nil when the key is absent, which is
					// what an option field wants.
					i32Const(this.ctx.stringTable.addString(":" + info.names().get(i)).offset());
					this.w.write(Instruction.CALL);
					this.w.writeSignedLeb128(WasmLispCompiler.FUNC_FETCH_PLIST_GET);
				}
				else {
					for (int k = 0; k < i; k++) {
						structGet(WasmLispCompiler.TYPE_CONS, 1);
					}
					structGet(WasmLispCompiler.TYPE_CONS, 0);
				}
				setLocal(field);
				emitLowerParam(info.abi(), info.types().get(i), field);
				this.i32Cursor = save32;
				this.i64Cursor = save64;
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
		private void emitLiftFlat(WitCanonicalAbi outer, WitType type) {
			WitCanonicalAbi.Scoped scoped = outer.resolveAliases(type);
			WitCanonicalAbi abi = scoped.abi();
			WitType t = scoped.type();
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
				case WitType.ResultOf ignored -> emitLiftVariantFromStackDisc(abi, t);
				case WitType.OptionOf ignored -> emitLiftVariantFromStackDisc(abi, t);
				case WitType.TupleOf ignored -> emitLiftRecordFlat(abi, t, false);
				case WitType.Named named -> {
					switch (abi.resolveNamed(named)) {
						case WitItem.ResourceDef ignored -> boxI31();
						case WitItem.EnumDef ignored -> emitLiftVariantFromStackDisc(abi, t);
						case WitItem.VariantDef ignored -> emitLiftVariantFromStackDisc(abi, t);
						case WitItem.RecordDef ignored -> emitLiftRecordFlat(abi, t, true);
						default -> throw resultUnsupported(named.name());
					}
				}
				default -> throw resultUnsupported(t.getClass().getSimpleName());
			}
		}

		// A record / tuple result that flattens to ONE core value (a single-field record,
		// say) never reaches the return area: the value comes back in the flat, so there
		// is
		// no memory to read the fields out of. It still has to lift to a plist / a list
		// --
		// the shape does not change with how the ABI happened to carry it.
		private void emitLiftRecordFlat(WitCanonicalAbi abi, WitType type, boolean plist) {
			WitCanonicalAbi.RecordInfo info = abi.recordInfo(type);
			int carrier = -1;
			for (int i = 0; i < info.types().size(); i++) {
				if (!info.abi().flatTypes(info.types().get(i)).isEmpty()) {
					carrier = i; // at most one, or the record would not be single-flat
				}
			}
			int value = -1;
			if (carrier >= 0) {
				emitLiftFlat(info.abi(), info.types().get(carrier));
				value = this.ctx.allocTemp();
				setLocal(value);
			}
			int conses = 0;
			for (int i = 0; i < info.names().size(); i++) {
				if (plist) {
					WasmEmitHelper.compileStringLiteral(":" + info.names().get(i), this.ctx);
					conses++;
				}
				if (i == carrier) {
					getLocal(value);
				}
				else {
					// A field that flattens to nothing is an empty record / tuple: nil.
					refNullEq();
				}
				conses++;
			}
			refNullEq();
			for (int i = 0; i < conses; i++) {
				newCons();
			}
		}

		// A single-flat variant-shaped result (every arm payload-less): the flat i32 IS
		// the discriminant.
		private void emitLiftVariantFromStackDisc(WitCanonicalAbi abi, WitType type) {
			int disc = allocI32();
			setLocal(disc);
			emitVariantDispatch(abi, type, disc, -1, 0);
		}

		// Lifts the canonical memory representation of `type` at [base + offset] into a
		// boxed value.
		private void emitLiftAt(WitCanonicalAbi outer, WitType type, int base, int offset) {
			WitCanonicalAbi.Scoped scoped = outer.resolveAliases(type);
			WitCanonicalAbi abi = scoped.abi();
			WitType t = scoped.type();
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
				case WitType.ListOf list when abi.isU8(list.element()) -> emitLiftString(base, offset);
				case WitType.ListOf list -> emitLiftList(abi, list.element(), base, offset);
				case WitType.BorrowOf ignored -> {
					load(base, offset, Instruction.I32_LOAD, 2);
					boxI31();
				}
				case WitType.OwnOf ignored -> {
					load(base, offset, Instruction.I32_LOAD, 2);
					boxI31();
				}
				case WitType.OptionOf ignored -> emitLiftVariantAt(abi, t, base, offset);
				case WitType.ResultOf ignored -> emitLiftVariantAt(abi, t, base, offset);
				case WitType.TupleOf ignored -> emitLiftRecordAt(abi, t, base, offset, false);
				case WitType.Named named -> {
					switch (abi.resolveNamed(named)) {
						case WitItem.ResourceDef ignored -> {
							load(base, offset, Instruction.I32_LOAD, 2);
							boxI31();
						}
						case WitItem.RecordDef ignored -> emitLiftRecordAt(abi, t, base, offset, true);
						case WitItem.VariantDef ignored -> emitLiftVariantAt(abi, t, base, offset);
						case WitItem.EnumDef ignored -> emitLiftVariantAt(abi, t, base, offset);
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
		private void emitLiftVariantAt(WitCanonicalAbi abi, WitType type, int base, int offset) {
			WitCanonicalAbi.VariantInfo info = abi.variantInfo(type);
			int disc = allocI32();
			int discOp = switch (info.discSize()) {
				case 1 -> Instruction.I32_LOAD8_U;
				case 2 -> Instruction.I32_LOAD16_U;
				default -> Instruction.I32_LOAD;
			};
			load(base, offset, discOp, info.discSize() == 1 ? 0 : info.discSize() == 2 ? 1 : 2);
			setLocal(disc);
			emitVariantDispatch(abi, type, disc, base, offset + info.payloadOffset());
		}

		// Emits the if-chain over a variant's discriminant local. payloadBase = -1 means
		// no memory payload exists (the single-flat, all-arms-payload-less shape).
		private void emitVariantDispatch(WitCanonicalAbi abi, WitType type, int discLocal, int payloadBase,
				int payloadOffset) {
			WitCanonicalAbi.VariantInfo info = abi.variantInfo(type);
			int n = info.names().size();
			for (int i = 0; i < n - 1; i++) {
				getLocal(discLocal);
				i32Const(i);
				this.w.write(Instruction.I32_EQ);
				this.w.write(Instruction.IF);
				this.w.write(Type.REFNULL.code());
				this.w.writeHeapType(Type.EQ.code());
				emitVariantCase(abi, type, info, i, payloadBase, payloadOffset);
				this.w.write(Instruction.ELSE);
			}
			emitVariantCase(abi, type, info, n - 1, payloadBase, payloadOffset);
			for (int i = 0; i < n - 1; i++) {
				this.w.write(Instruction.END);
			}
		}

		// One case's value: option -> nil / payload; result -> the (:ok . V) /
		// (:error . E) envelope; enum -> the case keyword; variant -> the keyword, or
		// (keyword . payload) when the case carries one.
		private void emitVariantCase(WitCanonicalAbi abi, WitType type, WitCanonicalAbi.VariantInfo info, int index,
				int payloadBase, int payloadOffset) {
			WitType payload = info.payloads().get(index);
			WitType resolved = abi.resolveAliases(type).type();
			if (resolved instanceof WitType.OptionOf) {
				if (index == 0) {
					refNullEq();
				}
				else {
					emitPayloadOrNil(info.abi(), payload, payloadBase, payloadOffset);
				}
				return;
			}
			if (resolved instanceof WitType.ResultOf) {
				WasmEmitHelper.compileStringLiteral(index == 0 ? ":ok" : ":error", this.ctx);
				emitPayloadOrNil(info.abi(), payload, payloadBase, payloadOffset);
				newCons();
				return;
			}
			// enum / variant: the case keyword, dotted with the payload when present.
			WasmEmitHelper.compileStringLiteral(":" + info.names().get(index), this.ctx);
			if (payload != null) {
				emitPayloadOrNil(info.abi(), payload, payloadBase, payloadOffset);
				newCons();
			}
		}

		private void emitPayloadOrNil(WitCanonicalAbi abi, @Nullable WitType payload, int payloadBase,
				int payloadOffset) {
			if (payload == null) {
				refNullEq();
			}
			else {
				if (payloadBase < 0) {
					throw new IllegalStateException("a payload-bearing case cannot be single-flat");
				}
				emitLiftAt(abi, payload, payloadBase, payloadOffset);
			}
		}

		// record -> (:field value ...) keyword plist; tuple -> (v0 v1 ...) proper list.
		private void emitLiftRecordAt(WitCanonicalAbi abi, WitType type, int base, int offset, boolean plist) {
			WitCanonicalAbi.RecordInfo info = abi.recordInfo(type);
			int conses = 0;
			for (int i = 0; i < info.names().size(); i++) {
				if (plist) {
					WasmEmitHelper.compileStringLiteral(":" + info.names().get(i), this.ctx);
					conses++;
				}
				emitLiftAt(info.abi(), info.types().get(i), base, offset + info.offsets().get(i));
				conses++;
			}
			refNullEq();
			for (int i = 0; i < conses; i++) {
				newCons();
			}
		}

		// list<T> at [base + offset]: (element base @+0, count @+4) -> a proper list,
		// built back to front so the accumulator is the cdr.
		private void emitLiftList(WitCanonicalAbi abi, WitType element, int base, int offset) {
			int save = this.i32Cursor;
			int elems = allocI32();
			int idx = allocI32();
			int elemBase = allocI32();
			int acc = this.ctx.allocTemp();
			int elemSize = abi.size(element);
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
			emitLiftAt(abi, element, elemBase, 0);
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

		// i32 = 1 when the (ref null eq) on the stack is of the given struct type.
		private void refTest(int typeIndex) {
			this.w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			this.w.writeHeapType(typeIndex);
		}

		// Casts the (ref null eq) on the stack to the struct type -- trapping when it is
		// something else, the house behavior of a type error on this backend -- and reads
		// one field.
		private void structGet(int typeIndex, int field) {
			this.w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			this.w.writeHeapType(typeIndex);
			this.w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
			this.w.writeSignedLeb128(typeIndex);
			this.w.writeSignedLeb128(field);
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

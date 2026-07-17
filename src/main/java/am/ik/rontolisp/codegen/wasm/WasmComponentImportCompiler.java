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

	/**
	 * How many bytes one {@code stream.read} call asks for. Each call stages its buffer
	 * above the heap mark and pops it once the bytes are on the GC heap, so the chunk
	 * size only caps a single blocking read; the Lisp-side loop accumulates.
	 */
	private static final int ASYNC_READ_CHUNK = 8192;

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

	/** The async built-in operations a stream/future type alias binds. */
	enum AsyncOp {

		NEW("new"), READ("read"), WRITE("write"), DROP_READABLE("drop-readable"), DROP_WRITABLE("drop-writable");

		final String suffix;

		AsyncOp(String suffix) {
			this.suffix = suffix;
		}

		static AsyncOp of(String suffix) {
			for (AsyncOp op : values()) {
				if (op.suffix.equals(suffix)) {
					return op;
				}
			}
			throw new IllegalStateException("Internal component-import form names an unknown async op: " + suffix);
		}

	}

	/**
	 * A bound async built-in: one {@code canon stream.*}/{@code future.*} operation on
	 * the stream/future type a {@code type} alias of the interface names.
	 * <p>
	 * Not a {@link Decl}: like a {@link Drop}, an async built-in is a CORE function with
	 * no component function alias and no {@code canon lower} behind it -- but unlike a
	 * drop it is typed by a <em>component-level</em> stream/future type that
	 * {@code WasmComponentBuilder} derives from the WIT, not by a resource projected out
	 * of the instance.
	 *
	 * @param lispName the Lisp-visible synthetic defun name ({@code h:body-stream-read})
	 * @param module the WASM import module = the interface's canonical id
	 * @param field the WASM import field = {@code "[async-read]body-stream"}
	 * @param alias the alias's name in the interface (the naming anchor)
	 * @param op the built-in operation
	 * @param abi the layout calculator scoped to the interface the alias's TARGET type is
	 * written in (payload references resolve there)
	 * @param type the resolved target: a {@link WitType.StreamOf} or
	 * {@link WitType.FutureOf}
	 */
	record Async(String lispName, String module, String field, String alias, AsyncOp op, WitCanonicalAbi abi,
			WitType type) {

		boolean stream() {
			return type() instanceof WitType.StreamOf;
		}

		/** The future's payload type ({@code null} for a stream). */
		@Nullable WitType payload() {
			return type() instanceof WitType.FutureOf fut ? fut.element() : null;
		}

	}

	/**
	 * A bound <strong>async func</strong> member ({@code client.send}): the call is
	 * async-lowered (the {@code async} canonical option), so it starts a subtask and
	 * returns the packed {@code (subtask << 4) | status} immediately; completion arrives
	 * as an {@code EVENT_SUBTASK}/RETURNED event on the task's waitable-set, driven by
	 * the scheduler. Two wrappers per member: the start wrapper lowers the arguments,
	 * allocates the return area and makes the call -- WITHOUT popping its staging, which
	 * must outlive the call -- returning the {@code (packed . retptr)} token cons; the
	 * lift wrapper (called by {@code _subtask_future} for an eagerly-completed call, or
	 * by the scheduler once the subtask reports RETURNED) lifts the result out of the
	 * return area, with no waiting of its own.
	 *
	 * @param startName the start wrapper's Lisp name ({@code pkg::%send-start})
	 * @param liftName the lift wrapper's Lisp name ({@code pkg::%send-lift})
	 * @param module the WASM import module = the interface's canonical id
	 * @param field the WASM import field = the canonical-ABI function name
	 * @param func the WIT function
	 * @param abi the layout calculator scoped to the interface
	 * @param sig the async-lowered flat core signature
	 */
	record AsyncCall(String startName, String liftName, String module, String field, WitResolver.Func func,
			WitCanonicalAbi abi, WitCanonicalAbi.FlatSig sig) {
	}

	/**
	 * A bound {@code task-return} built-in, derived from a non-stream/future type alias
	 * ({@code type handle-result = result<response, error-code>} binds
	 * {@code handle-result-task-return}): {@code canon task.return} typed by the alias's
	 * target -- how a stackful async EXPORT delivers its result mid-task and keeps
	 * running (the WASI 0.3 replacement for 0.2's {@code response-outparam.set}).
	 *
	 * @param lispName the Lisp-visible synthetic defun name
	 * @param module the WASM import module = the interface's canonical id
	 * @param field the WASM import field = {@code "[task-return]<alias>"}
	 * @param alias the alias's name in the interface
	 * @param abi the layout calculator scoped to the interface the target is written in
	 * @param type the resolved target type (the task's declared result type)
	 */
	record TaskReturn(String lispName, String module, String field, String alias, WitCanonicalAbi abi, WitType type) {
	}

	// The waitable-set built-ins an interface with async calls imports alongside them
	// (module = the interface's canonical id; the synthesized core instance exports
	// them). One set per interface, shared by all of its async calls.
	static final String FIELD_WAITABLE_SET_NEW = "[waitable-set-new]";

	static final String FIELD_WAITABLE_SET_WAIT = "[waitable-set-wait]";

	static final String FIELD_WAITABLE_SET_DROP = "[waitable-set-drop]";

	static final String FIELD_WAITABLE_JOIN = "[waitable-join]";

	static final String FIELD_SUBTASK_DROP = "[subtask-drop]";

	/** The waitable builtin fields, in emission order. */
	static final List<String> WAITABLE_FIELDS = List.of(FIELD_WAITABLE_SET_NEW, FIELD_WAITABLE_SET_WAIT,
			FIELD_WAITABLE_SET_DROP, FIELD_WAITABLE_JOIN, FIELD_SUBTASK_DROP);

	/** The core signature of a waitable builtin (host-verified on wasmtime 46). */
	static Type[] waitableParamTypes(String field) {
		return switch (field) {
			case FIELD_WAITABLE_SET_NEW -> new Type[0];
			case FIELD_WAITABLE_SET_WAIT, FIELD_WAITABLE_JOIN -> new Type[] { Type.I32, Type.I32 };
			case FIELD_WAITABLE_SET_DROP, FIELD_SUBTASK_DROP -> new Type[] { Type.I32 };
			default -> throw new IllegalStateException("not a waitable builtin field: " + field);
		};
	}

	/** The core result types of a waitable builtin. */
	static Type[] waitableResultTypes(String field) {
		return switch (field) {
			case FIELD_WAITABLE_SET_NEW, FIELD_WAITABLE_SET_WAIT -> new Type[] { Type.I32 };
			case FIELD_WAITABLE_SET_DROP, FIELD_WAITABLE_JOIN, FIELD_SUBTASK_DROP -> new Type[0];
			default -> throw new IllegalStateException("not a waitable builtin field: " + field);
		};
	}

	// The task/callback scheduler built-ins a module with a CALLBACK-lifted export
	// (serve's handle) imports under its own pseudo-module: the per-task context slot
	// (slot 0 = the task id; wasmtime 46 validates the context immediate to 0, so the
	// waitable-set handle lives in the task record instead of a second slot), the
	// intra-component u64 doorbell stream (the cross-task wakeup primitive) and its
	// own waitable-set new/join pair (independent of any WIT interface's trio, so a
	// module whose only interface binds no async calls can still run callback tasks).
	static final String SCHED_MODULE = "$sched";

	static final String FIELD_CONTEXT_GET_0 = "[context-get-0]";

	static final String FIELD_CONTEXT_SET_0 = "[context-set-0]";

	static final String FIELD_DOORBELL_NEW = "[doorbell-new]";

	static final String FIELD_DOORBELL_READ = "[doorbell-read]";

	static final String FIELD_DOORBELL_WRITE = "[doorbell-write]";

	static final String FIELD_SCHED_SET_NEW = "[sched-set-new]";

	static final String FIELD_SCHED_JOIN = "[sched-join]";

	/** The scheduler builtin fields, in emission order. */
	static final List<String> SCHED_FIELDS = List.of(FIELD_CONTEXT_GET_0, FIELD_CONTEXT_SET_0, FIELD_DOORBELL_NEW,
			FIELD_DOORBELL_READ, FIELD_DOORBELL_WRITE, FIELD_SCHED_SET_NEW, FIELD_SCHED_JOIN);

	/** The core signature of a scheduler builtin (host-verified on wasmtime 46). */
	static Type[] schedParamTypes(String field) {
		return switch (field) {
			case FIELD_CONTEXT_GET_0, FIELD_DOORBELL_NEW, FIELD_SCHED_SET_NEW -> new Type[0];
			case FIELD_CONTEXT_SET_0 -> new Type[] { Type.I32 };
			case FIELD_DOORBELL_READ, FIELD_DOORBELL_WRITE -> new Type[] { Type.I32, Type.I32, Type.I32 };
			case FIELD_SCHED_JOIN -> new Type[] { Type.I32, Type.I32 };
			default -> throw new IllegalStateException("not a scheduler builtin field: " + field);
		};
	}

	/** The core result types of a scheduler builtin. */
	static Type[] schedResultTypes(String field) {
		return switch (field) {
			case FIELD_CONTEXT_GET_0, FIELD_DOORBELL_READ, FIELD_DOORBELL_WRITE, FIELD_SCHED_SET_NEW ->
				new Type[] { Type.I32 };
			case FIELD_CONTEXT_SET_0, FIELD_SCHED_JOIN -> new Type[0];
			case FIELD_DOORBELL_NEW -> new Type[] { Type.I64 };
			default -> throw new IllegalStateException("not a scheduler builtin field: " + field);
		};
	}

	/**
	 * The waitable builtin ordinals an await wrapper calls, resolved by the caller from
	 * the shared import-slot table.
	 *
	 * @param setNew the {@code waitable-set.new} ordinal
	 * @param setWait the {@code waitable-set.wait} ordinal
	 * @param setDrop the {@code waitable-set.drop} ordinal
	 * @param join the {@code waitable.join} ordinal
	 * @param subtaskDrop the {@code subtask.drop} ordinal
	 */
	record WaitOrdinals(int setNew, int setWait, int setDrop, int join, int subtaskDrop) {
	}

	record Import(String ifaceId, WitItem.InterfaceDef iface, WitResolver resolver, List<Decl> decls, List<Drop> drops,
			List<Async> asyncs, List<AsyncCall> calls, List<TaskReturn> taskReturns) {
	}

	/**
	 * Merges imports that name the <strong>same interface</strong> into one,
	 * concatenating their bound functions and drops.
	 * <p>
	 * Two independently spliced libraries can bind the same interface: a serve+fetch
	 * program may carry two independently spliced libraries, and each lowers its own
	 * {@code rontolisp:wit-import "wasi:http/types@0.2.0"} (into a different
	 * {@code %}-package). The Lisp-callable wrappers must stay distinct -- both packages'
	 * defuns are referenced by their own source -- so their {@link Decl}s are kept as-is
	 * (a duplicate core import of the same host function is legal, and both bindings
	 * resolve to the one lowered component instance export). What must NOT be duplicated
	 * is the component-level instance: an interface can be imported only once. This merge
	 * produces that single import; the component wiring then deduplicates the bound
	 * functions by their canonical field name when it emits the one shared instance.
	 * <p>
	 * First-occurrence order is preserved, and an interface that appears once is returned
	 * unchanged, so a program with no overlapping imports is byte-identical.
	 * @param imports the imports in program order
	 * @return one import per interface, decls and drops concatenated in encounter order
	 */
	static List<Import> mergeByIface(List<Import> imports) {
		if (imports.size() < 2) {
			return imports;
		}
		final java.util.Map<String, Import> byId = new java.util.LinkedHashMap<>();
		for (Import imported : imports) {
			Import prev = byId.get(imported.ifaceId());
			if (prev == null) {
				byId.put(imported.ifaceId(), imported);
				continue;
			}
			final List<Decl> decls = new ArrayList<>(prev.decls());
			decls.addAll(imported.decls());
			final List<Drop> drops = new ArrayList<>(prev.drops());
			drops.addAll(imported.drops());
			final List<Async> asyncs = new ArrayList<>(prev.asyncs());
			asyncs.addAll(imported.asyncs());
			final List<AsyncCall> calls = new ArrayList<>(prev.calls());
			calls.addAll(imported.calls());
			final List<TaskReturn> taskReturns = new ArrayList<>(prev.taskReturns());
			taskReturns.addAll(imported.taskReturns());
			// The interface / resolver are the same WIT type across both bindings (the
			// same
			// interface id, parsed from the same WIT text), so either serves the
			// component-level wiring; each Decl keeps its own abi, which is all the
			// lowering
			// reads.
			byId.put(imported.ifaceId(), new Import(prev.ifaceId(), prev.iface(), prev.resolver(), decls, drops, asyncs,
					calls, taskReturns));
		}
		return new ArrayList<>(byId.values());
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
		List<Async> asyncs = new ArrayList<>();
		List<AsyncCall> calls = new ArrayList<>();
		List<TaskReturn> taskReturns = new ArrayList<>();
		for (int i = 3; i < items.size(); i++) {
			if (!(items.get(i) instanceof LispCons pair) || !(pair.cdr() instanceof LispCons rest)) {
				throw new UnsupportedOperationException(
						"Malformed internal component-import member: " + items.get(i).print());
			}
			// (:drop "bucket" "kv:bucket-drop") / (:async "body" "read" "h:body-read") --
			// the keyword head is what tells these apart from a ("member" "lisp-name")
			// function binding.
			if (pair.car() instanceof LispSymbol keyword && keyword.isKeyword()) {
				if (":async-call".equals(keyword.name())) {
					if (!(rest.car() instanceof LispString member) || !(rest.cdr() instanceof LispCons startCons)
							|| !(startCons.car() instanceof LispString startName)
							|| !(startCons.cdr() instanceof LispCons liftCons)
							|| !(liftCons.car() instanceof LispString liftName)) {
						throw new UnsupportedOperationException(
								"Malformed internal component-import member: " + items.get(i).print());
					}
					WitResolver.Func func = funcs.stream()
						.filter(f -> member.value().equals(WitImportDirective.memberName(f)))
						.findFirst()
						.orElseThrow(() -> new IllegalStateException(
								"Internal component-import form names an unknown member: " + member.value()));
					calls.add(new AsyncCall(startName.value(), liftName.value(), ifaceId.value(), cabiFieldName(func),
							func, abi, abi.flatSigAsyncLower(func)));
					continue;
				}
				if (":task-return".equals(keyword.name())) {
					if (!(rest.car() instanceof LispString alias) || !(rest.cdr() instanceof LispCons nameCons)
							|| !(nameCons.car() instanceof LispString trName)) {
						throw new UnsupportedOperationException(
								"Malformed internal component-import member: " + items.get(i).print());
					}
					WitCanonicalAbi.Scoped target = abi.resolveAliases(new WitType.Named(alias.value()));
					taskReturns.add(new TaskReturn(trName.value(), ifaceId.value(), "[task-return]" + alias.value(),
							alias.value(), target.abi(), target.type()));
					continue;
				}
				if (":async".equals(keyword.name())) {
					if (!(rest.car() instanceof LispString alias) || !(rest.cdr() instanceof LispCons opCons)
							|| !(opCons.car() instanceof LispString op) || !(opCons.cdr() instanceof LispCons nameCons)
							|| !(nameCons.car() instanceof LispString asyncName)) {
						throw new UnsupportedOperationException(
								"Malformed internal component-import member: " + items.get(i).print());
					}
					WitCanonicalAbi.Scoped target = abi.resolveAliases(new WitType.Named(alias.value()));
					if (!(target.type() instanceof WitType.StreamOf) && !(target.type() instanceof WitType.FutureOf)) {
						throw new IllegalStateException("Internal component-import form names an async alias that is "
								+ "neither a stream nor a future: " + alias.value());
					}
					AsyncOp asyncOp = AsyncOp.of(op.value());
					asyncs.add(new Async(asyncName.value(), ifaceId.value(),
							"[async-" + asyncOp.suffix + "]" + alias.value(), alias.value(), asyncOp, target.abi(),
							target.type()));
					continue;
				}
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
		return new Import(ifaceId.value(), iface, resolver, decls, drops, asyncs, calls, taskReturns);
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

	/** Returns the Lisp parameter count of an async call's start wrapper. */
	static int lispArity(AsyncCall call) {
		boolean method = call.func().resource() != null && call.func().def().kind() == WitItem.FuncKind.PLAIN;
		return (method ? 1 : 0) + call.func().def().func().params().size();
	}

	/** Returns the WASM parameter types of the async-lowered call's core signature. */
	static Type[] hostParamTypes(AsyncCall call) {
		return call.sig().params();
	}

	/** Returns the WASM result types of the async-lowered call's core signature. */
	static Type[] hostResultTypes(AsyncCall call) {
		return call.sig().results();
	}

	/** Returns the flat parameter types of a task-return built-in's core signature. */
	static Type[] taskReturnParamTypes(TaskReturn tr) {
		return tr.abi().flatTypes(tr.type()).toArray(new Type[0]);
	}

	/**
	 * Whether an async call's lowering stages bytes into linear memory beyond the return
	 * area (a string / {@code list<u8>} parameter). The async {@code canon lower} always
	 * carries the memory options (the return pointer alone needs them), so this is
	 * informational only.
	 * @param call the bound async call
	 * @return {@code true} when a parameter stages memory
	 */
	static boolean paramsStageMemory(AsyncCall call) {
		for (var param : call.func().def().func().params()) {
			if (stagesMemory(param.type(), call.abi())) {
				return true;
			}
		}
		return false;
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
		return wrapEntry(body);
	}

	// The code-entry framing shared by every wrapper kind: the i32 scratch pool, the i64
	// scratch pool, then the eq temps handed out during emission.
	private static byte[] wrapEntry(Body body) {
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

	/**
	 * The WASM parameter types of an async built-in's core signature, per the canonical
	 * ABI's synchronous stream/future built-ins (the shapes the fixed base adapter
	 * already exercises): {@code new: [] -> [i64]} (the packed readable/writable pair),
	 * {@code stream.read/write: [end, ptr, len] -> [status]},
	 * {@code future.read/write: [end, ptr] -> [status]}, {@code drop-*: [end] -> []}.
	 * @param async the bound built-in
	 * @return the flat parameter types
	 */
	static Type[] asyncParamTypes(Async async) {
		return switch (async.op()) {
			case NEW -> new Type[0];
			case READ, WRITE ->
				async.stream() ? new Type[] { Type.I32, Type.I32, Type.I32 } : new Type[] { Type.I32, Type.I32 };
			case DROP_READABLE, DROP_WRITABLE -> new Type[] { Type.I32 };
		};
	}

	/** Returns the WASM result types of an async built-in's core signature. */
	static Type[] asyncResultTypes(Async async) {
		return switch (async.op()) {
			case NEW -> new Type[] { Type.I64 };
			case READ, WRITE -> new Type[] { Type.I32 };
			case DROP_READABLE, DROP_WRITABLE -> new Type[0];
		};
	}

	/** Returns the Lisp parameter count of an async built-in's synthetic defun. */
	static int lispArity(Async async) {
		return switch (async.op()) {
			case NEW -> 0;
			case WRITE -> 2;
			case READ, DROP_READABLE, DROP_WRITABLE -> 1;
		};
	}

	/**
	 * Whether a {@code future.read} of this built-in's type needs the canonical realloc
	 * option: the host stages a string/{@code list<u8>} payload through it (the fixed
	 * adapter's filesystem future precedent).
	 * @param async the bound built-in
	 * @return {@code true} when the read's {@code canon} needs realloc
	 */
	static boolean asyncReadNeedsRealloc(Async async) {
		WitType payload = async.payload();
		return payload != null && stagesMemory(payload, async.abi());
	}

	/**
	 * Builds the code entry of an async built-in wrapper. Like {@link #buildWrapperBody}
	 * it emits twice to size the scratch pools exactly.
	 * @param ctxBuilder the shared context builder
	 * @param async the bound built-in
	 * @param ordinal the import's ordinal (shared with the function imports)
	 * @param allocFuncIndex the function index of {@code __ronto_alloc}
	 * @param strFromMemFuncIndex the function index of {@code _str_from_mem}
	 * @param sched the module's scheduler wiring, or {@code null} outside asyncMode --
	 * with it, a {@code stream.read} the host reports BLOCKED returns a pending
	 * {@code TYPE_FUTURE} registered with the scheduler instead of parking the whole task
	 * on a blocking wait
	 * @return the code entry bytes
	 */
	static byte[] buildAsyncBody(WasmLispCompiler.Ctx.Builder ctxBuilder, Async async, int ordinal,
			WaitOrdinals waitOrdinals, int allocFuncIndex, int strFromMemFuncIndex,
			WasmFutureRuntimeBuilder.@Nullable Sched sched) {
		int numParams = lispArity(async);
		Body probe = emitAsync(ctxBuilder, async, numParams, ordinal, waitOrdinals, allocFuncIndex, strFromMemFuncIndex,
				sched, MAX_SCRATCH, MAX_SCRATCH);
		Body body = emitAsync(ctxBuilder, async, numParams, ordinal, waitOrdinals, allocFuncIndex, strFromMemFuncIndex,
				sched, probe.i32Pool(), probe.i64Pool());
		return wrapEntry(body);
	}

	// The async-lowered call and waitable-set constants, HOST-VERIFIED on wasmtime 46
	// (2026-07-16, hand-written canonical-ABI reference served + curl-checked): the
	// async canon lower returns (subtask << 4) | status with the status in the LOW 4
	// bits; waitable-set.wait writes (waitable index, state) at the payload pointer.
	// Shared with the scheduler runtime (WasmFutureRuntimeBuilder).
	static final int SUBTASK_STATUS_RETURNED = 2;

	static final int EVENT_SUBTASK = 1;

	/** The completion event of an async {@code stream.read} (spike-pinned ordering). */
	static final int EVENT_STREAM_READ = 2;

	static final int SUBTASK_STATE_RETURNED = 2;

	/**
	 * The packed callback code a callback-lifted export (or its callback) returns to keep
	 * the task alive: {@code 2 | (waitable-set << 4)} -- deliver the set's next event to
	 * the callback. {@code 0} (EXIT) ends the task.
	 */
	static final int CALLBACK_CODE_WAIT = 2;

	/**
	 * Builds the code entry of an async call's START wrapper: lower the arguments,
	 * allocate the return area, make the async-lowered call and hand back the
	 * {@code (packed . retptr)} token cons. The staging is deliberately NOT popped -- the
	 * lowered argument bytes and the return area must outlive the call until the await --
	 * so each start grows the bump heap by its staging (accepted; noted in the design).
	 * @param ctxBuilder the shared context builder
	 * @param call the bound async call
	 * @param ordinal the import's ordinal (shared with the function imports)
	 * @param allocFuncIndex the function index of {@code __ronto_alloc}
	 * @param strFromMemFuncIndex the function index of {@code _str_from_mem}
	 * @return the code entry bytes
	 */
	static byte[] buildAsyncStartBody(WasmLispCompiler.Ctx.Builder ctxBuilder, AsyncCall call, int ordinal,
			int allocFuncIndex, int strFromMemFuncIndex) {
		int numParams = lispArity(call);
		Body probe = emitAsyncStart(ctxBuilder, call, numParams, ordinal, allocFuncIndex, strFromMemFuncIndex,
				MAX_SCRATCH, MAX_SCRATCH);
		Body body = emitAsyncStart(ctxBuilder, call, numParams, ordinal, allocFuncIndex, strFromMemFuncIndex,
				probe.i32Pool(), probe.i64Pool());
		return wrapEntry(body);
	}

	private static Body emitAsyncStart(WasmLispCompiler.Ctx.Builder ctxBuilder, AsyncCall call, int numParams,
			int ordinal, int allocFuncIndex, int strFromMemFuncIndex, int i32Pool, int i64Pool) {
		ByteArrayOutputStream bodyStream = new ByteArrayOutputStream();
		am.ik.wasm.WasmWriter writer = new am.ik.wasm.WasmWriter(bodyStream);
		WasmLispCompiler.Ctx ctx = ctxBuilder.writer(writer).bodyStream(bodyStream).build();
		Gen gen = new Gen(ctx, call.startName(), numParams, ordinal, allocFuncIndex, strFromMemFuncIndex, i32Pool,
				i64Pool);
		gen.emitAsyncStartBody(call);
		int eqTemps = ctx.nextLocal - (numParams + 1 + i32Pool + i64Pool);
		return new Body(bodyStream.toByteArray(), gen.i32High, gen.i64High, eqTemps);
	}

	/**
	 * Builds the code entry of an async call's LIFT wrapper: parse the token cons and
	 * lift the result from the return area. No waiting happens here -- the wrapper is
	 * called by {@code _subtask_future} when the call completed eagerly, or by the
	 * scheduler once the subtask has reported RETURNED (which also releases the subtask).
	 * @param ctxBuilder the shared context builder
	 * @param call the bound async call
	 * @param allocFuncIndex the function index of {@code __ronto_alloc}
	 * @param strFromMemFuncIndex the function index of {@code _str_from_mem}
	 * @return the code entry bytes
	 */
	static byte[] buildAsyncLiftBody(WasmLispCompiler.Ctx.Builder ctxBuilder, AsyncCall call, int allocFuncIndex,
			int strFromMemFuncIndex) {
		Body probe = emitAsyncLift(ctxBuilder, call, allocFuncIndex, strFromMemFuncIndex, MAX_SCRATCH, MAX_SCRATCH);
		Body body = emitAsyncLift(ctxBuilder, call, allocFuncIndex, strFromMemFuncIndex, probe.i32Pool(),
				probe.i64Pool());
		return wrapEntry(body);
	}

	private static Body emitAsyncLift(WasmLispCompiler.Ctx.Builder ctxBuilder, AsyncCall call, int allocFuncIndex,
			int strFromMemFuncIndex, int i32Pool, int i64Pool) {
		ByteArrayOutputStream bodyStream = new ByteArrayOutputStream();
		am.ik.wasm.WasmWriter writer = new am.ik.wasm.WasmWriter(bodyStream);
		WasmLispCompiler.Ctx ctx = ctxBuilder.writer(writer).bodyStream(bodyStream).build();
		Gen gen = new Gen(ctx, call.liftName(), 1, -1, allocFuncIndex, strFromMemFuncIndex, i32Pool, i64Pool);
		gen.emitAsyncLiftBody(call);
		int eqTemps = ctx.nextLocal - (1 + 1 + i32Pool + i64Pool);
		return new Body(bodyStream.toByteArray(), gen.i32High, gen.i64High, eqTemps);
	}

	/**
	 * Builds the code entry of a task-return wrapper: lower the Lisp value to the
	 * declared result type's flat values and deliver them through
	 * {@code canon task.return}, returning nil.
	 * @param ctxBuilder the shared context builder
	 * @param tr the bound task-return
	 * @param ordinal the import's ordinal (shared with the function imports)
	 * @param allocFuncIndex the function index of {@code __ronto_alloc}
	 * @param strFromMemFuncIndex the function index of {@code _str_from_mem}
	 * @return the code entry bytes
	 */
	static byte[] buildTaskReturnBody(WasmLispCompiler.Ctx.Builder ctxBuilder, TaskReturn tr, int ordinal,
			int allocFuncIndex, int strFromMemFuncIndex) {
		Body probe = emitTaskReturn(ctxBuilder, tr, ordinal, allocFuncIndex, strFromMemFuncIndex, MAX_SCRATCH,
				MAX_SCRATCH);
		Body body = emitTaskReturn(ctxBuilder, tr, ordinal, allocFuncIndex, strFromMemFuncIndex, probe.i32Pool(),
				probe.i64Pool());
		return wrapEntry(body);
	}

	private static Body emitTaskReturn(WasmLispCompiler.Ctx.Builder ctxBuilder, TaskReturn tr, int ordinal,
			int allocFuncIndex, int strFromMemFuncIndex, int i32Pool, int i64Pool) {
		ByteArrayOutputStream bodyStream = new ByteArrayOutputStream();
		am.ik.wasm.WasmWriter writer = new am.ik.wasm.WasmWriter(bodyStream);
		WasmLispCompiler.Ctx ctx = ctxBuilder.writer(writer).bodyStream(bodyStream).build();
		Gen gen = new Gen(ctx, tr.lispName(), 1, ordinal, allocFuncIndex, strFromMemFuncIndex, i32Pool, i64Pool);
		gen.emitTaskReturnBody(tr);
		int eqTemps = ctx.nextLocal - (1 + 1 + i32Pool + i64Pool);
		return new Body(bodyStream.toByteArray(), gen.i32High, gen.i64High, eqTemps);
	}

	private static Body emitAsync(WasmLispCompiler.Ctx.Builder ctxBuilder, Async async, int numParams, int ordinal,
			WaitOrdinals waitOrdinals, int allocFuncIndex, int strFromMemFuncIndex,
			WasmFutureRuntimeBuilder.@Nullable Sched sched, int i32Pool, int i64Pool) {
		ByteArrayOutputStream bodyStream = new ByteArrayOutputStream();
		am.ik.wasm.WasmWriter writer = new am.ik.wasm.WasmWriter(bodyStream);
		WasmLispCompiler.Ctx ctx = ctxBuilder.writer(writer).bodyStream(bodyStream).build();
		Gen gen = new Gen(ctx, async.lispName(), numParams, ordinal, allocFuncIndex, strFromMemFuncIndex, i32Pool,
				i64Pool);
		gen.waitOrdinals = waitOrdinals;
		gen.sched = sched;
		gen.emitAsyncBody(async);
		int eqTemps = ctx.nextLocal - (numParams + 1 + i32Pool + i64Pool);
		return new Body(bodyStream.toByteArray(), gen.i32High, gen.i64High, eqTemps);
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
		Gen gen = new Gen(ctx, decl.lispName(), numParams, ordinal, allocFuncIndex, strFromMemFuncIndex, i32Pool,
				i64Pool);
		gen.emitBody(decl);
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

		private final String lispName;

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

		// The interface's waitable-set builtin ordinals, set for the async built-in
		// wrappers (the BLOCKED park needs them); null for plain function wrappers.
		private @Nullable WaitOrdinals waitOrdinals;

		// The module's scheduler wiring, set for the async built-in wrappers of an
		// asyncMode module (a BLOCKED stream.read becomes a pending future registered
		// there); null outside asyncMode, where the BLOCKED park stays.
		private WasmFutureRuntimeBuilder.@Nullable Sched sched;

		Gen(WasmLispCompiler.Ctx ctx, String lispName, int numParams, int ordinal, int allocFuncIndex,
				int strFromMemFuncIndex, int i32Pool, int i64Pool) {
			this.ctx = ctx;
			this.w = ctx.writer;
			this.lispName = lispName;
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
				throw new UnsupportedOperationException("'" + this.lispName
						+ "': the WIT parameter and result types nest deeper than the component import boundary can "
						+ "marshal (more than " + MAX_SCRATCH + " scratch locals)");
			}
			int slot = this.i32Base + this.i32Cursor++;
			this.i32High = Math.max(this.i32High, this.i32Cursor);
			return slot;
		}

		private int allocI64() {
			if (this.i64Cursor >= this.i64Pool) {
				throw new UnsupportedOperationException("'" + this.lispName
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

		void emitBody(Decl decl) {
			emitStagingPrologue();
			// Lower every Lisp argument onto the stack in flat order.
			WitResolver.Func func = decl.func();
			int slot = 1;
			if (func.resource() != null && func.def().kind() == WitItem.FuncKind.PLAIN) {
				getLocal(slot++);
				WasmEmitHelper.castI31GetS(this.ctx);
			}
			for (var param : func.def().func().params()) {
				emitLowerParam(decl.abi(), param.type(), slot++);
				resetScratch();
			}
			// The return pointer, when the results are indirect: bump-allocated above the
			// staged arguments (__ronto_alloc aligns to 8, satisfying every canonical
			// alignment), reclaimed by the epilogue's pop.
			int rp = -1;
			WitCanonicalAbi.FlatSig sig = decl.sig();
			if (sig.retptr()) {
				// TEE, not SET: the pointer stays on the stack as the call's trailing
				// return-pointer parameter.
				rp = allocI32();
				i32Const(sig.retSize());
				this.w.write(Instruction.CALL);
				this.w.writeSignedLeb128(this.allocFuncIndex);
				this.w.write(Instruction.TEE_LOCAL);
				this.w.writeSignedLeb128(rp);
			}
			callImport();
			// Lift the result into one boxed value.
			WitType result = decl.abi().resultType(func);
			if (result == null) {
				refNullEq();
			}
			else if (sig.retptr()) {
				emitLiftAt(decl.abi(), result, rp, 0);
			}
			else {
				emitLiftFlat(decl.abi(), result);
			}
			int resultTmp = this.ctx.allocTemp();
			setLocal(resultTmp);
			emitStagingEpilogue(resultTmp);
		}

		// --- the async-lowered call bodies ---

		// start: lower args, allocate the return area, async-lowered call, return the
		// (packed . retptr) token cons. NO staging epilogue: the staged bytes and the
		// return area must survive until the await.
		void emitAsyncStartBody(AsyncCall call) {
			emitStagingPrologue();
			WitResolver.Func func = call.func();
			int slot = 1;
			if (func.resource() != null && func.def().kind() == WitItem.FuncKind.PLAIN) {
				getLocal(slot++);
				WasmEmitHelper.castI31GetS(this.ctx);
			}
			for (var param : func.def().func().params()) {
				emitLowerParam(call.abi(), param.type(), slot++);
				resetScratch();
			}
			int rp = -1;
			WitCanonicalAbi.FlatSig sig = call.sig();
			if (sig.retptr()) {
				rp = allocI32();
				i32Const(sig.retSize());
				this.w.write(Instruction.CALL);
				this.w.writeSignedLeb128(this.allocFuncIndex);
				this.w.write(Instruction.TEE_LOCAL);
				this.w.writeSignedLeb128(rp);
			}
			callImport();
			// packed on the stack -> the token's car
			boxI31();
			if (rp >= 0) {
				getLocal(rp);
				boxI31();
			}
			else {
				refNullEq();
			}
			newCons();
			this.w.write(Instruction.END);
		}

		// lift: (packed . retptr) -> the lifted result. The subtask (when there was
		// one) has already reported RETURNED and been dropped by the scheduler; this
		// wrapper only reads the return area.
		void emitAsyncLiftBody(AsyncCall call) {
			emitStagingPrologue();
			int rp = allocI32();
			WitCanonicalAbi.FlatSig sig = call.sig();
			if (sig.retptr()) {
				getLocal(1);
				structGet(WasmLispCompiler.TYPE_CONS, 1);
				WasmEmitHelper.castI31GetS(this.ctx);
				setLocal(rp);
			}
			WitType result = call.abi().resultType(call.func());
			if (result == null || !sig.retptr()) {
				refNullEq();
			}
			else {
				emitLiftAt(call.abi(), result, rp, 0);
			}
			int resultTmp = this.ctx.allocTemp();
			setLocal(resultTmp);
			emitStagingEpilogue(resultTmp);
		}

		// task-return: lower the Lisp value to the declared result type's flats and
		// deliver them mid-task; the staged bytes are popped -- task.return is
		// synchronous, the host has copied by the time it returns.
		void emitTaskReturnBody(TaskReturn tr) {
			emitStagingPrologue();
			emitLowerParam(tr.abi(), tr.type(), 1);
			callImport();
			refNullEq();
			int resultTmp = this.ctx.allocTemp();
			setLocal(resultTmp);
			emitStagingEpilogue(resultTmp);
		}

		private void callOrdinal(int ordinal) {
			this.w.write(Instruction.CALL);
			this.w.writeUnsignedLeb128(WasmImportCompiler.PLACEHOLDER_FUNC_BASE + ordinal);
		}

		// --- the async built-in bodies ---

		void emitAsyncBody(Async async) {
			switch (async.op()) {
				case NEW -> emitAsyncNew();
				case DROP_READABLE, DROP_WRITABLE -> emitAsyncDrop();
				case READ -> {
					if (async.stream()) {
						emitStreamRead();
					}
					else {
						emitFutureRead(async);
					}
				}
				case WRITE -> {
					if (async.stream()) {
						emitStreamWrite();
					}
					else {
						emitFutureWrite(async);
					}
				}
			}
		}

		// new: [] -> [i64], low 32 bits = the readable end, high 32 = the writable end
		// (the fixed adapter's convention). Lifts to the cons (readable . writable).
		private void emitAsyncNew() {
			callImport();
			setLocal64();
			getLocal64();
			this.w.write(Instruction.I32_WRAP_I64);
			boxI31();
			getLocal64();
			this.w.write(Instruction.I64_CONST);
			this.w.writeSignedLeb128(32);
			this.w.write(Instruction.I64_SHR_U);
			this.w.write(Instruction.I32_WRAP_I64);
			boxI31();
			newCons();
			this.w.write(Instruction.END);
		}

		// drop-readable / drop-writable: unbox the end's handle, hand it back, nil.
		private void emitAsyncDrop() {
			getLocal(1);
			WasmEmitHelper.castI31GetS(this.ctx);
			callImport();
			refNullEq();
			this.w.write(Instruction.END);
		}

		// stream.read into a staged chunk: the ASYNC (non-blocking) built-in; one chunk
		// per call, returning the bytes as a Lisp string, or nil once the stream is
		// dropped (EOF). The completion value is (count << 4) | status; a read
		// completes with at least one byte unless the writer is gone, so count 0 =
		// EOF. Outside asyncMode a BLOCKED read parks the whole task on the
		// waitable-set; in asyncMode it returns a PENDING future instead -- registered
		// with the scheduler (kind 1) and settled by the EVENT_STREAM_READ dispatch in
		// _sched_loop -- so other tasks' frames keep running while the chunk is in
		// flight. The chunk buffer must outlive this call, so it never comes from the
		// popped staging: it is recycled through the scheduler's free list (bounding
		// linear-memory growth by the maximum number of concurrent reads).
		private void emitStreamRead() {
			WasmFutureRuntimeBuilder.Sched wiring = this.sched;
			if (wiring == null) {
				emitStagingPrologue();
				int buf = allocRetArea(ASYNC_READ_CHUNK);
				int n = allocI32();
				int handle = allocI32();
				getLocal(1);
				WasmEmitHelper.castI31GetS(this.ctx);
				setLocal(handle);
				getLocal(handle);
				getLocal(buf);
				i32Const(ASYNC_READ_CHUNK);
				callImport();
				emitBlockedWait(handle);
				i32Const(4);
				this.w.write(Instruction.I32_SHR_U);
				this.w.write(Instruction.TEE_LOCAL);
				this.w.writeSignedLeb128(n);
				this.w.write(Instruction.IF);
				this.w.write(Type.REFNULL.code());
				this.w.writeHeapType(Type.EQ.code());
				getLocal(buf);
				getLocal(n);
				this.w.write(Instruction.CALL);
				this.w.writeSignedLeb128(this.strFromMemFuncIndex);
				this.w.write(Instruction.ELSE);
				refNullEq();
				this.w.write(Instruction.END);
				int resultTmp = this.ctx.allocTemp();
				setLocal(resultTmp);
				emitStagingEpilogue(resultTmp);
				return;
			}
			WaitOrdinals ordinals = Objects.requireNonNull(this.waitOrdinals,
					"async built-in wrapper emitted without waitable-set ordinals");
			int handle = allocI32();
			int buf = allocI32();
			int ret = allocI32();
			int n = allocI32();
			getLocal(1);
			WasmEmitHelper.castI31GetS(this.ctx);
			setLocal(handle);
			// buf = pop the free list, or a fresh (permanent) allocation.
			globalGet(wiring.readFreeGlobal());
			this.w.write(Instruction.REF_IS_NULL);
			this.w.write(Instruction.IF, 0x40);
			i32Const(ASYNC_READ_CHUNK);
			this.w.write(Instruction.CALL);
			this.w.writeSignedLeb128(this.allocFuncIndex);
			setLocal(buf);
			this.w.write(Instruction.ELSE);
			globalGet(wiring.readFreeGlobal());
			structGet(WasmLispCompiler.TYPE_CONS, 0);
			WasmEmitHelper.castI31GetS(this.ctx);
			setLocal(buf);
			globalGet(wiring.readFreeGlobal());
			structGet(WasmLispCompiler.TYPE_CONS, 1);
			globalSet(wiring.readFreeGlobal());
			this.w.write(Instruction.END);
			getLocal(handle);
			getLocal(buf);
			i32Const(ASYNC_READ_CHUNK);
			callImport();
			setLocal(ret);
			getLocal(ret);
			i32Const(-1);
			this.w.write(Instruction.I32_EQ);
			this.w.write(Instruction.IF);
			this.w.write(Type.REFNULL.code());
			this.w.writeHeapType(Type.EQ.code());
			// BLOCKED: a fresh pending future, registered as
			// (handle . (1 . (future . (buf . nil)))) -- the stream struct is attached
			// by _wasi_stream_read -- with the handle joined into the task
			// waitable-set (created lazily).
			this.w.write(Instruction.CALL);
			this.w.writeSignedLeb128(this.ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_NEW);
			int fut = this.ctx.allocTemp();
			setLocal(fut);
			getLocal(handle);
			boxI31();
			i32Const(1);
			boxI31();
			getLocal(fut);
			getLocal(buf);
			boxI31();
			refNullEq();
			newCons(); // (buf . nil)
			newCons(); // (future . ...)
			newCons(); // (kind . ...)
			newCons(); // (handle . ...)
			globalGet(wiring.registryGlobal());
			newCons();
			globalSet(wiring.registryGlobal());
			globalGet(wiring.setGlobal());
			this.w.write(Instruction.I32_EQZ);
			this.w.write(Instruction.IF, 0x40);
			callOrdinal(ordinals.setNew());
			globalSet(wiring.setGlobal());
			this.w.write(Instruction.END);
			getLocal(handle);
			globalGet(wiring.setGlobal());
			callOrdinal(ordinals.join());
			getLocal(fut);
			this.w.write(Instruction.ELSE);
			// Completed immediately: lift the chunk (count 0 = EOF -> nil) and recycle
			// the buffer.
			getLocal(ret);
			i32Const(4);
			this.w.write(Instruction.I32_SHR_U);
			this.w.write(Instruction.TEE_LOCAL);
			this.w.writeSignedLeb128(n);
			this.w.write(Instruction.IF);
			this.w.write(Type.REFNULL.code());
			this.w.writeHeapType(Type.EQ.code());
			getLocal(buf);
			getLocal(n);
			this.w.write(Instruction.CALL);
			this.w.writeSignedLeb128(this.strFromMemFuncIndex);
			this.w.write(Instruction.ELSE);
			refNullEq();
			this.w.write(Instruction.END);
			getLocal(buf);
			boxI31();
			globalGet(wiring.readFreeGlobal());
			newCons();
			globalSet(wiring.readFreeGlobal());
			this.w.write(Instruction.END);
			this.w.write(Instruction.END);
		}

		// stream.write of a whole Lisp string: the async built-in plus the BLOCKED
		// park, so one call carries the whole payload (the rendezvous the sync variant
		// used to provide). Returns the accepted byte count.
		private void emitStreamWrite() {
			emitStagingPrologue();
			int handle = allocI32();
			getLocal(1);
			WasmEmitHelper.castI31GetS(this.ctx);
			setLocal(handle);
			getLocal(handle);
			emitStageStringParam(2);
			callImport();
			emitBlockedWait(handle);
			i32Const(4);
			this.w.write(Instruction.I32_SHR_U);
			boxI31();
			int resultTmp = this.ctx.allocTemp();
			setLocal(resultTmp);
			emitStagingEpilogue(resultTmp);
		}

		// future.read: the async built-in plus the BLOCKED park until the payload
		// arrives, then lift it from the return area with the same machinery as an
		// indirect function result -- so a future<result<...>> reads out as the
		// (:ok . V) / (:error . E) envelope. A dropped future (the writer went away
		// without a value) reads as nil.
		private void emitFutureRead(Async async) {
			WitType payload = Objects.requireNonNull(async.payload(), "future payload");
			emitStagingPrologue();
			int rp = allocRetArea(async.abi().size(payload));
			int handle = allocI32();
			getLocal(1);
			WasmEmitHelper.castI31GetS(this.ctx);
			setLocal(handle);
			getLocal(handle);
			getLocal(rp);
			callImport();
			emitBlockedWait(handle);
			i32Const(0xF);
			this.w.write(Instruction.I32_AND);
			this.w.write(Instruction.I32_EQZ);
			this.w.write(Instruction.IF);
			this.w.write(Type.REFNULL.code());
			this.w.writeHeapType(Type.EQ.code());
			emitLiftAt(async.abi(), payload, rp, 0);
			this.w.write(Instruction.ELSE);
			refNullEq();
			this.w.write(Instruction.END);
			int resultTmp = this.ctx.allocTemp();
			setLocal(resultTmp);
			emitStagingEpilogue(resultTmp);
		}

		// future.write: lower the Lisp value into the canonical memory representation
		// (the mirror of emitLiftAt) and hand it to the pending reader, parking on
		// BLOCKED until one exists. Returns t when the reader took it, nil when the
		// readable end was dropped first.
		private void emitFutureWrite(Async async) {
			WitType payload = Objects.requireNonNull(async.payload(), "future payload");
			emitStagingPrologue();
			int rp = allocRetArea(async.abi().size(payload));
			int handle = allocI32();
			emitLowerAt(async.abi(), payload, 2, rp, 0);
			getLocal(1);
			WasmEmitHelper.castI31GetS(this.ctx);
			setLocal(handle);
			getLocal(handle);
			getLocal(rp);
			callImport();
			emitBlockedWait(handle);
			i32Const(0xF);
			this.w.write(Instruction.I32_AND);
			this.w.write(Instruction.I32_EQZ);
			WasmEmitHelper.emitBoolFromI32(this.ctx);
			int resultTmp = this.ctx.allocTemp();
			setLocal(resultTmp);
			emitStagingEpilogue(resultTmp);
		}

		// The BLOCKED (-1) park shared by the four async built-in wrappers: with the
		// operation's packed result on the stack, leaves the FINAL packed result on
		// the stack -- immediately when the built-in completed, or the completion
		// event's payload after a blocking waitable-set.wait (legal from any
		// async-typed task under base component-model-async). The handle is unjoined
		// (waitable.join with set 0) and the throwaway set dropped, so the
		// stream/future handle survives for later operations.
		private void emitBlockedWait(int handleLocal) {
			WaitOrdinals ordinals = Objects.requireNonNull(this.waitOrdinals,
					"async built-in wrapper emitted without waitable-set ordinals");
			int ret = allocI32();
			int set = allocI32();
			setLocal(ret);
			int evtp = allocRetArea(8);
			getLocal(ret);
			i32Const(-1);
			this.w.write(Instruction.I32_EQ);
			this.w.write(Instruction.IF, 0x40);
			callOrdinal(ordinals.setNew());
			setLocal(set);
			getLocal(handleLocal);
			getLocal(set);
			callOrdinal(ordinals.join());
			this.w.write(Instruction.BLOCK, 0x40);
			this.w.write(Instruction.LOOP, 0x40);
			getLocal(set);
			getLocal(evtp);
			callOrdinal(ordinals.setWait());
			this.w.write(Instruction.DROP);
			load(evtp, 0, Instruction.I32_LOAD, 2);
			getLocal(handleLocal);
			this.w.write(Instruction.I32_EQ);
			this.w.write(Instruction.BR_IF);
			this.w.writeUnsignedLeb128(1);
			this.w.write(Instruction.BR);
			this.w.writeUnsignedLeb128(0);
			this.w.write(Instruction.END);
			this.w.write(Instruction.END);
			load(evtp, 4, Instruction.I32_LOAD, 2);
			setLocal(ret);
			getLocal(handleLocal);
			i32Const(0);
			callOrdinal(ordinals.join());
			getLocal(set);
			callOrdinal(ordinals.setDrop());
			this.w.write(Instruction.END);
			getLocal(ret);
		}

		// --- shared scaffolding ---

		// mark = align8(HEAP_PTR), and HEAP_PTR = mark: the staging snapshot the
		// epilogue pops back to, AND the alignment floor of everything staged above
		// it. The canonical ABI rejects a misaligned return area outright ("pointer
		// not aligned"), and the bump heap is NOT always 8-aligned when a call
		// arrives: `_intern` copies a first-seen symbol's bytes into the permanent
		// low region and advances the pointer by their exact length. So align here
		// rather than trust the caller.
		private void emitStagingPrologue() {
			i32Const(WasmLispCompiler.HEAP_PTR_ADDR);
			alignedHeapTop(() -> loadCell(WasmLispCompiler.HEAP_PTR_ADDR));
			this.w.write(Instruction.TEE_LOCAL);
			this.w.writeSignedLeb128(this.mark);
			this.w.write(Instruction.I32_STORE, 0x02, 0x00);
		}

		// HEAP_PTR = align8(max(mark, intern high-water)): pop the per-call staging,
		// but never below the permanent interned-symbol region (the
		// __ronto_alloc_reset rule) -- and keep the pointer 8-aligned, because that
		// region's high-water is not (see the staging prologue).
		private void emitStagingEpilogue(int resultTmp) {
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

		// A bump-allocated scratch area above the staging mark (__ronto_alloc aligns to
		// 8, satisfying every canonical alignment), reclaimed by the epilogue's pop.
		// Stores the pointer in a fresh i32 scratch local (nothing left on the stack)
		// and returns its slot.
		private int allocRetArea(int size) {
			int rp = allocI32();
			i32Const(size);
			this.w.write(Instruction.CALL);
			this.w.writeSignedLeb128(this.allocFuncIndex);
			setLocal(rp);
			return rp;
		}

		private void callImport() {
			this.w.write(Instruction.CALL);
			this.w.writeUnsignedLeb128(WasmImportCompiler.PLACEHOLDER_FUNC_BASE + this.ordinal);
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
				// A stream/future crosses as its bare i32 end handle; the async built-ins
				// (not this lowering) are what read/write through it.
				case WitType.StreamOf ignored -> emitLowerHandleParam(slot);
				case WitType.FutureOf ignored -> emitLowerHandleParam(slot);
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
					"'" + this.lispName + "': a variant case's flat " + have + " does not join into " + want);
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
					this.w.writeSignedLeb128(WasmLispCompiler.FUNC_PLIST_GET);
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

		// --- argument lowering into memory (the mirror of emitLiftAt) ---

		// Stores the canonical memory representation of the boxed Lisp value in `slot`
		// at [base + offset]. This is what a guest-created value crosses through when
		// the boundary is a memory area rather than flat core values: a future.write's
		// payload today, an export's spilled result tomorrow. The Lisp shapes are the
		// settled mapping, identical to what emitLiftAt reads back.
		private void emitLowerAt(WitCanonicalAbi outer, WitType type, int slot, int base, int offset) {
			WitCanonicalAbi.Scoped scoped = outer.resolveAliases(type);
			WitCanonicalAbi abi = scoped.abi();
			WitType t = scoped.type();
			switch (t) {
				case WitType.Prim prim -> {
					switch (prim.name()) {
						case "bool" -> {
							getLocal(base);
							getLocal(slot);
							this.w.write(Instruction.REF_IS_NULL);
							this.w.write(Instruction.I32_EQZ);
							store(offset, Instruction.I32_STORE8, 0);
						}
						case "s8", "u8" -> {
							getLocal(base);
							getLocal(slot);
							WasmEmitHelper.castI31GetS(this.ctx);
							store(offset, Instruction.I32_STORE8, 0);
						}
						case "s16", "u16" -> {
							getLocal(base);
							getLocal(slot);
							WasmEmitHelper.castI31GetS(this.ctx);
							store(offset, Instruction.I32_STORE16, 1);
						}
						case "s32" -> {
							getLocal(base);
							getLocal(slot);
							WasmEmitHelper.castFloatGetF64(this.ctx);
							this.w.write(Instruction.I32_TRUNC_S_F64);
							store(offset, Instruction.I32_STORE, 2);
						}
						case "u32" -> {
							getLocal(base);
							getLocal(slot);
							WasmEmitHelper.castFloatGetF64(this.ctx);
							this.w.write(Instruction.I32_TRUNC_U_F64);
							store(offset, Instruction.I32_STORE, 2);
						}
						case "s64" -> {
							getLocal(base);
							getLocal(slot);
							WasmEmitHelper.castFloatGetF64(this.ctx);
							this.w.write(Instruction.I64_TRUNC_S_F64);
							store(offset, Instruction.I64_STORE, 3);
						}
						case "u64" -> {
							getLocal(base);
							getLocal(slot);
							WasmEmitHelper.castFloatGetF64(this.ctx);
							this.w.write(Instruction.I64_TRUNC_U_F64);
							store(offset, Instruction.I64_STORE, 3);
						}
						case "f32" -> {
							getLocal(base);
							getLocal(slot);
							WasmEmitHelper.castFloatGetF64(this.ctx);
							this.w.write(Instruction.F32_DEMOTE_F64);
							store(offset, Instruction.F32_STORE, 2);
						}
						case "f64" -> {
							getLocal(base);
							getLocal(slot);
							WasmEmitHelper.castFloatGetF64(this.ctx);
							store(offset, Instruction.F64_STORE, 3);
						}
						case "char" -> {
							getLocal(base);
							getLocal(slot);
							structGet(WasmLispCompiler.TYPE_CHAR, 0);
							store(offset, Instruction.I32_STORE, 2);
						}
						case "string" -> emitStoreStringAt(slot, base, offset);
						default -> throw paramUnsupported(prim.name());
					}
				}
				case WitType.ListOf list when abi.isU8(list.element()) -> emitStoreStringAt(slot, base, offset);
				case WitType.ListOf ignored -> throw paramUnsupported("list<T>");
				case WitType.BorrowOf ignored -> emitStoreHandleAt(slot, base, offset);
				case WitType.OwnOf ignored -> emitStoreHandleAt(slot, base, offset);
				case WitType.StreamOf ignored -> emitStoreHandleAt(slot, base, offset);
				case WitType.FutureOf ignored -> emitStoreHandleAt(slot, base, offset);
				case WitType.OptionOf ignored -> emitLowerVariantAt(abi, t, slot, base, offset);
				case WitType.ResultOf ignored -> emitLowerVariantAt(abi, t, slot, base, offset);
				case WitType.TupleOf ignored -> emitLowerRecordAt(abi, t, slot, base, offset, false);
				case WitType.Named named -> {
					switch (abi.resolveNamed(named)) {
						case WitItem.ResourceDef ignored -> emitStoreHandleAt(slot, base, offset);
						case WitItem.EnumDef ignored -> emitLowerVariantAt(abi, t, slot, base, offset);
						case WitItem.VariantDef ignored -> emitLowerVariantAt(abi, t, slot, base, offset);
						case WitItem.RecordDef ignored -> emitLowerRecordAt(abi, t, slot, base, offset, true);
						default -> throw paramUnsupported(named.name());
					}
				}
				default -> throw paramUnsupported(t.getClass().getSimpleName());
			}
		}

		private void emitStoreHandleAt(int slot, int base, int offset) {
			getLocal(base);
			getLocal(slot);
			WasmEmitHelper.castI31GetS(this.ctx);
			store(offset, Instruction.I32_STORE, 2);
		}

		// string / list<u8> at [base + offset]: stage the bytes above the heap mark and
		// store the canonical (content ptr @+0, content len @+4) pair.
		private void emitStoreStringAt(int slot, int base, int offset) {
			int save = this.i32Cursor;
			int ptr = allocI32();
			int len = allocI32();
			emitStageStringParam(slot);
			setLocal(len);
			setLocal(ptr);
			getLocal(base);
			getLocal(ptr);
			store(offset, Instruction.I32_STORE, 2);
			getLocal(base);
			getLocal(len);
			store(offset + 4, Instruction.I32_STORE, 2);
			this.i32Cursor = save;
		}

		// A variant-shaped value (variant / enum / option / result) into memory: store
		// the discriminant, then the matched case's payload at the payload offset. The
		// Lisp shapes are the same the flat lowering matches on (nil-or-value for an
		// option, the case keyword or (keyword . payload) cons otherwise).
		private void emitLowerVariantAt(WitCanonicalAbi abi, WitType type, int slot, int base, int offset) {
			WitCanonicalAbi.VariantInfo info = abi.variantInfo(type);
			int discOp = switch (info.discSize()) {
				case 1 -> Instruction.I32_STORE8;
				case 2 -> Instruction.I32_STORE16;
				default -> Instruction.I32_STORE;
			};
			int discAlign = info.discSize() == 1 ? 0 : info.discSize() == 2 ? 1 : 2;
			int payloadOffset = offset + info.payloadOffset();
			if (abi.resolveAliases(type).type() instanceof WitType.OptionOf opt) {
				// none = nil, some(v) = the value itself: no tag to read.
				getLocal(slot);
				this.w.write(Instruction.REF_IS_NULL);
				this.w.write(Instruction.IF, 0x40);
				getLocal(base);
				i32Const(0);
				store(offset, discOp, discAlign);
				this.w.write(Instruction.ELSE);
				getLocal(base);
				i32Const(1);
				store(offset, discOp, discAlign);
				emitCasePayloadAt(info.abi(), opt.element(), slot, base, payloadOffset);
				this.w.write(Instruction.END);
				return;
			}
			int tagId = emitTagId(slot);
			boolean anyPayload = false;
			for (WitType payload : info.payloads()) {
				anyPayload |= payload != null && !info.abi().flatTypes(payload).isEmpty();
			}
			int payload = anyPayload ? emitPayload(slot) : -1;
			int n = info.names().size();
			for (int i = 0; i < n; i++) {
				getLocal(tagId);
				i32Const(this.ctx.stringTable.addString(":" + info.names().get(i)).offset());
				this.w.write(Instruction.I32_EQ);
				this.w.write(Instruction.IF, 0x40);
				getLocal(base);
				i32Const(i);
				store(offset, discOp, discAlign);
				emitCasePayloadAt(info.abi(), info.payloads().get(i), payload, base, payloadOffset);
				this.w.write(Instruction.ELSE);
			}
			// The value names no case of this variant: a type error, and it traps
			// exactly as every other type error does on this backend.
			this.w.write(Instruction.UNREACHABLE);
			for (int i = 0; i < n; i++) {
				this.w.write(Instruction.END);
			}
		}

		// One case's payload into memory; a payload that flattens to nothing (an empty
		// tuple, a payload-less case) writes nothing. The scratch cursors roll back
		// afterwards: the cases are mutually exclusive.
		private void emitCasePayloadAt(WitCanonicalAbi abi, @Nullable WitType payload, int payloadSlot, int base,
				int payloadOffset) {
			if (payload == null || abi.flatTypes(payload).isEmpty()) {
				return;
			}
			if (payloadSlot < 0) {
				throw new IllegalStateException("a payload-bearing case has no payload value");
			}
			int save32 = this.i32Cursor;
			int save64 = this.i64Cursor;
			emitLowerAt(abi, payload, payloadSlot, base, payloadOffset);
			this.i32Cursor = save32;
			this.i64Cursor = save64;
		}

		// record (a keyword plist) / tuple (a positional list) into memory: the fields
		// at their canonical offsets, in WIT order.
		private void emitLowerRecordAt(WitCanonicalAbi abi, WitType type, int slot, int base, int offset,
				boolean plist) {
			WitCanonicalAbi.RecordInfo info = abi.recordInfo(type);
			for (int i = 0; i < info.names().size(); i++) {
				int save32 = this.i32Cursor;
				int save64 = this.i64Cursor;
				int field = this.ctx.allocTemp();
				getLocal(slot);
				if (plist) {
					i32Const(this.ctx.stringTable.addString(":" + info.names().get(i)).offset());
					this.w.write(Instruction.CALL);
					this.w.writeSignedLeb128(WasmLispCompiler.FUNC_PLIST_GET);
				}
				else {
					for (int k = 0; k < i; k++) {
						structGet(WasmLispCompiler.TYPE_CONS, 1);
					}
					structGet(WasmLispCompiler.TYPE_CONS, 0);
				}
				setLocal(field);
				emitLowerAt(info.abi(), info.types().get(i), field, base, offset + info.offsets().get(i));
				this.i32Cursor = save32;
				this.i64Cursor = save64;
			}
		}

		// [addr, value] -> store at the immediate offset.
		private void store(int offset, int storeOp, int align) {
			this.w.write(storeOp, align);
			this.w.writeUnsignedLeb128(offset);
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
				case WitType.StreamOf ignored -> boxI31();
				case WitType.FutureOf ignored -> boxI31();
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
				case WitType.StreamOf ignored -> {
					load(base, offset, Instruction.I32_LOAD, 2);
					boxI31();
				}
				case WitType.FutureOf ignored -> {
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

		private void globalGet(int index) {
			this.w.write(Instruction.GET_GLOBAL);
			this.w.writeSignedLeb128(index);
		}

		private void globalSet(int index) {
			this.w.write(Instruction.SET_GLOBAL);
			this.w.writeSignedLeb128(index);
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
			return new UnsupportedOperationException("'" + this.lispName + "': the WIT parameter type '" + what
					+ "' does not cross the component import boundary yet");
		}

		private UnsupportedOperationException resultUnsupported(String what) {
			return new UnsupportedOperationException("'" + this.lispName + "': the WIT result type '" + what
					+ "' does not cross the component import boundary yet");
		}

	}

}

package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
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
 * fields' flats, 64-bit integers on the {@code i64} lane), and results lift from the flat
 * value or the return area per the canonical ABI's layout rules ({@link WitCanonicalAbi})
 * into rontolisp values &mdash; a {@code result} lifts to the {@code (:ok . V)} /
 * {@code (:error . E)} envelope the Lisp-side {@code rontolisp::%wit-result} wrapper
 * unwraps (and whose error arm it signals as {@code rontolisp:wit-error}).
 *
 * <p>
 * Lowering and lifting are mirror images of one shape, which is what makes a lifted value
 * passable straight back into another call: a variant case is the keyword {@code :get}
 * (or {@code (:other . "PATCH")} when it carries a payload), an enum is a keyword, a
 * record is a keyword plist, an option is the value or {@code nil}, a {@code list<T>} a
 * proper list (staged as a canonical array in linear memory when lowered).
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
	 * @param octets whether a {@code list<u8>} the result carries lifts as a packed
	 * {@code (unsigned-byte 8)} vector ({@code rontolisp:wit-import :octets t}) rather
	 * than as text
	 */
	record Decl(String lispName, String module, String field, WitResolver.Func func, WitCanonicalAbi abi,
			WitCanonicalAbi.FlatSig sig, boolean octets) {

		/** Whether the result lift calls {@code _bytes_from_mem}. */
		boolean liftsOctets() {
			return octets() && liftsListU8(func().def().func().result(), abi());
		}

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

	/**
	 * The async built-in operations a stream/future type alias binds. {@code CANCEL_READ}
	 * is a stream's alone: it withdraws a read an asyncMode {@code READ} left in flight,
	 * and a future read is never left in flight. {@code WRITE_START} and
	 * {@code WRITE_SETTLE} are a future's alone, the two halves of a write that does not
	 * wait for its reader ({@code <alias>-resolve}): the first is another wrapper of the
	 * {@code future.write} built-in {@code WRITE} calls, so it imports that field.
	 */
	enum AsyncOp {

		NEW("new"), READ("read"), WRITE("write"), DROP_READABLE("drop-readable"), DROP_WRITABLE("drop-writable"),
		CANCEL_READ("cancel-read"), WRITE_START("write-start", "write"), WRITE_SETTLE("write-settle", "cancel-write");

		final String suffix;

		/** The built-in the wrapper imports, as its field names it. */
		final String builtin;

		AsyncOp(String suffix) {
			this(suffix, suffix);
		}

		AsyncOp(String suffix, String builtin) {
			this.suffix = suffix;
			this.builtin = builtin;
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
	 * @param octets whether a {@code list<u8>} a future's payload carries lifts as a
	 * packed {@code (unsigned-byte 8)} vector ({@code :octets t}); a byte stream's chunk
	 * is one either way
	 * @param blocking whether a read waits where it is in asyncMode too (a
	 * {@code :blocking t} directive's, the entry's trailing {@code :blocking}), as a read
	 * of values or of a future always does, rather than answering a pending future
	 */
	record Async(String lispName, String module, String field, String alias, AsyncOp op, WitCanonicalAbi abi,
			WitType type, boolean octets, boolean blocking) {

		/**
		 * Whether a read's lift calls {@code _bytes_from_mem}: a future's payload, or a
		 * stream's values, carrying a {@code list<u8>} under {@code :octets t}.
		 */
		boolean liftsOctets() {
			return octets() && op() == AsyncOp.READ
					&& liftsListU8(valueElement() ? Objects.requireNonNull(element()) : payload(), abi());
		}

		boolean stream() {
			return type() instanceof WitType.StreamOf;
		}

		/** The stream's element ({@code null} for a future and a bare stream). */
		@Nullable WitType element() {
			return type() instanceof WitType.StreamOf s ? s.element() : null;
		}

		/**
		 * Whether the stream's element is a resource handle (wasi:sockets' accept
		 * {@code stream<tcp-socket>}): 4-byte i32 elements read one at a time and lifted
		 * as opaque integer handles, where a byte stream reads a chunk and lifts a packed
		 * {@code (unsigned-byte 8)} vector.
		 */
		boolean handleElement() {
			WitType element = element();
			if (element == null) {
				return false;
			}
			WitCanonicalAbi.Scoped scoped = abi().resolveAliases(element);
			return switch (scoped.type()) {
				case WitType.OwnOf ignored -> true;
				case WitType.BorrowOf ignored -> true;
				case WitType.Named named -> scoped.abi().resolveNamed(named) instanceof WitItem.ResourceDef;
				default -> false;
			};
		}

		/**
		 * Whether the stream's element is a value of another type than a byte or a handle
		 * ({@code stream<directory-entry>}): a read asks for at most so many, waits for
		 * them where it is, and lifts each as a function's result is lifted, into a list.
		 */
		boolean valueElement() {
			WitType element = element();
			return element != null && !abi().isU8(element) && !handleElement();
		}

		/** Whether the stream's element is a byte. */
		boolean byteElement() {
			WitType element = element();
			return element != null && abi().isU8(element);
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
	 * <p>
	 * A call started and waited on with no scheduler ({@code <member>-start} /
	 * {@code <member>-wait}) has a wait wrapper instead of the lift: it waits where it is
	 * for the subtask to return, releases it and lifts the result, as a read of a
	 * {@code :blocking} directive waits.
	 *
	 * @param startName the start wrapper's Lisp name ({@code pkg::%send-start})
	 * @param liftName the lift wrapper's Lisp name ({@code pkg::%send-lift}), or
	 * {@code null} for a call the wait wrapper lifts
	 * @param waitName the wait wrapper's Lisp name ({@code pkg:send-wait}), or
	 * {@code null} for a call the scheduler awaits
	 * @param module the WASM import module = the interface's canonical id
	 * @param field the WASM import field = the canonical-ABI function name
	 * @param func the WIT function
	 * @param abi the layout calculator scoped to the interface
	 * @param sig the async-lowered flat core signature
	 * @param octets whether a {@code list<u8>} the result carries lifts as a packed
	 * {@code (unsigned-byte 8)} vector ({@code :octets t})
	 */
	record AsyncCall(String startName, @Nullable String liftName, @Nullable String waitName, String module,
			String field, WitResolver.Func func, WitCanonicalAbi abi, WitCanonicalAbi.FlatSig sig, boolean octets) {

		/** Whether the lift wrapper calls {@code _bytes_from_mem}. */
		boolean liftsOctets() {
			return octets() && liftsListU8(func().def().func().result(), abi());
		}

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

	/** The import field of an async built-in of an alias. */
	static String asyncField(AsyncOp op, String alias) {
		return "[async-" + op.builtin + "]" + alias;
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

	/**
	 * The field the core module imports a resource's {@code canon resource.drop} under,
	 * before the resource's name, from the module of the interface that declares it.
	 */
	static final String DROP_PREFIX = "[resource-drop]";

	/**
	 * What the exports of a world name of an interface the component imports
	 * ({@code rontolisp:wit-export}, {@link WitExportSignature#importedResources}): the
	 * resources their types name, which the instance type exports and the component
	 * aliases out for them, and those an export borrows, whose
	 * {@code canon resource.drop} the core module imports under
	 * {@code [resource-drop]<name>} -- the field a binding's {@code <resource>-drop}
	 * imports, so the two share it.
	 *
	 * @param types the resources' names, in the order the world names them
	 * @param borrowed the names of those an export borrows
	 */
	record ExportUses(List<String> types, List<String> borrowed) {

		/** What an interface the exports name nothing of has. */
		static final ExportUses NONE = new ExportUses(List.of(), List.of());

		ExportUses merged(ExportUses other) {
			if (other.types().isEmpty()) {
				return this;
			}
			java.util.LinkedHashSet<String> types = new java.util.LinkedHashSet<>(this.types);
			types.addAll(other.types());
			java.util.LinkedHashSet<String> borrowed = new java.util.LinkedHashSet<>(this.borrowed);
			borrowed.addAll(other.borrowed());
			return new ExportUses(List.copyOf(types), List.copyOf(borrowed));
		}

	}

	/**
	 * An interface the component imports.
	 *
	 * @param ifaceId the interface's canonical id
	 * @param iface the interface
	 * @param resolver the resolver over the document defining it
	 * @param decls the functions the program binds
	 * @param drops the resource drops the program binds
	 * @param asyncs the stream and future built-ins the program binds
	 * @param calls the async funcs the program binds
	 * @param taskReturns the task-return built-ins the program binds
	 * @param exportUses what the world's exports name of the interface: an interface the
	 * program binds nothing of is imported for that alone
	 */
	record Import(String ifaceId, WitItem.InterfaceDef iface, WitResolver resolver, List<Decl> decls, List<Drop> drops,
			List<Async> asyncs, List<AsyncCall> calls, List<TaskReturn> taskReturns, ExportUses exportUses) {

		/**
		 * The bound functions the component instance declares: the first binding of each
		 * canonical field. Two bindings of one interface ({@link #mergeByIface}) may each
		 * bind a member -- under {@code :octets t} and without, say -- and keep a wrapper
		 * each, but the instance exports the function once.
		 * @return the decls, one per field
		 */
		List<Decl> distinctDecls() {
			return firstPerKey(decls(), Decl::field);
		}

		/**
		 * The async func members the component instance declares, one per field.
		 * @return the calls, one per field
		 */
		List<AsyncCall> distinctCalls() {
			return firstPerKey(calls(), AsyncCall::field);
		}

		/**
		 * The resource drops the component lowers, one per resource.
		 * @return the drops, one per field
		 */
		List<Drop> distinctDrops() {
			return firstPerKey(drops(), Drop::field);
		}

		private static <T> List<T> firstPerKey(List<T> all, java.util.function.Function<T, String> key) {
			java.util.Set<String> seen = new java.util.HashSet<>();
			List<T> out = new ArrayList<>(all.size());
			for (T item : all) {
				if (seen.add(key.apply(item))) {
					out.add(item);
				}
			}
			return out.size() == all.size() ? all : out;
		}

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
			// a function lowered both ways would be two of one name in the instance type
			for (AsyncCall call : calls) {
				if (decls.stream().anyMatch(decl -> decl.field().equals(call.field()))) {
					throw new UnsupportedOperationException("rontolisp:wit-import binds the async func '" + call.field()
							+ "' of " + imported.ifaceId()
							+ " both as a call that waits (:blocking t) and as one that does not, which a component "
							+ "lowers once");
				}
			}
			final List<TaskReturn> taskReturns = new ArrayList<>(prev.taskReturns());
			taskReturns.addAll(imported.taskReturns());
			// The interface / resolver are the same WIT type across both bindings (the
			// same
			// interface id, parsed from the same WIT text), so either serves the
			// component-level wiring; each Decl keeps its own abi, which is all the
			// lowering
			// reads.
			byId.put(imported.ifaceId(), new Import(prev.ifaceId(), prev.iface(), prev.resolver(), decls, drops, asyncs,
					calls, taskReturns, prev.exportUses().merged(imported.exportUses())));
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
		// (:octets t) leads the members of a :octets t directive's form.
		boolean octets = items.size() > 3 && items.get(3) instanceof LispCons marker
				&& marker.car() instanceof LispSymbol head && ":OCTETS".equals(head.name());
		for (int i = octets ? 4 : 3; i < items.size(); i++) {
			if (!(items.get(i) instanceof LispCons pair) || !(pair.cdr() instanceof LispCons rest)) {
				throw new UnsupportedOperationException(
						"Malformed internal component-import member: " + items.get(i).print());
			}
			// (:drop "bucket" "kv:bucket-drop") / (:async "body" "read" "h:body-read") --
			// the keyword head is what tells these apart from a ("member" "lisp-name")
			// function binding.
			if (pair.car() instanceof LispSymbol keyword && keyword.isKeyword()) {
				if (":ASYNC-CALL".equals(keyword.name())) {
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
					calls.add(new AsyncCall(startName.value(), liftName.value(), null, ifaceId.value(),
							cabiFieldName(func), func, abi, abi.flatSigAsyncLower(func), octets));
					continue;
				}
				// (:async-start "send" "client:send-start" "client:send-wait%raw"): the
				// call started by the one wrapper and waited on by the other
				if (":ASYNC-START".equals(keyword.name())) {
					if (!(rest.car() instanceof LispString member) || !(rest.cdr() instanceof LispCons startCons)
							|| !(startCons.car() instanceof LispString startName)
							|| !(startCons.cdr() instanceof LispCons waitCons)
							|| !(waitCons.car() instanceof LispString waitName) || waitCons.cdr() != LispNil.INSTANCE) {
						throw new UnsupportedOperationException(
								"Malformed internal component-import member: " + items.get(i).print());
					}
					WitResolver.Func func = funcs.stream()
						.filter(f -> member.value().equals(WitImportDirective.memberName(f)))
						.findFirst()
						.orElseThrow(() -> new IllegalStateException(
								"Internal component-import form names an unknown member: " + member.value()));
					calls.add(new AsyncCall(startName.value(), null, waitName.value(), ifaceId.value(),
							cabiFieldName(func), func, abi, abi.flatSigAsyncLower(func), octets));
					continue;
				}
				if (":TASK-RETURN".equals(keyword.name())) {
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
				if (":ASYNC".equals(keyword.name())) {
					if (!(rest.car() instanceof LispString alias) || !(rest.cdr() instanceof LispCons opCons)
							|| !(opCons.car() instanceof LispString op) || !(opCons.cdr() instanceof LispCons nameCons)
							|| !(nameCons.car() instanceof LispString asyncName)
							|| !(nameCons.cdr() instanceof LispNil
									|| nameCons.cdr() instanceof LispCons last && last.car() instanceof LispSymbol flag
											&& ":BLOCKING".equals(flag.name()) && last.cdr() instanceof LispNil)) {
						throw new UnsupportedOperationException(
								"Malformed internal component-import member: " + items.get(i).print());
					}
					WitCanonicalAbi.Scoped target = abi.resolveAliases(new WitType.Named(alias.value()));
					if (!(target.type() instanceof WitType.StreamOf) && !(target.type() instanceof WitType.FutureOf)) {
						throw new IllegalStateException("Internal component-import form names an async alias that is "
								+ "neither a stream nor a future: " + alias.value());
					}
					AsyncOp asyncOp = AsyncOp.of(op.value());
					asyncs.add(new Async(asyncName.value(), ifaceId.value(), asyncField(asyncOp, alias.value()),
							alias.value(), asyncOp, target.abi(), target.type(), octets,
							nameCons.cdr() instanceof LispCons));
					continue;
				}
				if (!":DROP".equals(keyword.name()) || !(rest.car() instanceof LispString resource)
						|| !(rest.cdr() instanceof LispCons tail) || !(tail.car() instanceof LispString dropName)) {
					throw new UnsupportedOperationException(
							"Malformed internal component-import member: " + items.get(i).print());
				}
				drops
					.add(new Drop(dropName.value(), ifaceId.value(), DROP_PREFIX + resource.value(), resource.value()));
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
			decls.add(new Decl(lispName.value(), ifaceId.value(), cabiFieldName(func), func, abi, abi.flatSig(func),
					octets));
		}
		return new Import(ifaceId.value(), iface, resolver, decls, drops, asyncs, calls, taskReturns, ExportUses.NONE);
	}

	/**
	 * Adds to the interfaces a program imports what its world's exports name of the
	 * interfaces the component imports ({@link ExportUses}): an interface the program
	 * binds nothing of becomes an import of its types alone, ordered before an interface
	 * that uses them ({@link #inDependencyOrder}).
	 * @param imports the imports, in dependency order
	 * @param named what the exports name, by interface
	 * @return the imports, unchanged when the exports name nothing
	 */
	static List<Import> withExportUses(List<Import> imports, List<WitExportSignature.Imported> named) {
		if (named.isEmpty()) {
			return imports;
		}
		final java.util.Map<String, Import> byId = new java.util.LinkedHashMap<>();
		for (Import imported : imports) {
			byId.put(imported.ifaceId(), imported);
		}
		for (WitExportSignature.Imported used : named) {
			ExportUses uses = new ExportUses(used.types(), used.borrowed());
			Import prev = byId.get(used.ifaceId());
			byId.put(used.ifaceId(),
					prev == null
							? new Import(used.ifaceId(), used.iface(), used.resolver(), List.of(), List.of(), List.of(),
									List.of(), List.of(), uses)
							: new Import(prev.ifaceId(), prev.iface(), prev.resolver(), prev.decls(), prev.drops(),
									prev.asyncs(), prev.calls(), prev.taskReturns(), prev.exportUses().merged(uses)));
		}
		return inDependencyOrder(new ArrayList<>(byId.values()));
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
	// Whether lifting a value of this type lifts a list<u8> ANYWHERE inside it -- the
	// value itself, an option's element, a record field, a case payload, a list's
	// element -- so an :octets binding needs _bytes_from_mem.
	static boolean liftsListU8(@Nullable WitType type, WitCanonicalAbi abi) {
		return switch (type) {
			case null -> false;
			case WitType.ListOf list -> abi.isU8(list.element()) || liftsListU8(list.element(), abi);
			case WitType.OptionOf opt -> liftsListU8(opt.element(), abi);
			case WitType.ResultOf res -> liftsListU8(res.ok(), abi) || liftsListU8(res.err(), abi);
			case WitType.TupleOf tuple -> tuple.elements().stream().anyMatch(element -> liftsListU8(element, abi));
			case WitType.Named named -> {
				WitCanonicalAbi in = abi.scopeOf(named);
				yield switch (abi.resolveNamed(named)) {
					case WitItem.TypeAlias alias -> liftsListU8(alias.target(), in);
					case WitItem.RecordDef record ->
						record.fields().stream().anyMatch(field -> liftsListU8(field.type(), in));
					case WitItem.VariantDef variant ->
						variant.cases().stream().anyMatch(c -> liftsListU8(c.payload(), in));
					default -> false;
				};
			}
			default -> false;
		};
	}

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
	 * @param bytesFromMemFuncIndex the function index of {@code _bytes_from_mem}, which a
	 * {@link Decl#liftsOctets} binding needs, or -1
	 * @return the code entry bytes
	 */
	static byte[] buildWrapperBody(WasmLispCompiler.Ctx.Builder ctxBuilder, Decl decl, int ordinal, int allocFuncIndex,
			int strFromMemFuncIndex, int bytesFromMemFuncIndex) {
		int numParams = lispArity(decl);
		int octetsFromMem = octetsLift(decl.liftsOctets(), bytesFromMemFuncIndex, decl.lispName());
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
		Body probe = emitBody(ctxBuilder, decl, numParams, ordinal, allocFuncIndex, strFromMemFuncIndex, octetsFromMem,
				MAX_SCRATCH, MAX_SCRATCH);
		Body body = emitBody(ctxBuilder, decl, numParams, ordinal, allocFuncIndex, strFromMemFuncIndex, octetsFromMem,
				probe.i32Pool(), probe.i64Pool());
		return wrapEntry(body);
	}

	// The helper an :octets binding lifts a list<u8> through (-1: it lifts none, and
	// every list<u8> is text).
	private static int octetsLift(boolean liftsOctets, int bytesFromMemFuncIndex, String lispName) {
		if (!liftsOctets) {
			return -1;
		}
		if (bytesFromMemFuncIndex < 0) {
			throw new IllegalStateException(
					"'" + lispName + "': an :octets list<u8> lift emitted without the _bytes_from_mem helper");
		}
		return bytesFromMemFuncIndex;
	}

	// The code-entry framing shared by every wrapper kind: the i32 scratch pool, the i64
	// scratch pool, then the eq temps handed out during emission.
	private static byte[] wrapEntry(Body body) {
		ByteArrayOutputStream entry = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		am.ik.wasm.WasmWriter entryWriter = new am.ik.wasm.WasmWriter(entry);
		entryWriter.write(body.eqTemps() > 0 ? 3 : 2);
		entryWriter.writeUnsignedLeb128(body.i32Pool());
		entryWriter.write(Type.I32);
		entryWriter.writeUnsignedLeb128(body.i64Pool());
		entryWriter.write(Type.I64);
		if (body.eqTemps() > 0) {
			entryWriter.writeUnsignedLeb128(body.eqTemps());
			entryWriter.writeRefType(true, Type.EQ.code());
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
		ByteArrayOutputStream entry = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		am.ik.wasm.WasmWriter writer = new am.ik.wasm.WasmWriter(entry);
		writer.write(0); // no local groups
		WasmLispCompiler.Ctx ctx = ctxBuilder.writer(writer).bodyStream(entry).build();
		// Local 1, not 0: every compiled function carries an implicit closure environment
		// in
		// slot 0 and starts its parameters at 1. Reading slot 0 here casts the null env
		// to
		// an i31 and traps.
		writer.write(Instruction.GET_LOCAL);
		writer.writeUnsignedLeb128(1);
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
	 * {@code future.read/write: [end, ptr] -> [status]}, {@code drop-*: [end] -> []},
	 * {@code stream.cancel-read: [end] -> [status]} (the synchronous variant).
	 * @param async the bound built-in
	 * @return the flat parameter types
	 */
	static Type[] asyncParamTypes(Async async) {
		return switch (async.op()) {
			case NEW -> new Type[0];
			case READ, WRITE, WRITE_START ->
				async.stream() ? new Type[] { Type.I32, Type.I32, Type.I32 } : new Type[] { Type.I32, Type.I32 };
			case DROP_READABLE, DROP_WRITABLE, CANCEL_READ, WRITE_SETTLE -> new Type[] { Type.I32 };
		};
	}

	/** Returns the WASM result types of an async built-in's core signature. */
	static Type[] asyncResultTypes(Async async) {
		return switch (async.op()) {
			case NEW -> new Type[] { Type.I64 };
			case READ, WRITE, CANCEL_READ, WRITE_START, WRITE_SETTLE -> new Type[] { Type.I32 };
			case DROP_READABLE, DROP_WRITABLE -> new Type[0];
		};
	}

	/**
	 * Returns the Lisp parameter count of an async built-in's synthetic defun: a read of
	 * a stream of values takes the most it reads beside the handle.
	 */
	static int lispArity(Async async) {
		return switch (async.op()) {
			case NEW -> 0;
			case WRITE, WRITE_SETTLE -> 2;
			case READ -> async.valueElement() ? 2 : 1;
			case DROP_READABLE, DROP_WRITABLE, CANCEL_READ -> 1;
			case WRITE_START -> 4;
		};
	}

	/**
	 * Whether a {@code future.read} or a {@code stream.read} of this built-in's type
	 * needs the canonical realloc option: the host stages a string or a list a future's
	 * payload or a stream's value carries through it (the fixed adapter's filesystem
	 * future and directory-entry stream precedents).
	 * @param async the bound built-in
	 * @return {@code true} when the read's {@code canon} needs realloc
	 */
	static boolean asyncReadNeedsRealloc(Async async) {
		WitType carried = async.valueElement() ? async.element() : async.payload();
		return carried != null && stagesMemory(carried, async.abi());
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
	 * @param streamEndedGlobal the global index of the ended-stream latch (a cons list of
	 * the i31 readable-end handles whose read completed DROPPED), or -1 when the module
	 * reads no stream
	 * @param writeOrdinal the ordinal of the alias's {@code future.write} import, which a
	 * {@code WRITE_SETTLE} wrapper calls beside its own; -1 for every other
	 * @return the code entry bytes
	 */
	static byte[] buildAsyncBody(WasmLispCompiler.Ctx.Builder ctxBuilder, Async async, int ordinal,
			WaitOrdinals waitOrdinals, int allocFuncIndex, int strFromMemFuncIndex, int bytesFromMemFuncIndex,
			WasmFutureRuntimeBuilder.@Nullable Sched sched, int streamEndedGlobal, int writeOrdinal) {
		int numParams = lispArity(async);
		Body probe = emitAsync(ctxBuilder, async, numParams, ordinal, waitOrdinals, allocFuncIndex, strFromMemFuncIndex,
				bytesFromMemFuncIndex, sched, streamEndedGlobal, writeOrdinal, MAX_SCRATCH, MAX_SCRATCH);
		Body body = emitAsync(ctxBuilder, async, numParams, ordinal, waitOrdinals, allocFuncIndex, strFromMemFuncIndex,
				bytesFromMemFuncIndex, sched, streamEndedGlobal, writeOrdinal, probe.i32Pool(), probe.i64Pool());
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

	/**
	 * The DROPPED status in the low 4 bits of a stream/future copy result
	 * ({@code (count << 4) | status}): the other end is gone. It takes precedence over
	 * COMPLETED, so a stream read can report items AND the end together.
	 */
	static final int COPY_RESULT_DROPPED = 1;

	/** The CANCELLED status of a copy a {@code cancel-*} built-in withdrew. */
	static final int COPY_RESULT_CANCELLED = 2;

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
	 * so each start grows the bump heap by its staging (accepted; noted in the design). A
	 * start a wait wrapper ends hands it where its staging begins and ends too,
	 * {@code (packed . (retptr . (mark . end)))}, for the wait to pop.
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
		ByteArrayOutputStream bodyStream = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
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
	 * @param bytesFromMemFuncIndex the function index of {@code _bytes_from_mem}, which
	 * an {@link AsyncCall#liftsOctets} binding needs, or -1
	 * @return the code entry bytes
	 */
	static byte[] buildAsyncLiftBody(WasmLispCompiler.Ctx.Builder ctxBuilder, AsyncCall call, int allocFuncIndex,
			int strFromMemFuncIndex, int bytesFromMemFuncIndex) {
		String name = Objects.requireNonNull(call.liftName());
		int octetsFromMem = octetsLift(call.liftsOctets(), bytesFromMemFuncIndex, name);
		Body probe = emitAsyncLift(ctxBuilder, call, name, null, allocFuncIndex, strFromMemFuncIndex, octetsFromMem,
				MAX_SCRATCH, MAX_SCRATCH);
		Body body = emitAsyncLift(ctxBuilder, call, name, null, allocFuncIndex, strFromMemFuncIndex, octetsFromMem,
				probe.i32Pool(), probe.i64Pool());
		return wrapEntry(body);
	}

	/**
	 * Builds the code entry of an async call's WAIT wrapper ({@code <member>-wait}):
	 * parse the token cons, wait where it is for a subtask still running to return -- its
	 * own waitable-set, as a read of a {@code :blocking} directive waits --, release the
	 * subtask and lift the result from the return area. What the start staged goes with
	 * this wrapper's own staging when nothing was kept above it since; else it stays, as
	 * a scheduled call's does.
	 * @param ctxBuilder the shared context builder
	 * @param call the bound async call
	 * @param waitOrdinals the interface's waitable-set built-ins and {@code subtask.drop}
	 * @param allocFuncIndex the function index of {@code __ronto_alloc}
	 * @param strFromMemFuncIndex the function index of {@code _str_from_mem}
	 * @param bytesFromMemFuncIndex the function index of {@code _bytes_from_mem}, which
	 * an {@link AsyncCall#liftsOctets} binding needs, or -1
	 * @return the code entry bytes
	 */
	static byte[] buildAsyncWaitBody(WasmLispCompiler.Ctx.Builder ctxBuilder, AsyncCall call, WaitOrdinals waitOrdinals,
			int allocFuncIndex, int strFromMemFuncIndex, int bytesFromMemFuncIndex) {
		String name = Objects.requireNonNull(call.waitName());
		int octetsFromMem = octetsLift(call.liftsOctets(), bytesFromMemFuncIndex, name);
		Body probe = emitAsyncLift(ctxBuilder, call, name, waitOrdinals, allocFuncIndex, strFromMemFuncIndex,
				octetsFromMem, MAX_SCRATCH, MAX_SCRATCH);
		Body body = emitAsyncLift(ctxBuilder, call, name, waitOrdinals, allocFuncIndex, strFromMemFuncIndex,
				octetsFromMem, probe.i32Pool(), probe.i64Pool());
		return wrapEntry(body);
	}

	// The lift of an async call's result, waited for first when the wait ordinals are
	// given (the wait wrapper).
	private static Body emitAsyncLift(WasmLispCompiler.Ctx.Builder ctxBuilder, AsyncCall call, String name,
			@Nullable WaitOrdinals waitOrdinals, int allocFuncIndex, int strFromMemFuncIndex, int octetsFromMem,
			int i32Pool, int i64Pool) {
		ByteArrayOutputStream bodyStream = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		am.ik.wasm.WasmWriter writer = new am.ik.wasm.WasmWriter(bodyStream);
		WasmLispCompiler.Ctx ctx = ctxBuilder.writer(writer).bodyStream(bodyStream).build();
		Gen gen = new Gen(ctx, name, 1, -1, allocFuncIndex, strFromMemFuncIndex, i32Pool, i64Pool);
		gen.octetsFromMemFuncIndex = octetsFromMem;
		gen.waitOrdinals = waitOrdinals;
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
		ByteArrayOutputStream bodyStream = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		am.ik.wasm.WasmWriter writer = new am.ik.wasm.WasmWriter(bodyStream);
		WasmLispCompiler.Ctx ctx = ctxBuilder.writer(writer).bodyStream(bodyStream).build();
		Gen gen = new Gen(ctx, tr.lispName(), 1, ordinal, allocFuncIndex, strFromMemFuncIndex, i32Pool, i64Pool);
		gen.emitTaskReturnBody(tr);
		int eqTemps = ctx.nextLocal - (1 + 1 + i32Pool + i64Pool);
		return new Body(bodyStream.toByteArray(), gen.i32High, gen.i64High, eqTemps);
	}

	private static Body emitAsync(WasmLispCompiler.Ctx.Builder ctxBuilder, Async async, int numParams, int ordinal,
			WaitOrdinals waitOrdinals, int allocFuncIndex, int strFromMemFuncIndex, int bytesFromMemFuncIndex,
			WasmFutureRuntimeBuilder.@Nullable Sched sched, int streamEndedGlobal, int writeOrdinal, int i32Pool,
			int i64Pool) {
		ByteArrayOutputStream bodyStream = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		am.ik.wasm.WasmWriter writer = new am.ik.wasm.WasmWriter(bodyStream);
		WasmLispCompiler.Ctx ctx = ctxBuilder.writer(writer).bodyStream(bodyStream).build();
		Gen gen = new Gen(ctx, async.lispName(), numParams, ordinal, allocFuncIndex, strFromMemFuncIndex, i32Pool,
				i64Pool);
		gen.waitOrdinals = waitOrdinals;
		gen.writeOrdinal = writeOrdinal;
		gen.sched = sched;
		gen.bytesFromMemFuncIndex = bytesFromMemFuncIndex;
		gen.octetsFromMemFuncIndex = octetsLift(async.liftsOctets(), bytesFromMemFuncIndex, async.lispName());
		gen.streamEndedGlobal = streamEndedGlobal;
		gen.emitAsyncBody(async);
		int eqTemps = ctx.nextLocal - (numParams + 1 + i32Pool + i64Pool);
		return new Body(bodyStream.toByteArray(), gen.i32High, gen.i64High, eqTemps);
	}

	// One emission of a wrapper body, and what it cost in locals.
	private record Body(byte[] bytes, int i32Pool, int i64Pool, int eqTemps) {
	}

	/**
	 * The typed scratch locals of a canonical export's wrapper, which follow its
	 * parameters: the two pools the marshalling draws from.
	 *
	 * @param i32 the size of the i32 pool
	 * @param i64 the size of the i64 pool
	 */
	record ExportPools(int i32, int i64) {

		/**
		 * {@return the locals' types, in slot order}
		 */
		List<Type> types() {
			List<Type> types = new ArrayList<>();
			for (int i = 0; i < this.i32; i++) {
				types.add(Type.I32);
			}
			for (int i = 0; i < this.i64; i++) {
				types.add(Type.I64);
			}
			return types;
		}

	}

	/**
	 * The two halves of a canonical export's wrapper that are the WIT's: lifting the
	 * parameters a host passed, and lowering the value the function answered. The wrapper
	 * around them -- the entry bookkeeping, the call, the way out -- is the flat export's
	 * ({@code WasmExportCompiler.emitBody}).
	 */
	static final class ExportMarshal {

		private final Gen gen;

		private final WitExportSignature signature;

		private final int cabiReallocFuncIndex;

		private ExportMarshal(Gen gen, WitExportSignature signature, int cabiReallocFuncIndex) {
			this.gen = gen;
			this.signature = signature;
			this.cabiReallocFuncIndex = cabiReallocFuncIndex;
		}

		/** Pushes every parameter as a boxed value, in order. */
		void liftParams() {
			this.gen.emitExportLiftParams(this.signature);
		}

		/** Turns the boxed value on the stack into the wrapper's core result. */
		void lowerResult() {
			this.gen.emitExportLowerResult(this.signature, this.cabiReallocFuncIndex);
		}

		/**
		 * Releases every handle the lift was lent of a resource the component imports,
		 * once the function has answered: the task may not end holding one. Leaves the
		 * stack as it is.
		 */
		void releaseBorrows() {
			this.gen.emitReleaseBorrows();
		}

	}

	/**
	 * The marshalling of a canonical export, writing into the wrapper's context; its
	 * scratch pools are the locals right after the wrapper's parameters.
	 * @param ctx the wrapper's compilation context
	 * @param decl the export, a canonical one
	 * @param pools the scratch pools' sizes ({@link #measureExport})
	 * @param allocFuncIndex the function index of {@code __ronto_alloc}
	 * @param strFromMemFuncIndex the function index of {@code _str_from_mem}
	 * @param bytesFromMemFuncIndex the function index of {@code _bytes_from_mem}, or -1
	 * @param cabiReallocFuncIndex the function index of the core's {@code cabi_realloc},
	 * whose post-return releases what the result staged, or -1 in a core module, whose
	 * host brackets the call
	 * @param handles what crosses a handle of a resource the world exports, or
	 * {@code null} when it exports none
	 * @param drops the placeholder ordinal of the {@code canon resource.drop} of each
	 * resource the component imports that an export borrows, by
	 * {@code <interface id>#<resource>} ({@link ExportUses})
	 * @return the marshalling
	 */
	static ExportMarshal exportMarshal(WasmLispCompiler.Ctx ctx, WasmExportCompiler.Decl decl, ExportPools pools,
			int allocFuncIndex, int strFromMemFuncIndex, int bytesFromMemFuncIndex, int cabiReallocFuncIndex,
			@Nullable ExportHandles handles, java.util.Map<String, Integer> drops) {
		WitExportSignature signature = java.util.Objects.requireNonNull(decl.wit());
		Gen gen = new Gen(ctx, decl.exportName(), signature.coreParams().length, -1, allocFuncIndex,
				strFromMemFuncIndex, pools.i32(), pools.i64(), true);
		gen.octetsFromMemFuncIndex = octetsLift(signature.liftsOctets(), bytesFromMemFuncIndex, decl.exportName());
		// a function of the world names only resources the component imports: a use
		// clause of the world imports the interface it names
		gen.exportHandles = signature.ofWorld() ? null : handles;
		gen.importedDrops = drops;
		return new ExportMarshal(gen, signature, cabiReallocFuncIndex);
	}

	/**
	 * Measures the scratch a canonical export's marshalling draws, by emitting it once
	 * into a throwaway stream: how much a wrapper needs is how deeply its types nest
	 * ({@link #buildWrapperBody}).
	 * @param ctxBuilder the shared context builder
	 * @param decl the export, a canonical one
	 * @param allocFuncIndex the function index of {@code __ronto_alloc}
	 * @param strFromMemFuncIndex the function index of {@code _str_from_mem}
	 * @param bytesFromMemFuncIndex the function index of {@code _bytes_from_mem}, or -1
	 * @param handles what crosses a handle of a resource the world exports, or
	 * {@code null}
	 * @param drops the {@code canon resource.drop} ordinal of each imported resource an
	 * export borrows
	 * @return the pools' sizes
	 */
	static ExportPools measureExport(WasmLispCompiler.Ctx.Builder ctxBuilder, WasmExportCompiler.Decl decl,
			int allocFuncIndex, int strFromMemFuncIndex, int bytesFromMemFuncIndex, @Nullable ExportHandles handles,
			java.util.Map<String, Integer> drops) {
		ByteArrayOutputStream bodyStream = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		am.ik.wasm.WasmWriter writer = new am.ik.wasm.WasmWriter(bodyStream);
		WasmLispCompiler.Ctx ctx = ctxBuilder.writer(writer).bodyStream(bodyStream).build();
		ExportMarshal marshal = exportMarshal(ctx, decl, new ExportPools(MAX_SCRATCH, MAX_SCRATCH), allocFuncIndex,
				strFromMemFuncIndex, bytesFromMemFuncIndex, 0, handles, drops);
		marshal.liftParams();
		marshal.lowerResult();
		marshal.releaseBorrows();
		return new ExportPools(marshal.gen.i32High, marshal.gen.i64High);
	}

	// Emits the body with scratch pools of the given sizes, and reports the sizes it
	// actually used. Re-emitting is safe and repeatable: the only state it shares with
	// the
	// caller is the string table, whose entries are content-keyed (adding the same
	// literal
	// twice yields the same offset).
	private static Body emitBody(WasmLispCompiler.Ctx.Builder ctxBuilder, Decl decl, int numParams, int ordinal,
			int allocFuncIndex, int strFromMemFuncIndex, int octetsFromMem, int i32Pool, int i64Pool) {
		ByteArrayOutputStream bodyStream = new am.ik.wasm.UnsynchronizedByteArrayOutputStream();
		am.ik.wasm.WasmWriter writer = new am.ik.wasm.WasmWriter(bodyStream);
		WasmLispCompiler.Ctx ctx = ctxBuilder.writer(writer).bodyStream(bodyStream).build();
		Gen gen = new Gen(ctx, decl.lispName(), numParams, ordinal, allocFuncIndex, strFromMemFuncIndex, i32Pool,
				i64Pool);
		gen.octetsFromMemFuncIndex = octetsFromMem;
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

		// Whether this is an EXPORT's wrapper: its boundary carries a value exactly or
		// traps (.kb/wit.md, "The integer boundary"), where an import's lift and lowering
		// keep the widths they always had.
		private final boolean export;

		// The interface's waitable-set builtin ordinals, set for the async built-in
		// wrappers (the BLOCKED park needs them); null for plain function wrappers.
		private @Nullable WaitOrdinals waitOrdinals;

		// The module's scheduler wiring, set for the async built-in wrappers of an
		// asyncMode module (a BLOCKED stream.read becomes a pending future registered
		// there); null outside asyncMode, where the BLOCKED park stays.
		private WasmFutureRuntimeBuilder.@Nullable Sched sched;

		// _bytes_from_mem, the lift of a byte-stream read's chunk (a packed
		// (unsigned-byte 8) vector); set for the async built-in wrappers, -1 elsewhere.
		private int bytesFromMemFuncIndex = -1;

		// What crosses a handle of a resource the export's component defines; null in an
		// import's wrapper and in an export of a world that exports no resource.
		private @Nullable ExportHandles exportHandles;

		// The canon resource.drop ordinal of each resource the component imports that an
		// export borrows, by <interface id>#<resource>; empty in an import's wrapper.
		private java.util.Map<String, Integer> importedDrops = java.util.Map.of();

		// What an export's lift was lent of such a resource: for each drop ordinal, the
		// eq local the handles are consed onto as they are lifted.
		private final java.util.Map<Integer, Integer> borrowLists = new java.util.LinkedHashMap<>();

		// _bytes_from_mem again, as the lift of every list<u8> of an :octets binding's
		// result (a packed (unsigned-byte 8) vector, the octets as the host wrote them);
		// -1 lifts each through _str_from_mem as text.
		private int octetsFromMemFuncIndex = -1;

		// The ended-stream latch global (a cons list of i31 readable-end handles whose
		// read completed DROPPED); set for the async built-in wrappers of a module that
		// reads a stream, -1 elsewhere.
		private int streamEndedGlobal = -1;

		// The alias's future.write import, which a WRITE_SETTLE wrapper calls beside the
		// cancel-write it imports; -1 elsewhere.
		private int writeOrdinal = -1;

		// An import's wrapper: a defun, whose slot 0 is the closure environment.
		Gen(WasmLispCompiler.Ctx ctx, String lispName, int numParams, int ordinal, int allocFuncIndex,
				int strFromMemFuncIndex, int i32Pool, int i64Pool) {
			this(ctx, lispName, numParams + 1, ordinal, allocFuncIndex, strFromMemFuncIndex, i32Pool, i64Pool, false);
		}

		// The scratch pools start at scratchBase, the first local after the wrapper's
		// parameters.
		Gen(WasmLispCompiler.Ctx ctx, String lispName, int scratchBase, int ordinal, int allocFuncIndex,
				int strFromMemFuncIndex, int i32Pool, int i64Pool, boolean export) {
			this.ctx = ctx;
			this.w = ctx.writer;
			this.lispName = lispName;
			this.i32Pool = i32Pool;
			this.i64Pool = i64Pool;
			this.i32Base = scratchBase;
			this.i64Base = scratchBase + i32Pool;
			this.i64Local = this.i64Base; // slot 0 of the pool: boxI64's permanent
											// scratch
			this.i64Cursor = 1;
			this.i64High = 1;
			this.ordinal = ordinal;
			this.allocFuncIndex = allocFuncIndex;
			this.strFromMemFuncIndex = strFromMemFuncIndex;
			this.export = export;
			ctx.nextLocal = scratchBase + i32Pool + i64Pool;
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
				this.w.writeUnsignedLeb128(this.allocFuncIndex);
				this.w.write(Instruction.TEE_LOCAL);
				this.w.writeUnsignedLeb128(rp);
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

		// --- an export's wrapper: the opposite direction ---

		// Lifts the export's parameters out of the wrapper's own parameter locals -- the
		// flat values in order, or the one pointer to their tuple -- and leaves each as a
		// boxed value on the stack, in order: what an import's result lift does to a
		// return value, applied to what a host passed in.
		void emitExportLiftParams(WitExportSignature sig) {
			if (sig.spilledParams()) {
				int offset = 0;
				for (var param : sig.func().params()) {
					offset = WitCanonicalAbi.alignTo(offset, sig.abi().alignment(param.type()));
					emitLiftAt(sig.abi(), param.type(), 0, offset);
					offset += sig.abi().size(param.type());
					resetScratch();
				}
				return;
			}
			List<Type> flats = sig.flatParams();
			List<Flat> locals = new ArrayList<>();
			for (int i = 0; i < flats.size(); i++) {
				locals.add(new Flat(i, flats.get(i)));
			}
			int next = 0;
			for (var param : sig.func().params()) {
				int width = sig.abi().flatTypes(param.type()).size();
				emitLiftFlats(sig.abi(), param.type(), locals.subList(next, next + width));
				next += width;
				resetScratch();
			}
		}

		// Lowers the boxed value on the stack as the export's result: its one flat
		// value, or its memory representation in a fresh area whose address is answered.
		// What is staged here stays until the host has read it: the post-return
		// (cabi_post_*) pops the heap to the mark cabi_realloc took, which the call
		// below takes now when the host lowered nothing through it -- and which leaves
		// the heap top 8-aligned, as every canonical area must be (an intern leaves it
		// anywhere).
		void emitExportLowerResult(WitExportSignature sig, int cabiReallocFuncIndex) {
			WitType result = sig.func().result();
			if (result == null) {
				this.w.write(Instruction.DROP);
				return;
			}
			int value = this.ctx.allocTemp();
			setLocal(value);
			if (!sig.indirectResult()) {
				emitLowerParam(sig.abi(), result, value);
				return;
			}
			int area = allocI32();
			if (cabiReallocFuncIndex >= 0) {
				i32Const(0);
				i32Const(0);
				i32Const(8);
				i32Const(sig.abi().size(result));
				this.w.write(Instruction.CALL);
				this.w.writeUnsignedLeb128(cabiReallocFuncIndex);
			}
			else {
				// A core module's host brackets the call itself (the arena pair): only
				// the alignment is this side's.
				emitStagingPrologue();
				i32Const(sig.abi().size(result));
				this.w.write(Instruction.CALL);
				this.w.writeUnsignedLeb128(this.allocFuncIndex);
			}
			setLocal(area);
			emitLowerAt(sig.abi(), result, value, area, 0);
			getLocal(area);
		}

		// A flat value of a lifted parameter: the local that holds it and that local's
		// core type, which inside a variant is the JOINED type of the position, wider
		// than what one case's payload reads there.
		private record Flat(int local, Type have) {
		}

		// Pushes a flat as the core type one reader wants: the canonical ABI's
		// narrowing of a joined flat (an i64 wraps to an i32, an integer reinterprets as
		// the float it carries).
		private void pushFlat(Flat flat, Type want) {
			getLocal(flat.local());
			Type have = flat.have();
			if (have == want) {
				return;
			}
			if (have == Type.I64 && want == Type.F64) {
				this.w.write(Instruction.F64_REINTERPRET_I64);
				return;
			}
			if (have == Type.I64) {
				this.w.write(Instruction.I32_WRAP_I64);
				have = Type.I32;
			}
			if (have == Type.I32 && want == Type.F32) {
				this.w.write(Instruction.F32_REINTERPRET_I32);
				return;
			}
			if (have != want) {
				throw new IllegalStateException(
						"'" + this.lispName + "': a joined flat " + flat.have() + " does not narrow to " + want);
			}
		}

		// Lifts a value from its flat values into a boxed value: the mirror of
		// emitLowerParam, building the shapes emitLiftAt builds from memory.
		private void emitLiftFlats(WitCanonicalAbi outer, WitType type, List<Flat> flats) {
			WitCanonicalAbi.Scoped scoped = outer.resolveAliases(type);
			WitCanonicalAbi abi = scoped.abi();
			WitType t = scoped.type();
			switch (t) {
				case WitType.Prim prim when "string".equals(prim.name()) ->
					emitLiftBytesFlats(flats, this.strFromMemFuncIndex);
				case WitType.Prim prim -> {
					pushFlat(flats.get(0), abi.flatTypes(t).get(0));
					emitLiftFlat(abi, t);
				}
				case WitType.ListOf list when abi.isU8(list.element()) -> emitLiftBytesFlats(flats,
						this.octetsFromMemFuncIndex < 0 ? this.strFromMemFuncIndex : this.octetsFromMemFuncIndex);
				case WitType.ListOf list -> {
					int save = this.i32Cursor;
					int elems = allocI32();
					int count = allocI32();
					pushFlat(flats.get(0), Type.I32);
					setLocal(elems);
					pushFlat(flats.get(1), Type.I32);
					setLocal(count);
					emitLiftElements(abi, list.element(), elems, count);
					this.i32Cursor = save;
				}
				case WitType.BorrowOf borrow -> {
					pushFlat(flats.get(0), Type.I32);
					emitLiftHandle(abi, borrow.resource(), true);
				}
				case WitType.OwnOf own -> {
					pushFlat(flats.get(0), Type.I32);
					emitLiftHandle(abi, own.resource(), false);
				}
				case WitType.OptionOf ignored -> emitLiftVariantFlats(abi, t, flats);
				case WitType.ResultOf ignored -> emitLiftVariantFlats(abi, t, flats);
				case WitType.TupleOf ignored -> emitLiftRecordFlats(abi, t, flats, false);
				case WitType.Named named -> {
					switch (abi.resolveNamed(named)) {
						case WitItem.ResourceDef ignored -> {
							pushFlat(flats.get(0), Type.I32);
							emitLiftHandle(abi, named.name(), false);
						}
						case WitItem.EnumDef ignored -> emitLiftVariantFlats(abi, t, flats);
						case WitItem.VariantDef ignored -> emitLiftVariantFlats(abi, t, flats);
						case WitItem.RecordDef ignored -> emitLiftRecordFlats(abi, t, flats, true);
						case WitItem.FlagsDef flags -> {
							pushFlat(flats.get(0), Type.I32);
							emitLiftFlags(flags);
						}
						default -> throw resultUnsupported(named.name());
					}
				}
				default -> throw resultUnsupported(t.getClass().getSimpleName());
			}
		}

		// string / list<u8> from its (ptr, byte len) flats.
		private void emitLiftBytesFlats(List<Flat> flats, int helperFuncIndex) {
			pushFlat(flats.get(0), Type.I32);
			pushFlat(flats.get(1), Type.I32);
			this.w.write(Instruction.CALL);
			this.w.writeUnsignedLeb128(helperFuncIndex);
		}

		// A variant-shaped value from its flats: the discriminant, then the matched
		// case's payload out of the joined positions.
		private void emitLiftVariantFlats(WitCanonicalAbi abi, WitType type, List<Flat> flats) {
			int save = this.i32Cursor;
			int disc = allocI32();
			pushFlat(flats.get(0), Type.I32);
			setLocal(disc);
			List<Flat> joined = flats.subList(1, flats.size());
			emitVariantDispatch(abi, type, disc,
					(in, payload) -> emitLiftFlats(in, payload, joined.subList(0, in.flatTypes(payload).size())));
			this.i32Cursor = save;
		}

		// record -> (:field value ...) keyword plist; tuple -> (v0 v1 ...) proper list:
		// each field out of the flats it flattened to, in order.
		private void emitLiftRecordFlats(WitCanonicalAbi abi, WitType type, List<Flat> flats, boolean plist) {
			WitCanonicalAbi.RecordInfo info = abi.recordInfo(type);
			int conses = 0;
			int next = 0;
			for (int i = 0; i < info.names().size(); i++) {
				if (plist) {
					WasmEmitHelper.compileStringLiteral(":" + info.names().get(i).toUpperCase(java.util.Locale.ROOT),
							this.ctx);
					conses++;
				}
				int width = info.abi().flatTypes(info.types().get(i)).size();
				if (width == 0) {
					// A field that flattens to nothing is an empty tuple: nil.
					refNullEq();
				}
				else {
					emitLiftFlats(info.abi(), info.types().get(i), flats.subList(next, next + width));
				}
				next += width;
				conses++;
			}
			refNullEq();
			for (int i = 0; i < conses; i++) {
				newCons();
			}
		}

		// --- the async-lowered call bodies ---

		// start: lower args, allocate the return area, async-lowered call, return the
		// (packed . retptr) token cons. NO staging epilogue: the staged bytes and the
		// return area must survive until the await.
		void emitAsyncStartBody(AsyncCall call) {
			emitStagingPrologue();
			WitResolver.Func func = call.func();
			int slot = 1;
			WitCanonicalAbi.FlatSig sig = call.sig();
			if (sig.spilledParams()) {
				// Past the async 4-flat cap the arguments travel as a tuple in one
				// caller-allocated area (which, like the return area, must survive
				// until the await -- no staging pop here). Only the pointer goes on
				// the stack.
				WitCanonicalAbi.SpillLayout layout = call.abi().spillLayout(func);
				int area = allocI32();
				i32Const(layout.size());
				this.w.write(Instruction.CALL);
				this.w.writeUnsignedLeb128(this.allocFuncIndex);
				setLocal(area);
				int index = 0;
				if (func.resource() != null && func.def().kind() == WitItem.FuncKind.PLAIN) {
					getLocal(area);
					getLocal(slot++);
					WasmEmitHelper.castI31GetS(this.ctx);
					store(layout.offsets()[index++], Instruction.I32_STORE, 2);
				}
				for (var param : func.def().func().params()) {
					emitLowerAt(call.abi(), param.type(), slot++, area, layout.offsets()[index++]);
					resetScratch();
				}
				getLocal(area);
			}
			else {
				if (func.resource() != null && func.def().kind() == WitItem.FuncKind.PLAIN) {
					getLocal(slot++);
					WasmEmitHelper.castI31GetS(this.ctx);
				}
				for (var param : func.def().func().params()) {
					emitLowerParam(call.abi(), param.type(), slot++);
					resetScratch();
				}
			}
			int rp = -1;
			if (sig.retptr()) {
				rp = allocI32();
				i32Const(sig.retSize());
				this.w.write(Instruction.CALL);
				this.w.writeUnsignedLeb128(this.allocFuncIndex);
				this.w.write(Instruction.TEE_LOCAL);
				this.w.writeUnsignedLeb128(rp);
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
			if (call.waitName() != null) {
				// (packed . (retptr . (mark . end))): where the staging begins and ends,
				// which the wait wrapper pops when nothing was kept above it meanwhile
				getLocal(this.mark);
				boxI31();
				loadCell(WasmLispCompiler.HEAP_PTR_ADDR);
				boxI31();
				newCons();
				newCons();
			}
			newCons();
			this.w.write(Instruction.END);
		}

		// lift: (packed . retptr) -> the lifted result. The subtask (when there was
		// one) has already reported RETURNED and been dropped by the scheduler; this
		// wrapper only reads the return area. The wait wrapper (wait ordinals given)
		// waits for it first.
		void emitAsyncLiftBody(AsyncCall call) {
			emitStagingPrologue();
			WaitOrdinals waited = this.waitOrdinals;
			if (waited != null) {
				emitSubtaskWait(waited);
			}
			int rp = allocI32();
			WitCanonicalAbi.FlatSig sig = call.sig();
			if (sig.retptr()) {
				getLocal(1);
				structGet(WasmLispCompiler.TYPE_CONS, 1);
				if (waited != null) {
					structGet(WasmLispCompiler.TYPE_CONS, 0);
				}
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
			if (waited != null) {
				// the subtask has returned and its answer is lifted, so what its start
				// staged goes with this wrapper's own staging -- when nothing was kept
				// above it since: this wrapper's mark is where the start's staging ended
				int span = allocI32();
				getLocal(1);
				structGet(WasmLispCompiler.TYPE_CONS, 1);
				structGet(WasmLispCompiler.TYPE_CONS, 1);
				this.w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
				this.w.writeHeapType(WasmLispCompiler.TYPE_CONS);
				int pair = this.ctx.allocTemp();
				this.w.write(Instruction.TEE_LOCAL);
				this.w.writeUnsignedLeb128(pair);
				structGet(WasmLispCompiler.TYPE_CONS, 1);
				WasmEmitHelper.castI31GetS(this.ctx);
				setLocal(span);
				getLocal(this.mark);
				alignedHeapTop(() -> getLocal(span));
				this.w.write(Instruction.I32_EQ);
				this.w.write(Instruction.IF, 0x40);
				getLocal(pair);
				structGet(WasmLispCompiler.TYPE_CONS, 0);
				WasmEmitHelper.castI31GetS(this.ctx);
				setLocal(this.mark);
				this.w.write(Instruction.END);
			}
			emitStagingEpilogue(resultTmp);
		}

		// The subtask of a call still running, waited for where it is: the token's
		// packed status is no RETURNED, the subtask's index above its four bits (a call
		// that returned at once has none). Its own waitable-set, whose every event is
		// the subtask's -- STARTED, then RETURNED --; then the subtask leaves the set
		// and is released, which only a delivered RETURNED allows.
		private void emitSubtaskWait(WaitOrdinals ordinals) {
			int packed = allocI32();
			int subtask = allocI32();
			int set = allocI32();
			getLocal(1);
			structGet(WasmLispCompiler.TYPE_CONS, 0);
			WasmEmitHelper.castI31GetS(this.ctx);
			setLocal(packed);
			getLocal(packed);
			i32Const(0xF);
			this.w.write(Instruction.I32_AND);
			i32Const(SUBTASK_STATUS_RETURNED);
			this.w.write(Instruction.I32_NE);
			this.w.write(Instruction.IF, 0x40);
			getLocal(packed);
			i32Const(4);
			this.w.write(Instruction.I32_SHR_U);
			setLocal(subtask);
			int evtp = allocRetArea(8);
			callOrdinal(ordinals.setNew());
			setLocal(set);
			getLocal(subtask);
			getLocal(set);
			callOrdinal(ordinals.join());
			this.w.write(Instruction.BLOCK, 0x40);
			this.w.write(Instruction.LOOP, 0x40);
			getLocal(set);
			getLocal(evtp);
			callOrdinal(ordinals.setWait());
			this.w.write(Instruction.DROP);
			load(evtp, 4, Instruction.I32_LOAD, 2);
			i32Const(SUBTASK_STATE_RETURNED);
			this.w.write(Instruction.I32_EQ);
			this.w.write(Instruction.BR_IF);
			this.w.writeUnsignedLeb128(1);
			this.w.write(Instruction.BR);
			this.w.writeUnsignedLeb128(0);
			this.w.write(Instruction.END);
			this.w.write(Instruction.END);
			getLocal(subtask);
			i32Const(0);
			callOrdinal(ordinals.join());
			getLocal(set);
			callOrdinal(ordinals.setDrop());
			getLocal(subtask);
			callOrdinal(ordinals.subtaskDrop());
			this.w.write(Instruction.END);
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
				case DROP_READABLE, DROP_WRITABLE ->
					emitAsyncDrop(async.op() == AsyncOp.DROP_READABLE && async.stream());
				case READ -> {
					if (async.valueElement()) {
						emitValueStreamRead(async);
					}
					else if (async.stream()) {
						emitStreamRead(async.handleElement(), async.blocking());
					}
					else {
						emitFutureRead(async);
					}
				}
				case WRITE -> {
					if (async.stream()) {
						emitStreamWrite(async.handleElement());
					}
					else {
						emitFutureWrite(async);
					}
				}
				case CANCEL_READ -> emitStreamCancelRead(async.handleElement());
				case WRITE_START -> emitFutureWriteStart(async);
				case WRITE_SETTLE -> emitFutureWriteSettle();
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

		// drop-readable / drop-writable: unbox the end's handle, hand it back, nil. A
		// stream's drop-readable first takes the handle off the ended-stream latch: the
		// latch dies with the handle, because the host reuses handle numbers.
		private void emitAsyncDrop(boolean streamReadable) {
			if (streamReadable && this.streamEndedGlobal >= 0) {
				emitUnlatchEnded();
			}
			getLocal(1);
			WasmEmitHelper.castI31GetS(this.ctx);
			callImport();
			refNullEq();
			this.w.write(Instruction.END);
		}

		// stream.read into a staged chunk: the ASYNC (non-blocking) built-in; one chunk
		// per call, returning the bytes as a packed octet vector, or nil once the stream
		// is
		// dropped (EOF). The completion value is (count << 4) | status; a read
		// completes with at least one byte unless the writer is gone, so count 0 =
		// EOF. Outside asyncMode, and for a :blocking directive's read, a BLOCKED read
		// parks the whole task on the waitable-set; in asyncMode it returns a PENDING
		// future instead -- registered
		// with the scheduler (kind 1) and settled by the EVENT_STREAM_READ dispatch in
		// _sched_loop -- so other tasks' frames keep running while the chunk is in
		// flight. The chunk buffer must outlive this call, so it never comes from the
		// popped staging: it is recycled through the scheduler's free list (bounding
		// linear-memory growth by the maximum number of concurrent reads).
		// A HANDLE-element stream (handleElem: wasi:sockets' accept stream) reads ONE
		// 4-byte element instead of a byte chunk, lifts it as an opaque integer handle
		// (count 0 = EOF -> nil), and registers as kind 2 so the scheduler lifts it the
		// same way.
		// A completion whose status is DROPPED ends the stream even when it carries
		// elements (Dropped(n), n > 0: the last chunk and the writer's drop together),
		// and another stream.read of the handle traps -- so every completion path
		// latches the handle (streamEndedGlobal), and a read of a latched handle
		// answers nil without touching it.
		private void emitStreamRead(boolean handleElem, boolean blocking) {
			if (this.streamEndedGlobal >= 0) {
				emitEndedReadReturnsNil();
			}
			WasmFutureRuntimeBuilder.Sched wiring = this.sched;
			if (wiring == null || blocking) {
				emitStagingPrologue();
				int buf = allocRetArea(ASYNC_READ_CHUNK);
				int n = allocI32();
				int handle = allocI32();
				getLocal(1);
				WasmEmitHelper.castI31GetS(this.ctx);
				setLocal(handle);
				getLocal(handle);
				getLocal(buf);
				i32Const(handleElem ? 1 : ASYNC_READ_CHUNK);
				callImport();
				emitBlockedWait(handle);
				if (this.streamEndedGlobal >= 0) {
					int ret = allocI32();
					setLocal(ret);
					emitLatchIfDropped(ret, handle);
					getLocal(ret);
				}
				i32Const(4);
				this.w.write(Instruction.I32_SHR_U);
				this.w.write(Instruction.TEE_LOCAL);
				this.w.writeUnsignedLeb128(n);
				this.w.write(Instruction.IF);
				this.w.writeRefType(true, Type.EQ.code());
				emitReadLift(handleElem, buf, n);
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
			this.w.writeUnsignedLeb128(this.allocFuncIndex);
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
			i32Const(handleElem ? 1 : ASYNC_READ_CHUNK);
			callImport();
			setLocal(ret);
			getLocal(ret);
			i32Const(-1);
			this.w.write(Instruction.I32_EQ);
			this.w.write(Instruction.IF);
			this.w.writeRefType(true, Type.EQ.code());
			// BLOCKED: a fresh pending future, registered as
			// (handle . (kind . (future . (buf . nil)))) -- kind 1 = byte chunk (the
			// stream struct is attached by _wasi_stream_read), kind 2 = a handle
			// element -- with the handle joined into the task waitable-set (created
			// lazily).
			this.w.write(Instruction.CALL);
			this.w.writeUnsignedLeb128(this.ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_NEW);
			int fut = this.ctx.allocTemp();
			setLocal(fut);
			getLocal(handle);
			boxI31();
			i32Const(handleElem ? 2 : 1);
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
			// Completed immediately: lift the element (count 0 = EOF -> nil) and
			// recycle the buffer.
			if (this.streamEndedGlobal >= 0) {
				emitLatchIfDropped(ret, handle);
			}
			getLocal(ret);
			i32Const(4);
			this.w.write(Instruction.I32_SHR_U);
			this.w.write(Instruction.TEE_LOCAL);
			this.w.writeUnsignedLeb128(n);
			this.w.write(Instruction.IF);
			this.w.writeRefType(true, Type.EQ.code());
			emitReadLift(handleElem, buf, n);
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

		// stream.read of a stream of values (stream<directory-entry>, stream<row>): at
		// most
		// the count parameter 2 asks for, into a buffer of that many elements staged
		// above
		// the mark, the host lowering what each element carries through cabi_realloc. The
		// read WAITS where it is, a BLOCKED one parked on a waitable-set of its own as a
		// future read is, in asyncMode too, so everything the host lowered for it lies
		// above the mark when the elements are lifted -- a list, nil at the end -- and
		// the
		// epilogue pops it all: a reader's memory is one chunk, whatever the stream
		// holds.
		// A DROPPED completion latches the handle as the byte read's does.
		private void emitValueStreamRead(Async async) {
			WitType element = Objects.requireNonNull(async.element(), "stream element");
			if (this.streamEndedGlobal >= 0) {
				emitEndedReadReturnsNil();
			}
			emitStagingPrologue();
			int handle = allocI32();
			int max = allocI32();
			int buf = allocI32();
			int ret = allocI32();
			int n = allocI32();
			getLocal(1);
			WasmEmitHelper.castI31GetS(this.ctx);
			setLocal(handle);
			// max = the count asked for, at least one: a read of none is no read
			getLocal(2);
			WasmEmitHelper.castI31GetS(this.ctx);
			this.w.write(Instruction.TEE_LOCAL);
			this.w.writeUnsignedLeb128(max);
			i32Const(1);
			this.w.write(Instruction.I32_LT_S);
			this.w.write(Instruction.IF, 0x40);
			i32Const(1);
			setLocal(max);
			this.w.write(Instruction.END);
			getLocal(max);
			i32Const(async.abi().size(element));
			this.w.write(Instruction.I32_MUL);
			this.w.write(Instruction.CALL);
			this.w.writeUnsignedLeb128(this.allocFuncIndex);
			setLocal(buf);
			getLocal(handle);
			getLocal(buf);
			getLocal(max);
			callImport();
			emitBlockedWait(handle);
			setLocal(ret);
			if (this.streamEndedGlobal >= 0) {
				emitLatchIfDropped(ret, handle);
			}
			getLocal(ret);
			i32Const(4);
			this.w.write(Instruction.I32_SHR_U);
			setLocal(n);
			emitLiftElements(async.abi(), element, buf, n);
			int resultTmp = this.ctx.allocTemp();
			setLocal(resultTmp);
			emitStagingEpilogue(resultTmp);
		}

		// stream.cancel-read: withdraws the read an asyncMode stream.read above left in
		// flight (a BLOCKED read, registered as a pending future), so that the handle
		// can be read again or dropped -- a drop with a read in flight traps ("cannot
		// remove busy stream"). The SYNC built-in returns once the copy has settled,
		// with what it delivered: bytes the host had already copied (or a completion
		// that beat the cancel) settle the read's future as the completion event would
		// have, an empty withdrawal settles it nil, the staged buffer is recycled and a
		// DROPPED result latches the handle. The registry entry is unlinked and the
		// handle leaves the task waitable-set BEFORE the built-in runs: a synchronous
		// built-in traps on a joined waitable (measured, wasmtime 49), and nothing a
		// blocking cancel lets run can settle the entry twice. Answers the copy
		// result's status (0 completed, 1 dropped, 2 cancelled), or nil when no read
		// of the handle is in flight -- always so outside asyncMode, where a read
		// parks until it completes. Unlike the completion event it runs no close
		// protocol of an attached TYPE_WASI_STREAM: rontolisp:stream-read, the one
		// consumer that attaches one, never withdraws its read.
		private void emitStreamCancelRead(boolean handleElem) {
			WasmFutureRuntimeBuilder.Sched wiring = this.sched;
			if (wiring == null) {
				refNullEq();
				this.w.write(Instruction.END);
				return;
			}
			WaitOrdinals ordinals = Objects.requireNonNull(this.waitOrdinals,
					"async built-in wrapper emitted without waitable-set ordinals");
			int handle = allocI32();
			int ret = allocI32();
			int n = allocI32();
			int buf = allocI32();
			int prev = this.ctx.allocTemp();
			int cur = this.ctx.allocTemp();
			int entry = this.ctx.allocTemp();
			getLocal(1);
			WasmEmitHelper.castI31GetS(this.ctx);
			setLocal(handle);
			// Find the registry entry (handle . (kind . (future . (buf . stream)))) of
			// the handle; none means no read is in flight.
			refNullEq();
			setLocal(prev);
			globalGet(wiring.registryGlobal());
			setLocal(cur);
			this.w.write(Instruction.BLOCK, 0x40); // $found
			this.w.write(Instruction.LOOP, 0x40); // $walk
			getLocal(cur);
			this.w.write(Instruction.REF_IS_NULL);
			this.w.write(Instruction.IF, 0x40);
			refNullEq();
			this.w.write(Instruction.RETURN);
			this.w.write(Instruction.END);
			getLocal(cur);
			structGet(WasmLispCompiler.TYPE_CONS, 0);
			setLocal(entry);
			getLocal(entry);
			structGet(WasmLispCompiler.TYPE_CONS, 0);
			WasmEmitHelper.castI31GetS(this.ctx);
			getLocal(handle);
			this.w.write(Instruction.I32_EQ);
			this.w.write(Instruction.BR_IF);
			this.w.writeUnsignedLeb128(1); // -> $found
			getLocal(cur);
			setLocal(prev);
			getLocal(cur);
			structGet(WasmLispCompiler.TYPE_CONS, 1);
			setLocal(cur);
			this.w.write(Instruction.BR);
			this.w.writeUnsignedLeb128(0); // -> $walk
			this.w.write(Instruction.END); // loop $walk
			this.w.write(Instruction.END); // block $found
			// Unlink it.
			getLocal(prev);
			this.w.write(Instruction.REF_IS_NULL);
			this.w.write(Instruction.IF, 0x40);
			getLocal(cur);
			structGet(WasmLispCompiler.TYPE_CONS, 1);
			globalSet(wiring.registryGlobal());
			this.w.write(Instruction.ELSE);
			getLocal(prev);
			this.w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			this.w.writeHeapType(WasmLispCompiler.TYPE_CONS);
			getLocal(cur);
			structGet(WasmLispCompiler.TYPE_CONS, 1);
			this.w.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
			this.w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
			this.w.writeUnsignedLeb128(1);
			this.w.write(Instruction.END);
			// The handle leaves the set first -- the synchronous built-in traps on a
			// waitable that is still joined -- then the withdrawal.
			getLocal(handle);
			i32Const(0);
			callOrdinal(ordinals.join());
			getLocal(handle);
			callImport();
			setLocal(ret);
			// cur = (future . (buf . stream)); buf = the staged chunk buffer.
			getLocal(entry);
			structGet(WasmLispCompiler.TYPE_CONS, 1);
			structGet(WasmLispCompiler.TYPE_CONS, 1);
			setLocal(cur);
			getLocal(cur);
			structGet(WasmLispCompiler.TYPE_CONS, 1);
			structGet(WasmLispCompiler.TYPE_CONS, 0);
			WasmEmitHelper.castI31GetS(this.ctx);
			setLocal(buf);
			if (this.streamEndedGlobal >= 0) {
				emitLatchIfDropped(ret, handle);
			}
			// entry = what the read delivered (count 0 -> nil).
			getLocal(ret);
			i32Const(4);
			this.w.write(Instruction.I32_SHR_U);
			this.w.write(Instruction.TEE_LOCAL);
			this.w.writeUnsignedLeb128(n);
			this.w.write(Instruction.IF);
			this.w.writeRefType(true, Type.EQ.code());
			emitReadLift(handleElem, buf, n);
			this.w.write(Instruction.ELSE);
			refNullEq();
			this.w.write(Instruction.END);
			setLocal(entry);
			getLocal(buf);
			boxI31();
			globalGet(wiring.readFreeGlobal());
			newCons();
			globalSet(wiring.readFreeGlobal());
			getLocal(cur);
			structGet(WasmLispCompiler.TYPE_CONS, 0);
			getLocal(entry);
			this.w.write(Instruction.CALL);
			this.w.writeUnsignedLeb128(this.ctx.asyncFuncBase + WasmFutureRuntimeBuilder.OFF_SETTLE);
			this.w.write(Instruction.DROP);
			getLocal(ret);
			i32Const(0xF);
			this.w.write(Instruction.I32_AND);
			boxI31();
			this.w.write(Instruction.END);
		}

		// --- the ended-stream latch (streamEndedGlobal: a cons list of i31 handles) ---

		// Returns nil from the wrapper, before anything is staged, when the readable
		// end in parameter 1 is latched.
		private void emitEndedReadReturnsNil() {
			int handle = allocI32();
			int found = allocI32();
			int cur = this.ctx.allocTemp();
			getLocal(1);
			WasmEmitHelper.castI31GetS(this.ctx);
			setLocal(handle);
			i32Const(0);
			setLocal(found);
			globalGet(this.streamEndedGlobal);
			setLocal(cur);
			this.w.write(Instruction.BLOCK, 0x40); // $done
			this.w.write(Instruction.LOOP, 0x40); // $walk
			getLocal(cur);
			this.w.write(Instruction.REF_IS_NULL);
			this.w.write(Instruction.BR_IF);
			this.w.writeUnsignedLeb128(1);
			getLocal(cur);
			structGet(WasmLispCompiler.TYPE_CONS, 0);
			WasmEmitHelper.castI31GetS(this.ctx);
			getLocal(handle);
			this.w.write(Instruction.I32_EQ);
			this.w.write(Instruction.IF, 0x40);
			i32Const(1);
			setLocal(found);
			this.w.write(Instruction.BR);
			this.w.writeUnsignedLeb128(2);
			this.w.write(Instruction.END);
			getLocal(cur);
			structGet(WasmLispCompiler.TYPE_CONS, 1);
			setLocal(cur);
			this.w.write(Instruction.BR);
			this.w.writeUnsignedLeb128(0);
			this.w.write(Instruction.END); // loop $walk
			this.w.write(Instruction.END); // block $done
			getLocal(found);
			this.w.write(Instruction.IF, 0x40);
			refNullEq();
			this.w.write(Instruction.RETURN);
			this.w.write(Instruction.END);
		}

		// Latches the handle when the packed copy result in ret reports DROPPED.
		private void emitLatchIfDropped(int ret, int handle) {
			getLocal(ret);
			i32Const(0xF);
			this.w.write(Instruction.I32_AND);
			i32Const(COPY_RESULT_DROPPED);
			this.w.write(Instruction.I32_EQ);
			this.w.write(Instruction.IF, 0x40);
			getLocal(handle);
			boxI31();
			globalGet(this.streamEndedGlobal);
			newCons();
			globalSet(this.streamEndedGlobal);
			this.w.write(Instruction.END);
		}

		// Unlinks the readable end in parameter 1 from the latch (a no-op when it is
		// not there).
		private void emitUnlatchEnded() {
			int handle = allocI32();
			int prev = this.ctx.allocTemp();
			int cur = this.ctx.allocTemp();
			getLocal(1);
			WasmEmitHelper.castI31GetS(this.ctx);
			setLocal(handle);
			refNullEq();
			setLocal(prev);
			globalGet(this.streamEndedGlobal);
			setLocal(cur);
			this.w.write(Instruction.BLOCK, 0x40); // $done
			this.w.write(Instruction.LOOP, 0x40); // $walk
			getLocal(cur);
			this.w.write(Instruction.REF_IS_NULL);
			this.w.write(Instruction.BR_IF);
			this.w.writeUnsignedLeb128(1);
			getLocal(cur);
			structGet(WasmLispCompiler.TYPE_CONS, 0);
			WasmEmitHelper.castI31GetS(this.ctx);
			getLocal(handle);
			this.w.write(Instruction.I32_EQ);
			this.w.write(Instruction.IF, 0x40);
			getLocal(prev);
			this.w.write(Instruction.REF_IS_NULL);
			this.w.write(Instruction.IF, 0x40);
			getLocal(cur);
			structGet(WasmLispCompiler.TYPE_CONS, 1);
			globalSet(this.streamEndedGlobal);
			this.w.write(Instruction.ELSE);
			getLocal(prev);
			this.w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			this.w.writeHeapType(WasmLispCompiler.TYPE_CONS);
			getLocal(cur);
			structGet(WasmLispCompiler.TYPE_CONS, 1);
			this.w.write(Instruction.GC_PREFIX, Instruction.STRUCT_SET);
			this.w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
			this.w.writeUnsignedLeb128(1);
			this.w.write(Instruction.END);
			this.w.write(Instruction.BR);
			this.w.writeUnsignedLeb128(2);
			this.w.write(Instruction.END);
			getLocal(cur);
			setLocal(prev);
			getLocal(cur);
			structGet(WasmLispCompiler.TYPE_CONS, 1);
			setLocal(cur);
			this.w.write(Instruction.BR);
			this.w.writeUnsignedLeb128(0);
			this.w.write(Instruction.END); // loop $walk
			this.w.write(Instruction.END); // block $done
		}

		// The completed-read lift: a byte chunk becomes a packed (unsigned-byte 8)
		// vector -- the octets exactly as read, no decode, so an HTTP body stream is a
		// BYTE stream (a relayed reply crosses exactly; rontolisp:read-all decodes) and a
		// socket's chunk buffer walks bytes with aref; a handle element (4 bytes, count
		// 1) becomes the opaque integer handle.
		private void emitReadLift(boolean handleElem, int buf, int n) {
			if (handleElem) {
				getLocal(buf);
				this.w.write(Instruction.I32_LOAD, 0x02);
				this.w.writeUnsignedLeb128(0);
				boxI31();
				return;
			}
			if (this.bytesFromMemFuncIndex < 0) {
				throw new IllegalStateException(
						"'" + this.lispName + "': byte-stream read emitted without the _bytes_from_mem helper");
			}
			getLocal(buf);
			getLocal(n);
			this.w.write(Instruction.CALL);
			this.w.writeUnsignedLeb128(this.bytesFromMemFuncIndex);
		}

		// stream.write of a whole Lisp payload: the async built-in plus the BLOCKED
		// park, so one call carries the whole payload (the rendezvous the sync variant
		// used to provide). Returns the accepted byte count. A stream<u8> takes a
		// packed (unsigned-byte 8) vector as its raw octets, which is what carries a
		// BINARY response body out of a serve component unencoded.
		private void emitStreamWrite(boolean handleElement) {
			emitStagingPrologue();
			int handle = allocI32();
			getLocal(1);
			WasmEmitHelper.castI31GetS(this.ctx);
			setLocal(handle);
			getLocal(handle);
			if (handleElement) {
				emitStageStringParam(2);
			}
			else {
				emitStageBytesParam(2);
			}
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
			this.w.writeRefType(true, Type.EQ.code());
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

		// future.write that does not wait: (writable value region capacity). The value
		// is lowered above the mark and written; a write the reader has not taken yet
		// (BLOCKED) stays pending, what was lowered KEPT past this wrapper -- the host
		// copies it whenever it reads -- and the wrapper answers (address . size), a
		// region the caller owns until the write has settled (WRITE_SETTLE). A caller
		// holding a region a settled write left passes it with its capacity (nil for
		// none): where the value fits, it is lowered a second time there, what the first
		// pass staged is popped, and the wrapper answers t, so writes one after another
		// reuse one region. The first pass is what measures the value: a payload's
		// strings and lists are staged above its fixed area, so its size is known only
		// once it is lowered. A write that completed at once -- the reader took the
		// value, or had gone -- answers nil with nothing kept.
		private void emitFutureWriteStart(Async async) {
			WitType payload = Objects.requireNonNull(async.payload(), "future payload");
			emitStagingPrologue();
			int handle = allocI32();
			int second = allocI32();
			int size = allocI32();
			int ret = allocI32();
			getLocal(1);
			WasmEmitHelper.castI31GetS(this.ctx);
			setLocal(handle);
			this.w.write(Instruction.LOOP, 0x40); // $lower
			int rp = allocRetArea(async.abi().size(payload));
			emitLowerAt(async.abi(), payload, 2, rp, 0);
			getLocal(second);
			this.w.write(Instruction.I32_EQZ);
			this.w.write(Instruction.IF, 0x40);
			loadCell(WasmLispCompiler.HEAP_PTR_ADDR);
			getLocal(this.mark);
			this.w.write(Instruction.I32_SUB);
			setLocal(size);
			getLocal(3);
			this.w.write(Instruction.REF_IS_NULL);
			this.w.write(Instruction.I32_EQZ);
			this.w.write(Instruction.IF, 0x40);
			getLocal(size);
			getLocal(4);
			WasmEmitHelper.castI31GetS(this.ctx);
			this.w.write(Instruction.I32_LE_U);
			this.w.write(Instruction.IF, 0x40);
			i32Const(WasmLispCompiler.HEAP_PTR_ADDR);
			getLocal(3);
			WasmEmitHelper.castI31GetS(this.ctx);
			this.w.write(Instruction.I32_STORE, 0x02, 0x00);
			i32Const(1);
			setLocal(second);
			this.w.write(Instruction.BR);
			this.w.writeUnsignedLeb128(3); // -> $lower
			this.w.write(Instruction.END);
			this.w.write(Instruction.END);
			this.w.write(Instruction.END);
			this.w.write(Instruction.END); // loop $lower
			// the value lies in the caller's region: what the first pass staged goes
			getLocal(second);
			this.w.write(Instruction.IF, 0x40);
			i32Const(WasmLispCompiler.HEAP_PTR_ADDR);
			getLocal(this.mark);
			this.w.write(Instruction.I32_STORE, 0x02, 0x00);
			this.w.write(Instruction.END);
			getLocal(handle);
			getLocal(rp);
			callImport();
			setLocal(ret);
			getLocal(ret);
			i32Const(-1);
			this.w.write(Instruction.I32_EQ);
			getLocal(second);
			this.w.write(Instruction.I32_EQZ);
			this.w.write(Instruction.I32_AND);
			this.w.write(Instruction.IF, 0x40);
			// pending in what this call staged: kept, the staging not popped
			getLocal(rp);
			boxI31();
			getLocal(size);
			boxI31();
			newCons();
			this.w.write(Instruction.RETURN);
			this.w.write(Instruction.END);
			getLocal(ret);
			i32Const(-1);
			this.w.write(Instruction.I32_EQ);
			WasmEmitHelper.emitBoolFromI32(this.ctx);
			int resultTmp = this.ctx.allocTemp();
			setLocal(resultTmp);
			emitStagingEpilogue(resultTmp);
		}

		// What became of a write WRITE_START left pending: (writable address). The
		// synchronous future.cancel-write answers what the write completed with -- the
		// reader took the value, or dropped its end -- and the wrapper answers t: the
		// writable end may be dropped, the region reused. A write still pending is
		// withdrawn by it (CANCELLED), since the built-in has no way to ask without
		// withdrawing, so it is written again from where it lies; nil while the reader
		// has still not come. The end is in no waitable-set, which a synchronous
		// built-in needs.
		private void emitFutureWriteSettle() {
			int handle = allocI32();
			getLocal(1);
			WasmEmitHelper.castI31GetS(this.ctx);
			setLocal(handle);
			getLocal(handle);
			callImport();
			i32Const(0xF);
			this.w.write(Instruction.I32_AND);
			i32Const(COPY_RESULT_CANCELLED);
			this.w.write(Instruction.I32_EQ);
			this.w.write(Instruction.IF);
			this.w.writeRefType(true, Type.EQ.code());
			getLocal(handle);
			getLocal(2);
			WasmEmitHelper.castI31GetS(this.ctx);
			callOrdinal(this.writeOrdinal);
			i32Const(-1);
			this.w.write(Instruction.I32_NE);
			WasmEmitHelper.emitBoolFromI32(this.ctx);
			this.w.write(Instruction.ELSE);
			i32Const(1);
			WasmEmitHelper.emitBoolFromI32(this.ctx);
			this.w.write(Instruction.END);
			this.w.write(Instruction.END);
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
			this.w.writeUnsignedLeb128(this.mark);
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
			this.w.writeUnsignedLeb128(this.allocFuncIndex);
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
						case "s8", "u8", "s16", "u16" -> lowerNarrow(slot, prim.name());
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
						case "s64" -> lowerI64(slot, true);
						case "u64" -> lowerI64(slot, false);
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
				case WitType.ListOf list when abi.isU8(list.element()) -> emitStageBytesParam(slot);
				case WitType.ListOf list -> emitStageListParam(abi, list.element(), slot);
				case WitType.BorrowOf borrow -> emitLowerHandle(abi, borrow.resource(), slot);
				case WitType.OwnOf own -> emitLowerHandle(abi, own.resource(), slot);
				// A stream/future crosses as its bare i32 end handle; the async built-ins
				// (not this lowering) are what read/write through it.
				case WitType.StreamOf ignored -> emitLowerHandleParam(slot);
				case WitType.FutureOf ignored -> emitLowerHandleParam(slot);
				case WitType.OptionOf ignored -> emitLowerVariantParam(abi, t, slot);
				case WitType.ResultOf ignored -> emitLowerVariantParam(abi, t, slot);
				case WitType.TupleOf ignored -> emitLowerRecordParam(abi, t, slot, false);
				case WitType.Named named -> {
					switch (abi.resolveNamed(named)) {
						case WitItem.ResourceDef ignored -> emitLowerHandle(abi, named.name(), slot);
						case WitItem.EnumDef ignored -> emitLowerVariantParam(abi, t, slot);
						case WitItem.VariantDef ignored -> emitLowerVariantParam(abi, t, slot);
						case WitItem.RecordDef ignored -> emitLowerRecordParam(abi, t, slot, true);
						case WitItem.FlagsDef flags -> emitLowerFlags(flags, slot);
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

		// --- handles of a resource the component itself defines (an exported one) ---

		// The built-ins of the resource a handle in an export's signature is of, when
		// the export's own component defines it: the value behind such a handle is the
		// program's, kept in the slot store (wit-resource.lisp). Null for a handle
		// that is the integer it crosses as: every handle of an import's wrapper, and
		// of a resource the component imports.
		private ExportHandles.@Nullable Resource exported(WitCanonicalAbi abi, String resource) {
			ExportHandles handles = this.exportHandles;
			if (handles == null) {
				return null;
			}
			WitResolver.Owned owned = abi.resolveOwned(new WitType.Named(resource));
			return handles.of(owned.owner(), WitComponentTypeEncoder.definedNameOf(owned.item()));
		}

		// Pushes the i32 an owned handle of the value in the slot is: a handle made
		// over a fresh representation of the value. (No function answers a borrowed
		// handle, so an exported resource's handle is always owned here.)
		private void emitLowerHandle(WitCanonicalAbi abi, String resource, int slot) {
			ExportHandles.Resource builtins = exported(abi, resource);
			if (builtins == null) {
				emitLowerHandleParam(slot);
				return;
			}
			this.w.write(Instruction.REF_NULL);
			this.w.writeHeapType(Type.EQ.code());
			getLocal(slot);
			this.w.write(Instruction.CALL);
			this.w.writeUnsignedLeb128(java.util.Objects.requireNonNull(this.exportHandles).repNew());
			WasmEmitHelper.castI31GetS(this.ctx);
			this.w.write(Instruction.CALL);
			this.w.writeUnsignedLeb128(WasmImportCompiler.PLACEHOLDER_FUNC_BASE + builtins.resourceNew());
		}

		private void emitStoreResourceAt(WitCanonicalAbi abi, String resource, int slot, int base, int offset) {
			getLocal(base);
			emitLowerHandle(abi, resource, slot);
			store(offset, Instruction.I32_STORE, 2);
		}

		// Turns the i32 on the stack -- a handle as the host lowered it -- into the
		// boxed value. A borrowed handle of the component's own resource arrives as
		// its representation; an owned one as a handle in this instance's table, which
		// is read for its representation and released once the value is out of the
		// store (the destructor that release calls finds the slot taken).
		private void emitLiftHandle(WitCanonicalAbi abi, String resource, boolean borrowed) {
			ExportHandles.Resource builtins = exported(abi, resource);
			if (builtins == null) {
				if (this.export && borrowed) {
					emitRecordBorrow(abi, resource);
				}
				boxI31();
				return;
			}
			ExportHandles handles = java.util.Objects.requireNonNull(this.exportHandles);
			int boxed = this.ctx.allocTemp();
			if (borrowed) {
				boxI31();
				setLocal(boxed);
				this.w.write(Instruction.REF_NULL);
				this.w.writeHeapType(Type.EQ.code());
				getLocal(boxed);
				this.w.write(Instruction.CALL);
				this.w.writeUnsignedLeb128(handles.repValue());
				return;
			}
			int save = this.i32Cursor;
			int handle = allocI32();
			setLocal(handle);
			getLocal(handle);
			this.w.write(Instruction.CALL);
			this.w.writeUnsignedLeb128(WasmImportCompiler.PLACEHOLDER_FUNC_BASE + builtins.resourceRep());
			boxI31();
			setLocal(boxed);
			this.w.write(Instruction.REF_NULL);
			this.w.writeHeapType(Type.EQ.code());
			getLocal(boxed);
			this.w.write(Instruction.CALL);
			this.w.writeUnsignedLeb128(handles.repTake());
			getLocal(handle);
			this.w.write(Instruction.CALL);
			this.w.writeUnsignedLeb128(WasmImportCompiler.PLACEHOLDER_FUNC_BASE + builtins.resourceDrop());
			this.i32Cursor = save;
		}

		// --- handles of a resource the component imports, lent to an export ---

		// Conses the i32 on the stack -- a borrowed handle of a resource another
		// component implements, which the host put in this instance's table for the
		// call -- onto the list of its resource's drop, leaving it on the stack.
		private void emitRecordBorrow(WitCanonicalAbi abi, String resource) {
			WitResolver.Owned owned = abi.resolveOwned(new WitType.Named(resource));
			String key = abi.resolver().canonicalId(owned.owner()) + "#"
					+ WitComponentTypeEncoder.definedNameOf(owned.item());
			Integer drop = this.importedDrops.get(key);
			if (drop == null) {
				throw new IllegalStateException("'" + this.lispName + "' borrows the resource '" + key
						+ "', whose drop the component does not import");
			}
			int list = this.borrowLists.computeIfAbsent(drop, ordinal -> this.ctx.allocTemp());
			int save = this.i32Cursor;
			int handle = allocI32();
			setLocal(handle);
			getLocal(handle);
			boxI31();
			getLocal(list);
			newCons();
			setLocal(list);
			getLocal(handle);
			this.i32Cursor = save;
		}

		// Drops every handle the lift recorded, through its resource's drop: a loop per
		// list, which the locals alone drive.
		void emitReleaseBorrows() {
			for (java.util.Map.Entry<Integer, Integer> borrows : this.borrowLists.entrySet()) {
				int list = borrows.getValue();
				this.w.write(Instruction.BLOCK, 0x40);
				this.w.write(Instruction.LOOP, 0x40);
				getLocal(list);
				this.w.write(Instruction.REF_IS_NULL);
				this.w.write(Instruction.BR_IF);
				this.w.writeUnsignedLeb128(1);
				getLocal(list);
				structGet(WasmLispCompiler.TYPE_CONS, 0);
				WasmEmitHelper.castI31GetS(this.ctx);
				callOrdinal(borrows.getKey());
				getLocal(list);
				structGet(WasmLispCompiler.TYPE_CONS, 1);
				setLocal(list);
				this.w.write(Instruction.BR);
				this.w.writeUnsignedLeb128(0);
				this.w.write(Instruction.END);
				this.w.write(Instruction.END);
			}
		}

		// Flags: a list of keywords, one per label that is set, against the i32 whose
		// bit i is label i (the canonical ABI's packing, in memory and flattened). Leaves
		// the i32 of the list in the slot on the stack. A keyword that names no label
		// traps, as one that names no case of a variant does, and so does anything but
		// a list; a label named twice is set once.
		private void emitLowerFlags(WitItem.FlagsDef flags, int slot) {
			int bits = allocI32();
			int rest = this.ctx.allocTemp();
			int label = this.ctx.allocTemp();
			i32Const(0);
			setLocal(bits);
			getLocal(slot);
			setLocal(rest);
			this.w.write(Instruction.BLOCK, 0x40);
			this.w.write(Instruction.LOOP, 0x40);
			getLocal(rest);
			this.w.write(Instruction.REF_IS_NULL);
			this.w.write(Instruction.BR_IF);
			this.w.writeUnsignedLeb128(1);
			getLocal(rest);
			structGet(WasmLispCompiler.TYPE_CONS, 0);
			setLocal(label);
			int id = emitTagId(label);
			int labels = flags.cases().size();
			for (int i = 0; i < labels; i++) {
				getLocal(id);
				i32Const(this.ctx.stringTable
					.addString(":" + flags.cases().get(i).name().toUpperCase(java.util.Locale.ROOT))
					.offset());
				this.w.write(Instruction.I32_EQ);
				this.w.write(Instruction.IF, 0x40);
				getLocal(bits);
				i32Const(1 << i);
				this.w.write(Instruction.I32_OR);
				setLocal(bits);
				this.w.write(Instruction.ELSE);
			}
			this.w.write(Instruction.UNREACHABLE);
			for (int i = 0; i < labels; i++) {
				this.w.write(Instruction.END);
			}
			getLocal(rest);
			structGet(WasmLispCompiler.TYPE_CONS, 1);
			setLocal(rest);
			this.w.write(Instruction.BR);
			this.w.writeUnsignedLeb128(0);
			this.w.write(Instruction.END);
			this.w.write(Instruction.END);
			getLocal(bits);
		}

		// The flags the i32 on the stack holds: the keywords of its set labels, in label
		// order. A bit past the labels is not read (the canonical ABI's rule).
		private void emitLiftFlags(WitItem.FlagsDef flags) {
			int bits = allocI32();
			int list = this.ctx.allocTemp();
			setLocal(bits);
			refNullEq();
			setLocal(list);
			for (int i = flags.cases().size() - 1; i >= 0; i--) {
				getLocal(bits);
				i32Const(1 << i);
				this.w.write(Instruction.I32_AND);
				this.w.write(Instruction.IF, 0x40);
				WasmEmitHelper.compileStringLiteral(
						":" + flags.cases().get(i).name().toUpperCase(java.util.Locale.ROOT), this.ctx);
				getLocal(list);
				newCons();
				setLocal(list);
				this.w.write(Instruction.END);
			}
			getLocal(list);
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
					// The tag reads upcased (:GET): a source keyword upcases under the
					// reader's upcase premise, and a lifted variant case spells itself
					// upcased too, so a lifted value passes straight back here.
					getLocal(tagId);
					i32Const(
							this.ctx.stringTable.addString(":" + info.names().get(i).toUpperCase(java.util.Locale.ROOT))
								.offset());
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
			this.w.writeRefType(true, Type.EQ.code());
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
			this.w.writeRefType(true, Type.EQ.code());
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
					// _plist_get(plist, ":FIELD") -- nil when the key is absent, which is
					// what an option field wants. The key reads upcased -- a user plist
					// and
					// a lifted record both upcase their keys under the reader's upcase
					// premise.
					i32Const(
							this.ctx.stringTable.addString(":" + info.names().get(i).toUpperCase(java.util.Locale.ROOT))
								.offset());
					this.w.write(Instruction.CALL);
					this.w.writeUnsignedLeb128(WasmLispCompiler.FUNC_PLIST_GET);
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

		// Stages a `list<u8>`-typed argument -- a WIT list of bytes, which is what a
		// `stream<u8>` write carries too. A packed (unsigned-byte 8) vector crosses as
		// the RAW octets it holds; anything else is the string spelling of a byte
		// string (`.kb/wit.md`, "The settled type mapping") and stages exactly as a
		// `string`
		// parameter does. The distinction matters because the string staging UTF-8
		// ENCODES: without this arm every octet >= 0x80 would double on the way out.
		private void emitStageBytesParam(int slot) {
			int ptr = allocI32();
			int len = allocI32();
			int idx = allocI32();
			getLocal(slot);
			this.w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			this.w.writeHeapType(WasmLispCompiler.TYPE_I8ARR);
			this.w.write(Instruction.IF, 0x40);
			// dst = HEAP_PTR; len = array.len(v); grow-guard to dst + len
			loadCell(WasmLispCompiler.HEAP_PTR_ADDR);
			setLocal(ptr);
			getLocal(slot);
			this.w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			this.w.writeHeapType(WasmLispCompiler.TYPE_I8ARR);
			this.w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
			setLocal(len);
			WasmEmitHelper.emitGrowHeapTo(this.w, () -> {
				getLocal(ptr);
				getLocal(len);
				this.w.write(Instruction.I32_ADD);
			});
			// idx = 0; while (idx < len) mem[ptr + idx] = v[idx]; idx++
			i32Const(0);
			setLocal(idx);
			this.w.write(Instruction.BLOCK, 0x40);
			this.w.write(Instruction.LOOP, 0x40);
			getLocal(idx);
			getLocal(len);
			this.w.write(Instruction.I32_GE_U);
			this.w.write(Instruction.BR_IF);
			this.w.writeUnsignedLeb128(1);
			getLocal(ptr);
			getLocal(idx);
			this.w.write(Instruction.I32_ADD);
			getLocal(slot);
			this.w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
			this.w.writeHeapType(WasmLispCompiler.TYPE_I8ARR);
			getLocal(idx);
			this.w.write(Instruction.GC_PREFIX, Instruction.ARRAY_GET_U);
			this.w.writeUnsignedLeb128(WasmLispCompiler.TYPE_I8ARR);
			this.w.write(Instruction.I32_STORE8, 0x00, 0x00);
			getLocal(idx);
			i32Const(1);
			this.w.write(Instruction.I32_ADD);
			setLocal(idx);
			this.w.write(Instruction.BR);
			this.w.writeUnsignedLeb128(0);
			this.w.write(Instruction.END); // loop
			this.w.write(Instruction.END); // block
			// HEAP_PTR = align8(ptr + len)
			i32Const(WasmLispCompiler.HEAP_PTR_ADDR);
			getLocal(ptr);
			getLocal(len);
			this.w.write(Instruction.I32_ADD);
			i32Const(7);
			this.w.write(Instruction.I32_ADD);
			i32Const(-8);
			this.w.write(Instruction.I32_AND);
			this.w.write(Instruction.I32_STORE, 0x02, 0x00);
			this.w.write(Instruction.ELSE);
			emitStageStringParam(slot);
			setLocal(len);
			setLocal(ptr);
			this.w.write(Instruction.END);
			getLocal(ptr);
			getLocal(len);
		}

		// Stages a Lisp string / byte string into linear memory (advancing HEAP_PTR so a
		// later staged parameter cannot clobber it -- the epilogue pops the whole
		// region) and pushes its canonical (content ptr, content len) pair.
		private void emitStageStringParam(int slot) {
			int dst = allocI32();
			int total = allocI32();
			// A mutable character vector (a subseq/copy-seq result, e.g. the URL parts
			// the fetch library slices out) renders once here; any other value passes
			// through _charvec_to_str unchanged.
			getLocal(slot);
			WasmEmitHelper.emitCharvecToStrCall(this.ctx);
			setLocal(slot);
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

		// Stages a `list<T>` argument (T other than u8) -- a proper list, the shape
		// emitLiftList builds; the binding's Lisp wrapper has already turned a vector
		// into one and signalled type-error for anything else (rontolisp::%wit-coerce)
		// -- as a canonical array and pushes
		// its (ptr, len) pair:
		// one walk counts the elements, the array is bump-allocated (__ronto_alloc's
		// 8-byte alignment satisfies every element's), and a second walk stores each
		// element at its slot through emitLowerAt. An element that stages bytes of its
		// own (a string, a nested list) stages them above the array; the epilogue pops
		// both with the rest of the call's staging.
		private void emitStageListParam(WitCanonicalAbi abi, WitType element, int slot) {
			int len = allocI32();
			int ptr = allocI32();
			int elemBase = allocI32();
			int cur = this.ctx.allocTemp();
			int item = this.ctx.allocTemp();
			int elemSize = abi.size(element);
			// len = length(list)
			i32Const(0);
			setLocal(len);
			getLocal(slot);
			setLocal(cur);
			this.w.write(Instruction.BLOCK, 0x40);
			this.w.write(Instruction.LOOP, 0x40);
			getLocal(cur);
			refTest(WasmLispCompiler.TYPE_CONS);
			this.w.write(Instruction.I32_EQZ);
			this.w.write(Instruction.BR_IF);
			this.w.writeUnsignedLeb128(1);
			getLocal(len);
			i32Const(1);
			this.w.write(Instruction.I32_ADD);
			setLocal(len);
			getLocal(cur);
			structGet(WasmLispCompiler.TYPE_CONS, 1);
			setLocal(cur);
			this.w.write(Instruction.BR);
			this.w.writeUnsignedLeb128(0);
			this.w.write(Instruction.END);
			this.w.write(Instruction.END);
			// Anything but a proper list (only a raw binding a front end calls itself
			// can see one) traps, as every other type error here does -- not an empty
			// array.
			getLocal(cur);
			this.w.write(Instruction.REF_IS_NULL);
			this.w.write(Instruction.I32_EQZ);
			this.w.write(Instruction.IF, 0x40);
			this.w.write(Instruction.UNREACHABLE);
			this.w.write(Instruction.END);
			// ptr = elemBase = __ronto_alloc(len * size)
			getLocal(len);
			i32Const(elemSize);
			this.w.write(Instruction.I32_MUL);
			this.w.write(Instruction.CALL);
			this.w.writeUnsignedLeb128(this.allocFuncIndex);
			this.w.write(Instruction.TEE_LOCAL);
			this.w.writeUnsignedLeb128(ptr);
			setLocal(elemBase);
			// while (consp cur) { lower (car cur) at elemBase; elemBase += size; cur =
			// cdr }
			getLocal(slot);
			setLocal(cur);
			this.w.write(Instruction.BLOCK, 0x40);
			this.w.write(Instruction.LOOP, 0x40);
			getLocal(cur);
			refTest(WasmLispCompiler.TYPE_CONS);
			this.w.write(Instruction.I32_EQZ);
			this.w.write(Instruction.BR_IF);
			this.w.writeUnsignedLeb128(1);
			getLocal(cur);
			structGet(WasmLispCompiler.TYPE_CONS, 0);
			setLocal(item);
			int save32 = this.i32Cursor;
			int save64 = this.i64Cursor;
			emitLowerAt(abi, element, item, elemBase, 0);
			this.i32Cursor = save32;
			this.i64Cursor = save64;
			getLocal(elemBase);
			i32Const(elemSize);
			this.w.write(Instruction.I32_ADD);
			setLocal(elemBase);
			getLocal(cur);
			structGet(WasmLispCompiler.TYPE_CONS, 1);
			setLocal(cur);
			this.w.write(Instruction.BR);
			this.w.writeUnsignedLeb128(0);
			this.w.write(Instruction.END);
			this.w.write(Instruction.END);
			getLocal(ptr);
			getLocal(len);
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
							lowerNarrow(slot, prim.name());
							store(offset, Instruction.I32_STORE8, 0);
						}
						case "s16", "u16" -> {
							getLocal(base);
							lowerNarrow(slot, prim.name());
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
							lowerI64(slot, true);
							store(offset, Instruction.I64_STORE, 3);
						}
						case "u64" -> {
							getLocal(base);
							lowerI64(slot, false);
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
						case "string" -> emitStoreStringAt(slot, base, offset, false);
						default -> throw paramUnsupported(prim.name());
					}
				}
				case WitType.ListOf list when abi.isU8(list.element()) -> emitStoreStringAt(slot, base, offset, true);
				case WitType.ListOf list -> emitStoreListAt(abi, list.element(), slot, base, offset);
				case WitType.BorrowOf borrow -> emitStoreResourceAt(abi, borrow.resource(), slot, base, offset);
				case WitType.OwnOf own -> emitStoreResourceAt(abi, own.resource(), slot, base, offset);
				case WitType.StreamOf ignored -> emitStoreHandleAt(slot, base, offset);
				case WitType.FutureOf ignored -> emitStoreHandleAt(slot, base, offset);
				case WitType.OptionOf ignored -> emitLowerVariantAt(abi, t, slot, base, offset);
				case WitType.ResultOf ignored -> emitLowerVariantAt(abi, t, slot, base, offset);
				case WitType.TupleOf ignored -> emitLowerRecordAt(abi, t, slot, base, offset, false);
				case WitType.Named named -> {
					switch (abi.resolveNamed(named)) {
						case WitItem.ResourceDef ignored -> emitStoreResourceAt(abi, named.name(), slot, base, offset);
						case WitItem.EnumDef ignored -> emitLowerVariantAt(abi, t, slot, base, offset);
						case WitItem.VariantDef ignored -> emitLowerVariantAt(abi, t, slot, base, offset);
						case WitItem.RecordDef ignored -> emitLowerRecordAt(abi, t, slot, base, offset, true);
						case WitItem.FlagsDef flags -> {
							int size = WitCanonicalAbi.flagsSize(flags);
							getLocal(base);
							emitLowerFlags(flags, slot);
							store(offset, size == 1 ? Instruction.I32_STORE8
									: size == 2 ? Instruction.I32_STORE16 : Instruction.I32_STORE, size >> 1);
						}
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
		// store the canonical (content ptr @+0, content len @+4) pair. `bytes` marks the
		// list<u8> spelling, which additionally takes a packed (unsigned-byte 8) vector
		// as its raw octets.
		private void emitStoreStringAt(int slot, int base, int offset, boolean bytes) {
			int save = this.i32Cursor;
			int ptr = allocI32();
			int len = allocI32();
			if (bytes) {
				emitStageBytesParam(slot);
			}
			else {
				emitStageStringParam(slot);
			}
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

		// list<T> (T other than u8) at [base + offset]: stage the array and store its
		// canonical (ptr @+0, len @+4) pair.
		private void emitStoreListAt(WitCanonicalAbi abi, WitType element, int slot, int base, int offset) {
			int save = this.i32Cursor;
			int ptr = allocI32();
			int len = allocI32();
			emitStageListParam(abi, element, slot);
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
				// The tag reads upcased (:GET): a source keyword upcases under the
				// reader's upcase premise, and a lifted variant case spells itself
				// upcased too, so a lifted value passes straight back here.
				getLocal(tagId);
				i32Const(this.ctx.stringTable.addString(":" + info.names().get(i).toUpperCase(java.util.Locale.ROOT))
					.offset());
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
					// _plist_get(plist, ":FIELD") -- nil when the key is absent, which is
					// what an option field wants. The key reads upcased -- a user plist
					// and
					// a lifted record both upcase their keys under the reader's upcase
					// premise.
					i32Const(
							this.ctx.stringTable.addString(":" + info.names().get(i).toUpperCase(java.util.Locale.ROOT))
								.offset());
					this.w.write(Instruction.CALL);
					this.w.writeUnsignedLeb128(WasmLispCompiler.FUNC_PLIST_GET);
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
						case "s8", "u8", "s16", "u16" -> boxI31();
						case "s32" -> {
							if (this.export) {
								// the whole declared range, where an i31 holds 31 bits of
								// it
								this.w.write(Instruction.I64_EXTEND_S_I32);
								boxI64(true);
							}
							else {
								boxI31();
							}
						}
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
				case WitType.BorrowOf borrow -> emitLiftHandle(abi, borrow.resource(), true);
				case WitType.OwnOf own -> emitLiftHandle(abi, own.resource(), false);
				case WitType.StreamOf ignored -> boxI31();
				case WitType.FutureOf ignored -> boxI31();
				case WitType.ResultOf ignored -> emitLiftVariantFromStackDisc(abi, t);
				case WitType.OptionOf ignored -> emitLiftVariantFromStackDisc(abi, t);
				case WitType.TupleOf ignored -> emitLiftRecordFlat(abi, t, false);
				case WitType.Named named -> {
					switch (abi.resolveNamed(named)) {
						case WitItem.ResourceDef ignored -> emitLiftHandle(abi, named.name(), false);
						case WitItem.EnumDef ignored -> emitLiftVariantFromStackDisc(abi, t);
						case WitItem.VariantDef ignored -> emitLiftVariantFromStackDisc(abi, t);
						case WitItem.RecordDef ignored -> emitLiftRecordFlat(abi, t, true);
						case WitItem.FlagsDef flags -> emitLiftFlags(flags);
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
					WasmEmitHelper.compileStringLiteral(":" + info.names().get(i).toUpperCase(java.util.Locale.ROOT),
							this.ctx);
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
			emitVariantDispatch(abi, type, disc, (in, payload) -> {
				throw new IllegalStateException("a payload-bearing case cannot be single-flat");
			});
		}

		// Lifts one case's payload -- out of memory, or out of the joined flats -- and
		// leaves it boxed on the stack.
		@FunctionalInterface
		private interface PayloadLift {

			void lift(WitCanonicalAbi abi, WitType payload);

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
				case WitType.ListOf list when abi.isU8(list.element()) -> emitLiftFromMem(base, offset,
						this.octetsFromMemFuncIndex < 0 ? this.strFromMemFuncIndex : this.octetsFromMemFuncIndex);
				case WitType.ListOf list -> emitLiftList(abi, list.element(), base, offset);
				case WitType.BorrowOf borrow -> {
					load(base, offset, Instruction.I32_LOAD, 2);
					emitLiftHandle(abi, borrow.resource(), true);
				}
				case WitType.OwnOf own -> {
					load(base, offset, Instruction.I32_LOAD, 2);
					emitLiftHandle(abi, own.resource(), false);
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
							emitLiftHandle(abi, named.name(), false);
						}
						case WitItem.RecordDef ignored -> emitLiftRecordAt(abi, t, base, offset, true);
						case WitItem.VariantDef ignored -> emitLiftVariantAt(abi, t, base, offset);
						case WitItem.EnumDef ignored -> emitLiftVariantAt(abi, t, base, offset);
						case WitItem.FlagsDef flags -> {
							int size = WitCanonicalAbi.flagsSize(flags);
							load(base, offset, size == 1 ? Instruction.I32_LOAD8_U
									: size == 2 ? Instruction.I32_LOAD16_U : Instruction.I32_LOAD, size >> 1);
							emitLiftFlags(flags);
						}
						default -> throw resultUnsupported(named.name());
					}
				}
				default -> throw resultUnsupported(t.getClass().getSimpleName());
			}
		}

		// string / list<u8> at [base + offset]: (ptr @+0, byte len @+4) -> a fresh Lisp
		// string over the host-written bytes.
		private void emitLiftString(int base, int offset) {
			emitLiftFromMem(base, offset, this.strFromMemFuncIndex);
		}

		// (ptr @+0, byte len @+4) at [base + offset] -> what the helper makes of the
		// host-written bytes: _str_from_mem's string, _bytes_from_mem's octet vector.
		private void emitLiftFromMem(int base, int offset, int helperFuncIndex) {
			load(base, offset, Instruction.I32_LOAD, 2);
			load(base, offset + 4, Instruction.I32_LOAD, 2);
			this.w.write(Instruction.CALL);
			this.w.writeUnsignedLeb128(helperFuncIndex);
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
			int payloadOffset = offset + info.payloadOffset();
			emitVariantDispatch(abi, type, disc, (in, payload) -> emitLiftAt(in, payload, base, payloadOffset));
		}

		// Emits the if-chain over a variant's discriminant local; the lifter reads a
		// case's payload from wherever this variant's lies.
		private void emitVariantDispatch(WitCanonicalAbi abi, WitType type, int discLocal, PayloadLift payloadLift) {
			WitCanonicalAbi.VariantInfo info = abi.variantInfo(type);
			int n = info.names().size();
			for (int i = 0; i < n - 1; i++) {
				getLocal(discLocal);
				i32Const(i);
				this.w.write(Instruction.I32_EQ);
				this.w.write(Instruction.IF);
				this.w.writeRefType(true, Type.EQ.code());
				emitVariantCase(abi, type, info, i, payloadLift);
				this.w.write(Instruction.ELSE);
			}
			emitVariantCase(abi, type, info, n - 1, payloadLift);
			for (int i = 0; i < n - 1; i++) {
				this.w.write(Instruction.END);
			}
		}

		// One case's value: option -> nil / payload; result -> the (:ok . V) /
		// (:error . E) envelope; enum -> the case keyword; variant -> the keyword, or
		// (keyword . payload) when the case carries one.
		private void emitVariantCase(WitCanonicalAbi abi, WitType type, WitCanonicalAbi.VariantInfo info, int index,
				PayloadLift payloadLift) {
			WitType payload = info.payloads().get(index);
			WitType resolved = abi.resolveAliases(type).type();
			if (resolved instanceof WitType.OptionOf) {
				if (index == 0) {
					refNullEq();
				}
				else {
					emitPayloadOrNil(info.abi(), payload, payloadLift);
				}
				return;
			}
			if (resolved instanceof WitType.ResultOf) {
				WasmEmitHelper.compileStringLiteral(index == 0 ? ":OK" : ":ERROR", this.ctx);
				emitPayloadOrNil(info.abi(), payload, payloadLift);
				newCons();
				return;
			}
			// enum / variant: the case keyword, dotted with the payload when present.
			WasmEmitHelper.compileStringLiteral(":" + info.names().get(index).toUpperCase(java.util.Locale.ROOT),
					this.ctx);
			if (payload != null) {
				emitPayloadOrNil(info.abi(), payload, payloadLift);
				newCons();
			}
		}

		private void emitPayloadOrNil(WitCanonicalAbi abi, @Nullable WitType payload, PayloadLift payloadLift) {
			if (payload == null) {
				refNullEq();
			}
			else {
				payloadLift.lift(abi, payload);
			}
		}

		// record -> (:field value ...) keyword plist; tuple -> (v0 v1 ...) proper list.
		private void emitLiftRecordAt(WitCanonicalAbi abi, WitType type, int base, int offset, boolean plist) {
			WitCanonicalAbi.RecordInfo info = abi.recordInfo(type);
			int conses = 0;
			for (int i = 0; i < info.names().size(); i++) {
				if (plist) {
					WasmEmitHelper.compileStringLiteral(":" + info.names().get(i).toUpperCase(java.util.Locale.ROOT),
							this.ctx);
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
			int count = allocI32();
			load(base, offset, Instruction.I32_LOAD, 2);
			setLocal(elems);
			load(base, offset + 4, Instruction.I32_LOAD, 2);
			setLocal(count);
			emitLiftElements(abi, element, elems, count);
			this.i32Cursor = save;
		}

		// The count elements at the address in the elems local, lifted into a list in
		// order; the count local is spent.
		private void emitLiftElements(WitCanonicalAbi abi, WitType element, int elems, int idx) {
			int save = this.i32Cursor;
			int elemBase = allocI32();
			int acc = this.ctx.allocTemp();
			int elemSize = abi.size(element);
			refNullEq();
			setLocal(acc);
			this.w.write(Instruction.BLOCK, 0x40);
			this.w.write(Instruction.LOOP, 0x40);
			// while (idx != 0) { idx--; acc = cons(lift(elems[idx]), acc) }
			getLocal(idx);
			this.w.write(Instruction.I32_EQZ);
			this.w.write(Instruction.BR_IF);
			this.w.writeUnsignedLeb128(1);
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
			this.w.writeUnsignedLeb128(0);
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
			this.w.writeUnsignedLeb128(WasmLispCompiler.TYPE_FLOAT);
		}

		private void boxChar() {
			this.w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
			this.w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CHAR);
		}

		// Pushes the eqref local's value as the i32 of an integer type narrower than
		// 32 bits. An export answers the value exactly or traps (`v != canon(v)`, the
		// flat boundary's rule): a host masks what does not fit, or refuses the call.
		private void lowerNarrow(int slot, String type) {
			getLocal(slot);
			WasmEmitHelper.castI31GetS(this.ctx);
			if (!this.export) {
				return;
			}
			int save = this.i32Cursor;
			int value = allocI32();
			this.w.write(Instruction.TEE_LOCAL);
			this.w.writeUnsignedLeb128(value);
			getLocal(value);
			switch (type) {
				case "s8" -> this.w.write(Instruction.I32_EXTEND8_S);
				case "s16" -> this.w.write(Instruction.I32_EXTEND16_S);
				default -> {
					i32Const("u8".equals(type) ? 0xff : 0xffff);
					this.w.write(Instruction.I32_AND);
				}
			}
			this.w.write(Instruction.I32_NE);
			this.w.write(Instruction.IF, 0x40);
			this.w.write(Instruction.UNREACHABLE);
			this.w.write(Instruction.END);
			getLocal(value);
			this.i32Cursor = save;
		}

		// Pushes the eqref local's value as an i64. The exact-integer representations
		// (i31 and TYPE_BIGNUM) unbox through _int_val, exact over the full signed
		// 64-bit range -- the f64 route would round past 2^53; a float or ratio
		// normalizes through the number-to-f64 conversion with a trapping trunc.
		private void lowerI64(int slot, boolean signed) {
			getLocal(slot);
			this.w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			this.w.writeHeapType(Type.I31.code());
			getLocal(slot);
			this.w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
			this.w.writeHeapType(WasmLispCompiler.TYPE_BIGNUM);
			this.w.write(Instruction.I32_OR);
			this.w.write(Instruction.IF);
			this.w.write(Type.I64);
			getLocal(slot);
			this.w.write(Instruction.CALL);
			this.w.writeUnsignedLeb128(WasmLispCompiler.FUNC_INT_VAL);
			this.w.write(Instruction.ELSE);
			getLocal(slot);
			WasmEmitHelper.castFloatGetF64(this.ctx);
			this.w.write(signed ? Instruction.I64_TRUNC_S_F64 : Instruction.I64_TRUNC_U_F64);
			this.w.write(Instruction.END);
		}

		// Boxes the i64 on the stack as an exact integer through _int_new (an i31 when
		// it fits, a TYPE_BIGNUM box otherwise). One exception keeps the old float
		// widening: an UNSIGNED value at or above 2^63 has no exact representation in
		// the signed i64 box, and a host is allowed to hand one in (u64::MAX is a
		// common "no limit" sentinel in WASI), so it degrades to the float
		// approximation instead of arriving as a wrong negative number or a trap.
		private void boxI64(boolean signed) {
			if (signed) {
				this.w.write(Instruction.CALL);
				this.w.writeUnsignedLeb128(WasmLispCompiler.FUNC_INT_NEW);
				return;
			}
			if (this.export) {
				// An export's parameter is exact or the call traps, as the flat
				// boundary's u64 is (WasmExportCompiler.emitBoxParam).
				setLocal64();
				getLocal64();
				this.w.write(Instruction.I64_CONST);
				this.w.writeSignedLeb128(0);
				this.w.write(Instruction.I64_LT_S);
				this.w.write(Instruction.IF, 0x40);
				this.w.write(Instruction.UNREACHABLE);
				this.w.write(Instruction.END);
				getLocal64();
				this.w.write(Instruction.CALL);
				this.w.writeUnsignedLeb128(WasmLispCompiler.FUNC_INT_NEW);
				return;
			}
			setLocal64();
			getLocal64();
			this.w.write(Instruction.I64_CONST);
			this.w.writeSignedLeb128(0);
			this.w.write(Instruction.I64_GE_S);
			this.w.write(Instruction.IF);
			this.w.writeRefType(true, Type.EQ.code());
			getLocal64();
			this.w.write(Instruction.CALL);
			this.w.writeUnsignedLeb128(WasmLispCompiler.FUNC_INT_NEW);
			this.w.write(Instruction.ELSE);
			getLocal64();
			this.w.write(Instruction.F64_CONVERT_U_I64);
			boxFloat();
			this.w.write(Instruction.END);
		}

		private void newCons() {
			WasmEmitHelper.emitNewCons(this.ctx);
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
			this.w.writeUnsignedLeb128(typeIndex);
			this.w.writeUnsignedLeb128(field);
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
			this.w.writeUnsignedLeb128(slot);
		}

		private void globalGet(int index) {
			this.w.write(Instruction.GET_GLOBAL);
			this.w.writeUnsignedLeb128(index);
		}

		private void globalSet(int index) {
			this.w.write(Instruction.SET_GLOBAL);
			this.w.writeUnsignedLeb128(index);
		}

		private void setLocal(int slot) {
			this.w.write(Instruction.SET_LOCAL);
			this.w.writeUnsignedLeb128(slot);
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

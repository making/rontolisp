package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import am.ik.wit.WitItem;
import am.ik.wit.WitLocations;
import am.ik.wit.WitParseException;
import am.ik.wit.WitParseResult;
import am.ik.wit.WitParser;
import am.ik.wit.WitResolver;
import am.ik.wit.WitType;

import org.jspecify.annotations.Nullable;

/**
 * The
 * {@code (rontolisp:wit-import "kv.wit" :interface "wasi:keyvalue/store@0.2.0" :package
 * kv)} directive: <em>this program calls this WIT interface</em>. It is the mirror of
 * {@link WitExportDirective} and, like it, a compile-time front-end that <strong>lowers
 * into forms that already exist</strong> -- there is no new call path on any backend.
 *
 * <p>
 * What it lowers to depends on the backend, and that is the whole point: <em>one WIT, a
 * different implementation per backend, zero source changes</em>.
 *
 * <ul>
 * <li><strong>Preview 1 WASM</strong>: one {@link WasmImportDirective} per WIT function.
 * A flat function (scalars / string / bool / handles) lowers to literally what a
 * hand-written import block would have carried, so the emitted module is byte-identical
 * to it and {@code --optimize} still shakes the functions the program never calls. A rich
 * type is a compile error naming the WIT line: a core import is a bare host function,
 * with no component type to describe a richer shape with. A {@code result}-returning
 * function binds an internal raw import whose host answers the {@code (:ok . V)} /
 * {@code (:error . E)} envelope, unwrapped by a public wrapper defun through
 * {@code rontolisp::%wit-result} (whose error arm signals
 * {@code rontolisp:wit-error}).</li>
 * <li><strong>Interpreter and JVM</strong>: an ordinary {@code defun} per WIT function,
 * whose body calls {@code rontolisp::%wit-call} -- the runtime dispatch through the
 * provider bound for the interface ({@code rontolisp:wit-provide}). Because each binding
 * is an <em>ordinary defun</em>, {@code #'kv:get} / {@code funcall} / {@code mapcar} /
 * {@code eval} work with no extra wiring, exactly as they do for {@code wasm-import}'s
 * synthetic defuns.</li>
 * <li><strong>{@code --component}</strong>: an internal
 * {@code rontolisp::%component-import} form carrying the WIT text, from which the WASM
 * compiler synthesizes one canonical-ABI marshalling defun per bound function (the guest
 * side of a {@code canon lower}ed component import), plus the same envelope-unwrapping
 * wrapper split as Preview 1 for {@code result}-returning functions. The canonical ABI
 * marshals the rich types, so this is the only WASM boundary a {@code record} /
 * {@code variant} / {@code enum} / {@code option} / {@code tuple} / {@code result}
 * crosses -- as an argument as well as a result; only a {@code list<T>} argument (other
 * than {@code list<u8>}) and {@code flags} do not.</li>
 * </ul>
 *
 * <p>
 * {@code --no-gc} is rejected here (its MVP module imports nothing).
 *
 * <p>
 * Like {@link WitExportDirective} this class does no I/O and no codegen: the caller reads
 * the WIT text (so the interpreter and the filesystem-less browser playground can supply
 * it their own way) and splices the returned forms.
 *
 * @see WitTypeMapper
 * @see WitResolver
 */
public final class WitImportDirective {

	private WitImportDirective() {
	}

	/**
	 * How a WIT function name is spelled as the WASM Preview 1 import field (the
	 * host-side property name). A WIT label is always lower-kebab-case; a JavaScript host
	 * normally spells the same function in camelCase, which is also what {@code jco}
	 * produces when it transpiles a component.
	 */
	public enum FieldStyle {

		/**
		 * {@code create-shader} -> {@code createShader}: the JavaScript convention (the
		 * default -- it is what the browser demos' hand-written import objects already
		 * use).
		 */
		CAMEL,

		/** {@code create-shader} -> {@code create-shader}: the WIT label verbatim. */
		KEBAB;

		String apply(String label) {
			if (this == KEBAB) {
				return label;
			}
			StringBuilder out = new StringBuilder(label.length());
			boolean upper = false;
			for (int i = 0; i < label.length(); i++) {
				char c = label.charAt(i);
				if (c == '-') {
					upper = true;
					continue;
				}
				out.append(upper ? Character.toUpperCase(c) : c);
				upper = false;
			}
			return out.toString();
		}

	}

	/**
	 * A parsed {@code rontolisp:wit-import} directive.
	 *
	 * @param path the WIT file path as written (relative paths resolve against the source
	 * file's directory, like {@code load})
	 * @param iface the interface to bind, e.g. {@code wasi:keyvalue/store@0.2.0} (the
	 * version may be omitted, and a bare interface name works when the file defines it
	 * only once)
	 * @param pkg the Lisp package the bindings land in, or {@code null} to define them in
	 * the current package
	 * @param module the WASM Preview 1 import module ({@code :from}), or {@code null} for
	 * the interface's bare name
	 * @param fieldStyle how a WIT label is spelled as a Preview 1 import field
	 */
	public record Directive(String path, String iface, @Nullable String pkg, @Nullable String module,
			FieldStyle fieldStyle) {
	}

	/**
	 * Returns whether the given form is a {@code (rontolisp:wit-import ...)} directive.
	 * @param form the top-level form
	 * @return {@code true} if it is a rontolisp:wit-import directive
	 */
	public static boolean isDirective(LispVal form) {
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol sym) {
			PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
			return qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg()) && LispNames.WIT_IMPORT.equals(qn.member());
		}
		return false;
	}

	/**
	 * Parses a {@code (rontolisp:wit-import "kv.wit" :interface "..." :package kv)}
	 * directive.
	 * @param form the directive form
	 * @return the parsed directive
	 * @throws UnsupportedOperationException if the directive is malformed
	 */
	public static Directive parse(LispCons form) {
		List<LispVal> items = form.toList();
		if (items.size() < 2 || !(items.get(1) instanceof LispString path)) {
			throw new UnsupportedOperationException(
					"rontolisp:wit-import expects a WIT file path string, got: " + form.print());
		}
		String iface = null;
		String pkg = null;
		String module = null;
		FieldStyle fieldStyle = FieldStyle.CAMEL;
		int i = 2;
		while (i < items.size()) {
			if (!(items.get(i) instanceof LispSymbol keyword) || !keyword.isKeyword()) {
				throw new UnsupportedOperationException(
						"Expected a keyword option in " + form.print() + ", got: " + items.get(i).print());
			}
			if (i + 1 >= items.size()) {
				throw new UnsupportedOperationException("Missing value for " + keyword.name() + " in " + form.print());
			}
			LispVal value = items.get(i + 1);
			switch (LispNames.foldKeyword(keyword.name())) {
				// :interface and :from name WIT-side things (lower-kebab), so a bare
				// symbol -- upcased by the reader -- lowercases; :package names the
				// Lisp-side package and keeps the reader's spelling.
				case ":interface" -> iface = designator(value, ":interface", form).toLowerCase(java.util.Locale.ROOT);
				case ":package" -> pkg = designator(value, ":package", form);
				case ":from" -> module = designator(value, ":from", form).toLowerCase(java.util.Locale.ROOT);
				case ":field-style" -> fieldStyle = fieldStyle(value, form);
				default -> throw new UnsupportedOperationException(
						"Unknown rontolisp:wit-import option " + keyword.name() + " in " + form.print());
			}
			i += 2;
		}
		if (iface == null) {
			throw new UnsupportedOperationException("rontolisp:wit-import requires :interface (the WIT interface to "
					+ "bind, e.g. :interface \"wasi:keyvalue/store@0.2.0\") in " + form.print());
		}
		return new Directive(path.value(), iface, pkg, module, fieldStyle);
	}

	// A string, or a bare symbol written in the WIT's own spelling.
	private static String designator(LispVal value, String keyword, LispCons form) {
		return switch (value) {
			case LispString str -> str.value();
			case LispSymbol sym when !sym.isKeyword() -> {
				PackageRegistry.QualifiedName qn = PackageRegistry.splitQualified(sym.name());
				yield qn == null ? sym.name() : qn.member();
			}
			default -> throw new UnsupportedOperationException("rontolisp:wit-import " + keyword + " expects a name in "
					+ form.print() + ", got: " + value.print());
		};
	}

	private static FieldStyle fieldStyle(LispVal value, LispCons form) {
		if (value instanceof LispSymbol sym && sym.isKeyword()) {
			return switch (LispNames.foldKeyword(sym.name())) {
				case ":camel" -> FieldStyle.CAMEL;
				case ":kebab" -> FieldStyle.KEBAB;
				default -> throw new UnsupportedOperationException(
						"rontolisp:wit-import :field-style expects :camel or :kebab in " + form.print() + ", got: "
								+ sym.name());
			};
		}
		throw new UnsupportedOperationException("rontolisp:wit-import :field-style expects :camel or :kebab in "
				+ form.print() + ", got: " + value.print());
	}

	/**
	 * Checks the WIT interface against the backend's boundary and lowers it into the
	 * forms that bind it: on Preview 1 WASM a {@code rontolisp:wasm-import} per function,
	 * on the interpreter and the JVM a {@code defun} per function dispatching through the
	 * interface's provider. A {@code :package} also produces the {@code defpackage} that
	 * exports the bound names.
	 * @param directive the parsed directive
	 * @param witSource the WIT text
	 * @param witPath the WIT file path, for error messages
	 * @param backend the backend being compiled for
	 * @return the forms the directive stands for, in WIT order
	 * @throws UnsupportedOperationException on any contract violation, naming the WIT
	 * file and line
	 */
	public static List<LispVal> lower(Directive directive, String witSource, String witPath,
			WitExportDirective.Backend backend) {
		return lower(directive, witSource, witPath, backend, null);
	}

	/**
	 * Like {@link #lower(Directive, String, String, WitExportDirective.Backend)}, with an
	 * optional member filter for the {@code --component} backend: only the named members
	 * are bound (and validated). The component path skips {@code --optimize}'s core tree
	 * shaker by design, so unused interface functions are pruned here instead -- the
	 * caller ({@code WitImportInliner}) passes the members the program references.
	 * @param directive the parsed directive
	 * @param witSource the WIT text
	 * @param witPath the WIT file path, for error messages
	 * @param backend the backend being compiled for
	 * @param memberFilter the members to bind, or {@code null} for all of them
	 * @return the forms the directive stands for, in WIT order
	 */
	public static List<LispVal> lower(Directive directive, String witSource, String witPath,
			WitExportDirective.Backend backend, @Nullable Set<String> memberFilter) {
		return lower(directive, witSource, witPath, backend, memberFilter, Set.of());
	}

	/**
	 * Like {@link #lower(Directive, String, String, WitExportDirective.Backend, Set)},
	 * plus the filter for resource {@code drop}s.
	 * <p>
	 * A WIT {@code resource} is released by its interface's {@code drop}, which the model
	 * does not spell out as a function -- so there is nothing in
	 * {@link WitResolver#functions} to bind, and a program that receives a handle has no
	 * way to give it back. It is bound as <strong>{@code <resource>-drop}</strong>,
	 * symmetric with the {@code <resource>-new} a constructor binds: both are rontolisp
	 * spellings of something WIT does not name.
	 * <p>
	 * <strong>Only when the program names it</strong>, on every backend. A drop is not a
	 * WIT function, so it is exempt from the "Preview 1 binds every function" convention
	 * -- and this one rule is what keeps every artifact that existed before drops
	 * byte-identical, since nothing references a {@code -drop} name in it.
	 * @param directive the parsed directive
	 * @param witSource the WIT text
	 * @param witPath the WIT file path, for error messages
	 * @param backend the backend being compiled for
	 * @param memberFilter the FUNCTIONS to bind, or {@code null} for all of them
	 * @param dropFilter the drop names the program references, or {@code null} to bind a
	 * drop for every resource ({@code --no-prune} / {@code --dynamic}, and the
	 * interpreter, which produces no artifact to keep identical)
	 * @return the forms the directive stands for, in WIT order
	 */
	public static List<LispVal> lower(Directive directive, String witSource, String witPath,
			WitExportDirective.Backend backend, @Nullable Set<String> memberFilter, @Nullable Set<String> dropFilter) {
		if (backend == WitExportDirective.Backend.WASM_NO_GC) {
			throw new UnsupportedOperationException("rontolisp:wit-import is not supported with --no-gc: the scalar "
					+ "backend emits a plain MVP module with no imports");
		}
		WitParseResult parsed;
		try {
			parsed = WitParser.parseLocated(witSource);
		}
		catch (WitParseException ex) {
			throw new UnsupportedOperationException(witPath + ": " + ex.getMessage(), ex);
		}
		WitLocations locations = parsed.locations();
		WitResolver resolver = new WitResolver(parsed.document());
		WitItem.InterfaceDef iface = resolver.findInterface(directive.iface());
		if (iface == null) {
			throw new UnsupportedOperationException(witPath + ": no interface '" + directive.iface() + "' (found: "
					+ String.join(", ", resolver.interfaceIds()) + ")");
		}
		List<WitResolver.Func> funcs = WitResolver.functions(iface);
		if (funcs.isEmpty()) {
			throw new UnsupportedOperationException(witPath + ":" + locations.lineOf(iface) + ": interface '"
					+ iface.name() + "' declares no functions");
		}
		boolean wasm = backend == WitExportDirective.Backend.WASM_GC;
		boolean component = backend == WitExportDirective.Backend.WASM_COMPONENT;
		String module = directive.module() == null ? iface.name() : directive.module();
		// The provider registry is keyed by the interface's CANONICAL id, never by the
		// reference as written: `wasi:keyvalue/store@0.2.0`, `wasi:keyvalue/store` and
		// `store` all name this one interface, so all three must reach the one provider
		// (and one `rontolisp:wit-provide` key must override it whichever was written).
		// On the component path the canonical id is also the component import name.
		String ifaceId = Objects.requireNonNullElse(resolver.canonicalId(iface), directive.iface());
		List<LispVal> forms = new ArrayList<>();
		List<LispVal> bindings = new ArrayList<>();
		List<LispVal> componentMembers = new ArrayList<>();
		Set<String> allMembers = new LinkedHashSet<>();
		Set<String> boundMembers = new LinkedHashSet<>();
		for (WitResolver.Func func : funcs) {
			String member = memberName(func);
			if (!allMembers.add(member)) {
				throw new UnsupportedOperationException(witPath + ":" + locations.lineOf(func.def()) + ": interface '"
						+ iface.name() + "' binds '" + member + "' twice");
			}
			if (memberFilter != null && !memberFilter.contains(member)) {
				continue;
			}
			boundMembers.add(member);
			String name = directive.pkg() == null ? member : PackageRegistry.qualify(directive.pkg(), member);
			if (component) {
				validateComponentFunc(func, witPath, locations, resolver, iface, member);
				List<Param> params = parameters(func, witPath, locations, resolver, iface, member, false, true);
				if (func.def().func().async()) {
					// An `async func` member async-lowers: the call starts as a subtask
					// (%member-start, returning a (packed . retptr) token cons), which
					// rontolisp::%subtask-future turns into a FIRST-CLASS FUTURE --
					// settled immediately when the call completed eagerly (lifted
					// through %member-lift), pending and scheduler-registered otherwise.
					// The public defun returns that future; a result-returning member's
					// public defun is an async-defun that awaits it and unwraps the
					// (:ok . V) / (:error . E) envelope, so the error arm re-signals at
					// the caller's await (the settled result mapping).
					String start = internalName(directive.pkg(), "%" + member + "-start");
					String lift = internalName(directive.pkg(), "%" + member + "-lift");
					componentMembers.add(asyncCallBinding(member, start, lift));
					bindings
						.add(subtaskFutureDefun(name, start, lift, params, isResultReturning(func, resolver, iface)));
					continue;
				}
				if (isResultReturning(func, resolver, iface)) {
					// The raw synthetic defun returns the (:ok . V) / (:error . E)
					// envelope; the public wrapper unwraps it and signals the error arm.
					String raw = rawName(directive.pkg(), member);
					componentMembers.add(memberBinding(member, raw));
					bindings.add(resultWrapperDefun(name, raw, params));
				}
				else {
					componentMembers.add(memberBinding(member, name));
				}
				continue;
			}
			List<Param> params = parameters(func, witPath, locations, resolver, iface, member, wasm, false);
			String returns = resultDesignator(func, witPath, locations, resolver, iface, member, wasm);
			if (!wasm && func.def().func().async()) {
				// An `async func` member on the interpreter / the JVM: the provider call
				// is synchronous, but the binding is an async-defun so callers get a
				// FUTURE on every backend that has one -- settled with the provider's
				// result (eager-start runs the body to completion), or rejected when the
				// provider signals (the condition re-signals at await, matching the
				// component's error-arm mapping). Preview 1 stays the degenerate
				// synchronous binding below (its async contract).
				bindings.add(asyncProviderDefun(name, ifaceId, member, params));
				continue;
			}
			bindings.add(wasm ? wasmImportForm(name, module, directive.fieldStyle().apply(member), params, returns)
					: providerDefun(name, ifaceId, member, params));
		}
		// A resource is released by its own interface's `drop`, which WIT declares no
		// function for -- see the lower() javadoc for the name and why it is bound only
		// when
		// the program asks for it.
		for (WitItem item : iface.items()) {
			if (!(item instanceof WitItem.ResourceDef resource)) {
				continue;
			}
			String member = resource.name() + "-drop";
			if (!allMembers.add(member)) {
				throw new UnsupportedOperationException(witPath + ":" + locations.lineOf(item) + ": interface '"
						+ iface.name() + "' binds '" + member + "' twice");
			}
			if (dropFilter != null && !dropFilter.contains(member)) {
				continue;
			}
			boundMembers.add(member);
			String name = directive.pkg() == null ? member : PackageRegistry.qualify(directive.pkg(), member);
			if (component) {
				componentMembers.add(dropBinding(resource.name(), name));
			}
			else if (wasm) {
				// Preview 1: a handle is an opaque integer the host handed over -- there
				// is
				// no guest-side table and nothing to release. Importing a
				// `[resource-drop]` field would INVENT a host function the interface
				// never
				// declared, breaking both the
				// byte-identity-with-a-hand-written-import-block
				// property and the browser demos' hand-written import objects.
				bindings.add(noopDropDefun(name));
			}
			else {
				bindings.add(providerDefun(name, ifaceId, member, List.of(new Param("self", ":int"))));
			}
		}
		// An async built-in is derived from a `type` ALIAS the interface declares to a
		// stream or a future: `type body-stream = stream<u8>` binds body-stream-new /
		// -read / -write / -drop-readable / -drop-writable. Like a drop, an async member
		// is not a WIT function -- it is a rontolisp spelling of the canonical ABI's
		// stream.*/future.* built-ins, typed by the alias's target -- so it is bound ONLY
		// when the program names it, and it exists ONLY under --component: the built-ins
		// are a component-model mechanism no other backend has.
		for (WitItem item : iface.items()) {
			if (!(item instanceof WitItem.TypeAlias alias)) {
				continue;
			}
			Scoped target = resolveAliases(alias.target(), resolver, iface);
			boolean stream = target.type() instanceof WitType.StreamOf;
			if (!stream && !(target.type() instanceof WitType.FutureOf)) {
				// A non-stream/future alias binds ONE derived member instead:
				// `<alias>-task-return` -- `canon task.return` typed by the alias's
				// target, the mid-task result delivery of a stackful async EXPORT (the
				// WASI 0.3 replacement for 0.2's response-outparam.set). Like the async
				// built-ins it is not a WIT function: bound ONLY when the program names
				// it, and only under --component.
				String member = alias.name() + "-task-return";
				if (!allMembers.add(member)) {
					throw new UnsupportedOperationException(witPath + ":" + locations.lineOf(item) + ": interface '"
							+ iface.name() + "' binds '" + member + "' twice");
				}
				if (dropFilter != null && !dropFilter.contains(member)) {
					continue;
				}
				if (!component) {
					if (dropFilter != null) {
						throw new UnsupportedOperationException(witPath + ":" + locations.lineOf(item) + ": '" + member
								+ "' is the task-return built-in, which only the --component backend has (the async "
								+ "canonical ABI); the interpreter, the JVM and Preview 1 WASM cannot bind it");
					}
					continue;
				}
				validateComponentParam(target.type(), witPath, locations, resolver, target.iface(), alias, member,
						"the task result", true);
				boundMembers.add(member);
				String name = directive.pkg() == null ? member : PackageRegistry.qualify(directive.pkg(), member);
				componentMembers.add(taskReturnBinding(alias.name(), name));
				continue;
			}
			for (String op : ASYNC_OPS) {
				String member = alias.name() + "-" + op;
				if (!allMembers.add(member)) {
					throw new UnsupportedOperationException(witPath + ":" + locations.lineOf(item) + ": interface '"
							+ iface.name() + "' binds '" + member + "' twice");
				}
				if (dropFilter != null && !dropFilter.contains(member)) {
					continue;
				}
				if (!component) {
					if (dropFilter != null) {
						throw new UnsupportedOperationException(witPath + ":" + locations.lineOf(item) + ": '" + member
								+ "' is a stream/future built-in, which only the --component backend has (the async "
								+ "canonical ABI); the interpreter, the JVM and Preview 1 WASM cannot bind it");
					}
					// A bind-everything pass (no filter) on a non-component backend just
					// skips them: the alias itself is legal WIT, and a program that never
					// calls the built-ins must keep compiling everywhere.
					continue;
				}
				validateAsyncAlias(target, witPath, locations, resolver, alias, member, op);
				boundMembers.add(member);
				String name = directive.pkg() == null ? member : PackageRegistry.qualify(directive.pkg(), member);
				componentMembers.add(asyncBinding(alias.name(), op, name));
			}
		}
		// A component may import an interface purely for its TYPES: wasi:io/streams' own
		// `stream-error` carries a wasi:io/error `error` resource, and a resource is its
		// defining interface's type, so that interface has to be imported for the type to
		// exist -- even though nothing calls its one function. (wasm-tools encodes
		// exactly
		// that: the wasi:io/error instance type of a real fetch component declares the
		// resource and no functions at all.) Whether such an import is actually needed is
		// only knowable once every import is in hand, so the component path defers the
		// judgement to WasmComponentBuilder.appendUserImports. On every other backend an
		// interface is a set of callable functions and nothing else, so an unused one is
		// a mistake worth naming.
		if (boundMembers.isEmpty() && !component) {
			throw new UnsupportedOperationException(witPath + ":" + locations.lineOf(iface) + ": interface '"
					+ iface.name() + "': the program calls none of its functions");
		}
		if (directive.pkg() != null) {
			forms.add(defpackageForm(directive.pkg(), boundMembers));
		}
		if (component) {
			forms.add(componentImportForm(ifaceId, witSource, componentMembers));
		}
		forms.addAll(bindings);
		return forms;
	}

	// The internal raw name of a result-returning binding: pkg::%member (the public
	// wrapper defun unwraps its envelope).
	private static String rawName(@Nullable String pkg, String member) {
		return pkg == null ? "%" + member : PackageRegistry.qualifyInternal(pkg, "%" + member);
	}

	// An internal (non-exported) name in the directive's package.
	private static String internalName(@Nullable String pkg, String bare) {
		return pkg == null ? bare : PackageRegistry.qualifyInternal(pkg, bare);
	}

	// (:async-call "send" "pkg::%send-start" "pkg::%send-lift") -- an async func member
	// of a %component-import form: the start wrapper async-lowers the call, the lift
	// wrapper reads the result out of the return area once the subtask has returned
	// (called by %subtask-future for an eager completion, or by the scheduler).
	private static LispVal asyncCallBinding(String member, String startName, String liftName) {
		return list(List.of(new LispSymbol(":async-call"), new LispString(member), new LispString(startName),
				new LispString(liftName)));
	}

	// (:task-return "handle-result" "pkg::handle-result-task-return") -- the task-return
	// built-in derived from a non-stream/future type alias.
	private static LispVal taskReturnBinding(String alias, String lispName) {
		return list(List.of(new LispSymbol(":task-return"), new LispString(alias), new LispString(lispName)));
	}

	// The public binding of an async func member under --component. A plain member:
	// (defun name (p ...)
	// (rontolisp::%subtask-future (start p ...) (function lift)))
	// -- the future settles to the lifted result. A result-returning member instead:
	// (rontolisp:async-defun name (p ...)
	// (let ((%wit-envelope (rontolisp:await
	// (rontolisp::%subtask-future (start p ...)
	// (function lift)))))
	// (rontolisp::%wit-result %wit-envelope)))
	// -- awaiting inside an async-defun keeps the eager-start contract (the subtask is
	// in flight before the caller resumes) and re-signals the envelope's error arm at
	// the caller's await. The await sits in a let init (a spine position) so the state
	// machine suspends there structurally.
	private static LispVal subtaskFutureDefun(String name, String start, String lift, List<Param> params,
			boolean resultReturning) {
		List<LispVal> lambdaList = new ArrayList<>();
		List<LispVal> startCall = new ArrayList<>();
		startCall.add(new LispSymbol(start));
		for (Param param : params) {
			LispSymbol symbol = new LispSymbol(param.name());
			lambdaList.add(symbol);
			startCall.add(symbol);
		}
		LispVal liftRef = list(List.of(new LispSymbol(LispNames.FUNCTION), new LispSymbol(lift)));
		LispVal future = list(List.of(
				new LispSymbol(
						PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG, LispNames.SUBTASK_FUTURE_INTERNAL)),
				list(startCall), liftRef));
		if (!resultReturning) {
			return list(List.of(new LispSymbol(LispNames.DEFUN), new LispSymbol(name), list(lambdaList), future));
		}
		LispSymbol envelope = new LispSymbol("%wit-envelope");
		LispVal await = list(
				List.of(new LispSymbol(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.AWAIT)), future));
		LispVal unwrap = list(
				List.of(new LispSymbol(PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG, LispNames.WIT_RESULT)),
						envelope));
		LispVal let = list(
				List.of(new LispSymbol(LispNames.LET), list(List.of(list(List.of(envelope, await)))), unwrap));
		return list(List.of(new LispSymbol(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.ASYNC_DEFUN)),
				new LispSymbol(name), list(lambdaList), let));
	}

	// (rontolisp:async-defun name (p ...) (rontolisp::%wit-call "iface" "member" p ...))
	// -- an async func member on the interpreter / the JVM: the synchronous provider
	// call wrapped so the binding returns a settled (or, on a signal, rejected) future.
	private static LispVal asyncProviderDefun(String name, String iface, String member, List<Param> params) {
		List<LispVal> lambdaList = new ArrayList<>();
		List<LispVal> call = new ArrayList<>();
		call.add(new LispSymbol(PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG, LispNames.WIT_CALL)));
		call.add(new LispString(iface));
		call.add(new LispString(member));
		for (Param param : params) {
			LispSymbol symbol = new LispSymbol(param.name());
			lambdaList.add(symbol);
			call.add(symbol);
		}
		return list(List.of(new LispSymbol(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.ASYNC_DEFUN)),
				new LispSymbol(name), list(lambdaList), list(call)));
	}

	// ("member" "lisp-name") -- one bound member of a %component-import form.
	private static LispVal memberBinding(String member, String lispName) {
		return list(List.of(new LispString(member), new LispString(lispName)));
	}

	// The operations an async (stream/future) type alias binds. `new` returns the
	// (readable . writable) handle pair; the rest take an end. The set is the same for
	// both kinds -- a future is a one-shot stream as far as its handle lifecycle goes.
	private static final List<String> ASYNC_OPS = List.of("new", "read", "write", "drop-readable", "drop-writable");

	// (:async "body-stream" "read" "h:body-stream-read") -- an async built-in binding of
	// a %component-import form. Like :drop it is a distinct emission kind: a canon
	// stream.*/future.* built-in is a CORE function typed by a component-level
	// stream/future type, with no instance function behind it.
	private static LispVal asyncBinding(String alias, String op, String lispName) {
		return list(
				List.of(new LispSymbol(":async"), new LispString(alias), new LispString(op), new LispString(lispName)));
	}

	// The element/payload contract of a bound async alias: a stream must be a byte
	// stream (stream<u8> is what the canonical built-ins read/write through linear
	// memory) -- EXCEPT a stream of resource handles (wasi:sockets' accept
	// stream<tcp-socket>), whose 4-byte elements the read built-in lifts as opaque
	// integer handles; such a stream is read-only for the guest, so only read /
	// drop-readable bind. A future must be parameterized, and its payload must be a
	// liftable value (future.read lifts it with the same machinery as a function
	// result).
	private static void validateAsyncAlias(Scoped target, String witPath, WitLocations locations, WitResolver resolver,
			WitItem.TypeAlias alias, String member, String op) {
		if (target.type() instanceof WitType.StreamOf stream) {
			WitType element = stream.element();
			if (element != null && isResourceHandle(element, resolver, target.iface())) {
				if (!"read".equals(op) && !"drop-readable".equals(op)) {
					throw new UnsupportedOperationException(witPath + ":" + locations.lineOf(alias) + ": '" + member
							+ "': the alias '" + alias.name() + "' names a stream of resource handles, which is "
							+ "read-only for the guest -- only the -read and -drop-readable built-ins bind");
				}
				return;
			}
			if (element == null || !isU8(element, resolver, target.iface())) {
				throw new UnsupportedOperationException(witPath + ":" + locations.lineOf(alias) + ": '" + member
						+ "': the alias '" + alias.name() + "' names a stream whose element is not u8; only stream<u8> "
						+ "(a byte stream) and a stream of resource handles have async built-ins");
			}
			return;
		}
		WitType payload = ((WitType.FutureOf) target.type()).element();
		if (payload == null) {
			throw new UnsupportedOperationException(witPath + ":" + locations.lineOf(alias) + ": '" + member
					+ "': the alias '" + alias.name() + "' names a bare future with no payload type; only a "
					+ "parameterized future<T> can be read or written");
		}
		validateComponentResult(payload, witPath, locations, resolver, target.iface(), alias, member,
				"the future payload");
	}

	// (:drop "bucket" "kv:bucket-drop") -- a resource drop of a %component-import form.
	// The
	// keyword head is what tells it apart from a ("member" "lisp-name") function binding:
	// `canon resource.drop` is a different emission kind, producing a CORE function with
	// no
	// component function behind it.
	private static LispVal dropBinding(String resource, String lispName) {
		return list(List.of(new LispSymbol(":drop"), new LispString(resource), new LispString(lispName)));
	}

	// (defun name (self) self nil) -- a Preview 1 drop, which releases nothing.
	private static LispVal noopDropDefun(String name) {
		return list(List.of(new LispSymbol(LispNames.DEFUN), new LispSymbol(name),
				list(List.of(new LispSymbol("self"))), new LispSymbol("self"), LispNil.INSTANCE));
	}

	// (rontolisp::%component-import "iface-id" "wit text" ("member" "lisp-name") ...) --
	// the WIT text travels inside the form so the WASM compiler reads no files (the
	// browser playground has no filesystem).
	private static LispVal componentImportForm(String ifaceId, String witSource, List<LispVal> members) {
		List<LispVal> out = new ArrayList<>();
		out.add(new LispSymbol(PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG, LispNames.COMPONENT_IMPORT)));
		out.add(new LispString(ifaceId));
		out.add(new LispString(witSource));
		out.addAll(members);
		return list(out);
	}

	// (defun name (p ...) (rontolisp::%wit-result (raw p ...))) -- the public wrapper of
	// a result-returning binding on the WASM backends.
	private static LispVal resultWrapperDefun(String name, String raw, List<Param> params) {
		List<LispVal> lambdaList = new ArrayList<>();
		List<LispVal> call = new ArrayList<>();
		call.add(new LispSymbol(raw));
		for (Param param : params) {
			LispSymbol symbol = new LispSymbol(param.name());
			lambdaList.add(symbol);
			call.add(symbol);
		}
		LispVal unwrap = list(
				List.of(new LispSymbol(PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG, LispNames.WIT_RESULT)),
						list(call)));
		return list(List.of(new LispSymbol(LispNames.DEFUN), new LispSymbol(name), list(lambdaList), unwrap));
	}

	// Whether the function's (effective) result is a WIT result -- the shape whose error
	// arm must signal, and therefore the shape that needs the envelope + wrapper split on
	// the WASM backends.
	private static boolean isResultReturning(WitResolver.Func func, WitResolver resolver, WitItem.InterfaceDef iface) {
		if (func.def().kind() == WitItem.FuncKind.CONSTRUCTOR) {
			return false;
		}
		WitType result = func.def().func().result();
		if (result == null) {
			return false;
		}
		return resolveAliases(result, resolver, iface).type() instanceof WitType.ResultOf;
	}

	/**
	 * A WIT type together with the interface scope its named references resolve in. The
	 * scope CHANGES as a walk descends: follow a {@code use} clause into another
	 * interface and you are looking at THAT interface's types, whose own internal
	 * references the starting scope never imported ({@code wasi:http/outgoing-handler}
	 * uses {@code error-code} from {@code wasi:http/types}, and {@code error-code}'s
	 * {@code DNS-error} case carries a {@code DNS-error-payload} it has never heard of).
	 *
	 * @param iface the interface the type is written in
	 * @param type the type
	 */
	private record Scoped(WitItem.InterfaceDef iface, WitType type) {
	}

	// Follows a `type` alias chain (across interfaces) to the type it bottoms out at, and
	// reports the scope THAT type is written in.
	private static Scoped resolveAliases(WitType type, WitResolver resolver, WitItem.InterfaceDef iface) {
		if (type instanceof WitType.Named named) {
			WitResolver.Owned owned = resolver.resolveOwned(iface, named.name());
			if (owned != null && owned.item() instanceof WitItem.TypeAlias alias) {
				return resolveAliases(alias.target(), resolver, owned.owner());
			}
		}
		return new Scoped(iface, type);
	}

	// What the canonical-ABI wrapper codegen supports today. Results lift recursively
	// from
	// the return area, so everything but flags crosses; parameters lower to flat values,
	// which reaches every shape a variant / record flattens into -- but NOT a list<T>
	// (other than list<u8>), because writing a canonical array into linear memory is a
	// different mechanism from flattening.
	private static void validateComponentFunc(WitResolver.Func func, String witPath, WitLocations locations,
			WitResolver resolver, WitItem.InterfaceDef iface, String member) {
		for (var param : func.def().func().params()) {
			validateComponentParam(param.type(), witPath, locations, resolver, iface, func.def(), member,
					"parameter '" + param.name() + "'", true);
		}
		WitType result = func.def().func().result();
		if (result != null) {
			validateComponentResult(result, witPath, locations, resolver, iface, func.def(), member, "the result");
		}
	}

	private static void validateComponentParam(WitType type, String witPath, WitLocations locations,
			WitResolver resolver, WitItem.InterfaceDef iface, WitItem anchor, String member, String what,
			boolean optionAllowed) {
		Scoped scoped = resolveAliases(type, resolver, iface);
		// From here on the type's names resolve in the interface that WROTE it, which is
		// not necessarily the one this walk started in.
		WitItem.InterfaceDef in = scoped.iface();
		WitType t = scoped.type();
		switch (t) {
			case WitType.StreamOf ignored ->
				validateAsyncElement(t, witPath, locations, resolver, in, anchor, member, what);
			case WitType.FutureOf ignored ->
				validateAsyncElement(t, witPath, locations, resolver, in, anchor, member, what);
			case WitType.ListOf list -> {
				if (!isU8(list.element(), resolver, in)) {
					throw new UnsupportedOperationException(witPath + ":" + locations.lineOf(anchor) + ": '" + member
							+ "': the WIT type of " + what + " is a list, which does not cross the component import "
							+ "boundary as a parameter yet (only list<u8>, which crosses as a byte string, does). Its "
							+ "rontolisp representation is settled (a proper list), and the interpreter and the JVM "
							+ "backend bind it today");
				}
			}
			case WitType.OptionOf opt -> {
				if (!optionAllowed) {
					// option<option<T>> has no rontolisp representation: both `none` and
					// `some(none)` are nil.
					throw new UnsupportedOperationException(witPath + ":" + locations.lineOf(anchor) + ": '" + member
							+ "': the WIT type of " + what + " nests an option directly inside an option, which has no "
							+ "rontolisp value (an option is the value or nil, so `none` and `some(none)` would be the "
							+ "same nil)");
				}
				validateComponentParam(opt.element(), witPath, locations, resolver, in, anchor, member, what, false);
			}
			case WitType.ResultOf res -> {
				if (res.ok() != null) {
					validateComponentParam(res.ok(), witPath, locations, resolver, in, anchor, member, what, true);
				}
				if (res.err() != null) {
					validateComponentParam(res.err(), witPath, locations, resolver, in, anchor, member, what, true);
				}
			}
			case WitType.TupleOf tuple -> {
				for (WitType element : tuple.elements()) {
					validateComponentParam(element, witPath, locations, resolver, in, anchor, member, what, true);
				}
			}
			case WitType.Named named -> {
				WitResolver.Owned owned = resolver.resolveOwned(in, named.name());
				// A definition's fields / cases are written in the interface that owns
				// it.
				WitItem.InterfaceDef owner = owned == null ? in : owned.owner();
				switch (owned == null ? null : owned.item()) {
					case WitItem.RecordDef record -> {
						rejectEmptyRecord(record, witPath, locations, anchor, member, what);
						for (WitItem.Field field : record.fields()) {
							validateComponentParam(field.type(), witPath, locations, resolver, owner, anchor, member,
									what, true);
						}
					}
					case WitItem.VariantDef variant -> {
						for (WitItem.Case c : variant.cases()) {
							if (c.payload() != null) {
								validateComponentParam(c.payload(), witPath, locations, resolver, owner, anchor, member,
										what, true);
							}
						}
					}
					case WitItem.FlagsDef ignored -> throw new UnsupportedOperationException(witPath + ":"
							+ locations.lineOf(anchor) + ": '" + member + "': the WIT type of " + what
							+ " involves flags, which does not cross the component import boundary yet (its rontolisp "
							+ "representation is settled: a keyword list)");
					case WitItem.EnumDef ignored -> {
						// a keyword -- crosses
					}
					case WitItem.ResourceDef ignored -> {
						// a handle -- crosses
					}
					case null -> throw undefinedType(witPath, locations, anchor, member, what, named);
					default -> {
						// a type alias was already resolved above
					}
				}
			}
			default -> {
				// the scalars, bool, char, string, borrow/own -- all cross
			}
		}
	}

	// The component model has no empty record: `record r { }` cannot be encoded at all,
	// so
	// it would surface as an unreadable component rather than a compile error.
	private static void rejectEmptyRecord(WitItem.RecordDef record, String witPath, WitLocations locations,
			WitItem anchor, String member, String what) {
		if (record.fields().isEmpty()) {
			throw new UnsupportedOperationException(witPath + ":" + locations.lineOf(anchor) + ": '" + member
					+ "': the WIT type of " + what + " involves the record '" + record.name()
					+ "', which declares no fields -- the component model has no empty record type");
		}
	}

	private static boolean isU8(WitType type, WitResolver resolver, WitItem.InterfaceDef iface) {
		return resolveAliases(type, resolver, iface).type() instanceof WitType.Prim prim && "u8".equals(prim.name());
	}

	// Whether the type is (an own of, or a bare reference to) a resource -- the element
	// shape of a handle stream like wasi:sockets' stream<tcp-socket>.
	private static boolean isResourceHandle(WitType type, WitResolver resolver, WitItem.InterfaceDef iface) {
		Scoped scoped = resolveAliases(type, resolver, iface);
		if (scoped.type() instanceof WitType.OwnOf) {
			return true;
		}
		return scoped.type() instanceof WitType.Named named
				&& resolver.resolveType(scoped.iface(), named.name()) instanceof WitItem.ResourceDef;
	}

	// The --component acceptance policy for a stream/future value type: the handle itself
	// crosses as a bare i32 (the canonical ABI's async built-ins read/write it), but the
	// element governs that marshalling, so an unmarshalable element is rejected here as a
	// friendly compile error rather than surfacing later as a codegen throw. A stream
	// must be a byte stream (stream<u8>) or a stream of resource handles (wasi:sockets'
	// accept stream<tcp-socket>, read through a handle-element alias built-in); a future
	// must be parameterized so its payload can be read out (future.read), and that
	// payload is validated as a produced value.
	private static void validateAsyncElement(WitType t, String witPath, WitLocations locations, WitResolver resolver,
			WitItem.InterfaceDef in, WitItem anchor, String member, String what) {
		if (t instanceof WitType.StreamOf stream) {
			WitType elem = stream.element();
			if (elem == null || (!isU8(elem, resolver, in) && !isResourceHandle(elem, resolver, in))) {
				throw new UnsupportedOperationException(witPath + ":" + locations.lineOf(anchor) + ": '" + member
						+ "': the WIT type of " + what + " is a stream whose element is not u8; only stream<u8> "
						+ "(a byte stream) and a stream of resource handles cross the component boundary");
			}
		}
		else if (t instanceof WitType.FutureOf fut) {
			if (fut.element() == null) {
				throw new UnsupportedOperationException(witPath + ":" + locations.lineOf(anchor) + ": '" + member
						+ "': the WIT type of " + what + " is a bare future with no payload type; only a parameterized "
						+ "future<T> can be read");
			}
			validateComponentResult(fut.element(), witPath, locations, resolver, in, anchor, member, what);
		}
	}

	private static UnsupportedOperationException undefinedType(String witPath, WitLocations locations, WitItem anchor,
			String member, String what, WitType.Named named) {
		return new UnsupportedOperationException(
				witPath + ":" + locations.lineOf(anchor) + ": '" + member + "': the WIT type of " + what + " is '"
						+ named.name() + "', which the file does not define (nor import with a use clause)");
	}

	private static void validateComponentResult(WitType type, String witPath, WitLocations locations,
			WitResolver resolver, WitItem.InterfaceDef iface, WitItem anchor, String member, String what) {
		Scoped scoped = resolveAliases(type, resolver, iface);
		WitItem.InterfaceDef in = scoped.iface();
		WitType t = scoped.type();
		switch (t) {
			case WitType.StreamOf ignored ->
				validateAsyncElement(t, witPath, locations, resolver, in, anchor, member, what);
			case WitType.FutureOf ignored ->
				validateAsyncElement(t, witPath, locations, resolver, in, anchor, member, what);
			case WitType.ListOf list ->
				validateComponentResult(list.element(), witPath, locations, resolver, in, anchor, member, what);
			case WitType.OptionOf opt ->
				validateComponentResult(opt.element(), witPath, locations, resolver, in, anchor, member, what);
			case WitType.ResultOf res -> {
				if (res.ok() != null) {
					validateComponentResult(res.ok(), witPath, locations, resolver, in, anchor, member, what);
				}
				if (res.err() != null) {
					validateComponentResult(res.err(), witPath, locations, resolver, in, anchor, member, what);
				}
			}
			case WitType.TupleOf tuple -> {
				for (WitType element : tuple.elements()) {
					validateComponentResult(element, witPath, locations, resolver, in, anchor, member, what);
				}
			}
			case WitType.Named named -> {
				WitResolver.Owned owned = resolver.resolveOwned(in, named.name());
				WitItem.InterfaceDef owner = owned == null ? in : owned.owner();
				switch (owned == null ? null : owned.item()) {
					case WitItem.RecordDef record -> {
						rejectEmptyRecord(record, witPath, locations, anchor, member, what);
						for (WitItem.Field field : record.fields()) {
							validateComponentResult(field.type(), witPath, locations, resolver, owner, anchor, member,
									what);
						}
					}
					case WitItem.VariantDef variant -> {
						for (WitItem.Case c : variant.cases()) {
							if (c.payload() != null) {
								validateComponentResult(c.payload(), witPath, locations, resolver, owner, anchor,
										member, what);
							}
						}
					}
					case WitItem.FlagsDef ignored ->
						throw new UnsupportedOperationException(witPath + ":" + locations.lineOf(anchor) + ": '"
								+ member + "': the WIT type of " + what + " involves flags, which does not cross the "
								+ "component import boundary yet (its rontolisp representation is settled: a keyword "
								+ "list)");
					case WitItem.EnumDef ignored -> {
						// a keyword -- crosses
					}
					case WitItem.ResourceDef ignored -> {
						// a handle -- crosses
					}
					case null -> throw undefinedType(witPath, locations, anchor, member, what, named);
					default -> {
						// a type alias was already resolved above
					}
				}
			}
			default -> {
				// primitive scalars, bool, string, char -- all cross
			}
		}
	}

	/**
	 * The Lisp name a WIT function binds to, without a package qualifier: a freestanding
	 * function keeps its WIT label; a {@code resource} member is prefixed with the
	 * resource ({@code bucket.get} -> {@code bucket-get}, {@code constructor(bucket)} ->
	 * {@code bucket-new}), which keeps the flat, Lisp-2 function namespace unambiguous
	 * when two resources declare the same method.
	 * @param func the WIT function and its owning resource
	 * @return the bare Lisp function name
	 */
	public static String memberName(WitResolver.Func func) {
		String resource = func.resource();
		if (resource == null) {
			return func.def().name();
		}
		return switch (func.def().kind()) {
			case CONSTRUCTOR -> resource + "-new";
			case PLAIN, STATIC -> resource + "-" + func.def().name();
		};
	}

	// The lambda list of a binding. A resource method takes the handle as its leading
	// parameter (the WIT `self` receiver, which the model does not spell out).
	private static List<Param> parameters(WitResolver.Func func, String witPath, WitLocations locations,
			WitResolver resolver, WitItem.InterfaceDef iface, String member, boolean wasm, boolean component) {
		List<Param> params = new ArrayList<>();
		boolean method = func.resource() != null && func.def().kind() == WitItem.FuncKind.PLAIN;
		if (method) {
			params.add(new Param("self", ":int"));
		}
		for (var param : func.def().func().params()) {
			String designator = designatorOf(param.type(), witPath, locations, resolver, iface, func, member,
					"parameter '" + param.name() + "'", wasm, component);
			String name = param.name();
			if (method && "self".equals(name)) {
				throw new UnsupportedOperationException(witPath + ":" + locations.lineOf(func.def()) + ": '" + member
						+ "' declares a parameter named 'self', which collides with the resource handle rontolisp "
						+ "passes as the first argument of a method");
			}
			params.add(new Param(name, designator));
		}
		return params;
	}

	// The wasm-import :returns designator (":void" when the function returns nothing). A
	// constructor returns its resource, which the WIT model leaves implicit.
	private static String resultDesignator(WitResolver.Func func, String witPath, WitLocations locations,
			WitResolver resolver, WitItem.InterfaceDef iface, String member, boolean wasm) {
		if (func.def().kind() == WitItem.FuncKind.CONSTRUCTOR) {
			return ":int";
		}
		WitType result = func.def().func().result();
		if (result == null) {
			return ":void";
		}
		// resultDesignator is only reached on the non-component path (the component path
		// binds through the WIT text, not a flat designator), so component is always
		// false.
		return designatorOf(result, witPath, locations, resolver, iface, func, member, "the result", wasm, false);
	}

	// The one place a WIT type is judged. On the WASM boundary only the flat set
	// rontolisp:wasm-import can carry crosses; on the interpreter and the JVM the
	// boundary
	// is an ordinary Lisp function call, so every type crosses as its settled house
	// representation (WitTypeMapper) and only stream/future -- which have no rontolisp
	// value at all until language-level async lands -- are refused.
	private static String designatorOf(WitType type, String witPath, WitLocations locations, WitResolver resolver,
			WitItem.InterfaceDef iface, WitResolver.Func func, String member, String what, boolean wasm,
			boolean component) {
		WitTypeMapper.Rep rep = repOf(type, witPath, locations, resolver, iface, func, member, what);
		if (rep == WitTypeMapper.Rep.STREAM_HANDLE || rep == WitTypeMapper.Rep.FUTURE_HANDLE) {
			if (component) {
				// --component marshals a stream/future handle through the canonical ABI's
				// async built-ins (validated in validateComponentFunc); the flat
				// designator
				// is unused on the component path, which drives the wrapper off the WIT
				// text.
				return ":void";
			}
			throw new UnsupportedOperationException(witPath + ":" + locations.lineOf(func.def()) + ": '" + member
					+ "': the WIT type of " + what + " is a stream or a future, which only the --component backend can "
					+ "marshal (through the canonical ABI's async built-ins); the interpreter, the JVM and Preview 1 "
					+ "WASM have no rontolisp value for it");
		}
		if (rep == WitTypeMapper.Rep.UNSUPPORTED) {
			throw new UnsupportedOperationException(witPath + ":" + locations.lineOf(func.def()) + ": '" + member
					+ "': the WIT type of " + what + " has no rontolisp representation on any backend");
		}
		if (!wasm) {
			// The interpreter and the JVM pass LispVals straight through to the provider,
			// so the designator is unused there -- every representation is expressible.
			return ":void";
		}
		return switch (rep) {
			case INT, HANDLE -> ":int";
			case FLOAT -> ":float";
			case BOOLEAN -> ":bool";
			case STRING, BYTE_STRING -> ":string";
			// A Preview 1 core import is a bare host function: it carries flat values and
			// nothing else, because a core module has no component type with which to
			// declare a richer shape to its host. The canonical ABI is what marshals the
			// rich types, and it lives at the component boundary.
			default -> throw new UnsupportedOperationException(witPath + ":" + locations.lineOf(func.def()) + ": '"
					+ member + "': the WIT type of " + what + " does not cross the Preview 1 WASM import boundary, "
					+ "which carries the flat set (the integer scalars up to 32 bits, the float scalars, bool, "
					+ "string, list<u8> and resource handles). Its rontolisp representation is settled (" + rep.name()
					+ "): compile with --component, where the canonical ABI marshals it, or run on the interpreter "
					+ "or the JVM backend, which bind it through a provider");
		};
	}

	private static WitTypeMapper.Rep repOf(WitType type, String witPath, WitLocations locations, WitResolver resolver,
			WitItem.InterfaceDef iface, WitResolver.Func func, String member, String what) {
		// Aliases first: WitTypeMapper classifies a type STRUCTURALLY, so it cannot
		// follow
		// an alias whose target is itself a name (`type headers = fields`, which every
		// real
		// wasi:http interface writes) -- only the resolver can.
		Scoped scoped = resolveAliases(type, resolver, iface);
		WitType t = scoped.type();
		if (t instanceof WitType.Named named) {
			WitResolver.Owned owned = resolver.resolveOwned(scoped.iface(), named.name());
			if (owned == null) {
				throw undefinedType(witPath, locations, func.def(), member, what, named);
			}
			return WitTypeMapper.repOfDefinition(owned.item());
		}
		return WitTypeMapper.rep(t);
	}

	// (rontolisp:wasm-import 'name :from "module" :as "field" :params '(...) :returns
	// ...)
	private static LispVal wasmImportForm(String name, String module, String field, List<Param> params,
			String returns) {
		List<LispVal> designators = new ArrayList<>();
		for (Param param : params) {
			designators.add(new LispSymbol(param.designator()));
		}
		List<LispVal> out = new ArrayList<>();
		out.add(new LispSymbol(PackageRegistry.qualify(LispNames.RONTOLISP_PKG, LispNames.WASM_IMPORT)));
		out.add(quote(new LispSymbol(name)));
		out.add(new LispSymbol(":from"));
		out.add(new LispString(module));
		out.add(new LispSymbol(":as"));
		out.add(new LispString(field));
		out.add(new LispSymbol(":params"));
		out.add(quote(list(designators)));
		out.add(new LispSymbol(":returns"));
		out.add(new LispSymbol(returns));
		return list(out);
	}

	// (defun name (p ...) (rontolisp::%wit-call "iface" "member" p ...))
	private static LispVal providerDefun(String name, String iface, String member, List<Param> params) {
		List<LispVal> lambdaList = new ArrayList<>();
		List<LispVal> call = new ArrayList<>();
		call.add(new LispSymbol(PackageRegistry.qualifyInternal(LispNames.RONTOLISP_PKG, LispNames.WIT_CALL)));
		call.add(new LispString(iface));
		call.add(new LispString(member));
		for (Param param : params) {
			LispSymbol symbol = new LispSymbol(param.name());
			lambdaList.add(symbol);
			call.add(symbol);
		}
		return list(List.of(new LispSymbol(LispNames.DEFUN), new LispSymbol(name), list(lambdaList), list(call)));
	}

	// (defpackage kv (:use cl) (:export get set ...)) -- the bound names are the
	// package's
	// external symbols, so a call site spells them kv:get like any other package.
	private static LispVal defpackageForm(String pkg, Set<String> members) {
		List<LispVal> exports = new ArrayList<>();
		exports.add(new LispSymbol(":export"));
		for (String member : members) {
			exports.add(new LispSymbol(member));
		}
		LispVal use = list(List.of(new LispSymbol(":use"), new LispSymbol(LispNames.CL_PKG)));
		// The package name is the designator VERBATIM: rontolisp symbols are
		// case-preserving, so lowercasing it here while `PackageRegistry.qualify` keeps
		// the bindings' case defined `KV:open` in a package named `kv` -- and every call
		// site then failed to resolve with "No such package: KV".
		return list(List.of(new LispSymbol(LispNames.DEFPACKAGE), new LispSymbol(pkg), use, list(exports)));
	}

	// One bound parameter: its Lisp name and the wasm-import type designator it crosses
	// as
	// (unused on the interpreter/JVM path, where the boundary is a Lisp call).
	private record Param(String name, String designator) {
	}

	private static LispVal quote(LispVal value) {
		return new LispCons(new LispSymbol(LispNames.QUOTE), new LispCons(value, LispNil.INSTANCE));
	}

	private static LispVal list(List<LispVal> elements) {
		LispVal result = LispNil.INSTANCE;
		for (int i = elements.size() - 1; i >= 0; i--) {
			result = new LispCons(elements.get(i), result);
		}
		return result;
	}

}

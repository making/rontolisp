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
 * type crosses as {@code :s-expr} (printed on the way out, parsed by the embedded reader
 * on the way back); a {@code result}-returning function binds an internal raw import
 * whose host answers the {@code (:ok . V)} / {@code (:error . E)} envelope, unwrapped by
 * a public wrapper defun through {@code rontolisp::%wit-result} (whose error arm signals
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
 * wrapper split as Preview 1 for {@code result}-returning functions.</li>
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
			switch (keyword.name()) {
				case ":interface" -> iface = designator(value, ":interface", form);
				case ":package" -> pkg = designator(value, ":package", form);
				case ":from" -> module = designator(value, ":from", form);
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
			return switch (sym.name()) {
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
				List<Param> params = parameters(func, witPath, locations, resolver, iface, member, false);
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
			List<Param> params = parameters(func, witPath, locations, resolver, iface, member, wasm);
			String returns = resultDesignator(func, witPath, locations, resolver, iface, member, wasm);
			bindings.add(wasm ? wasmImportForm(name, module, directive.fieldStyle().apply(member), params, returns)
					: providerDefun(name, ifaceId, member, params));
		}
		if (boundMembers.isEmpty()) {
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

	// ("member" "lisp-name") -- one bound member of a %component-import form.
	private static LispVal memberBinding(String member, String lispName) {
		return list(List.of(new LispString(member), new LispString(lispName)));
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
		WitType resolved = resolveAliases(result, resolver, iface);
		return resolved instanceof WitType.ResultOf;
	}

	private static WitType resolveAliases(WitType type, WitResolver resolver, WitItem.InterfaceDef iface) {
		if (type instanceof WitType.Named named
				&& resolver.resolveType(iface, named.name()) instanceof WitItem.TypeAlias alias) {
			return resolveAliases(alias.target(), resolver, iface);
		}
		return type;
	}

	// --- component-boundary validation: errors name the WIT line ---

	// What the canonical-ABI wrapper codegen supports today. Parameters lower to flat
	// values, so the set is narrow (scalars, bool, string, list<u8>, handles, and option
	// of those); results lift recursively, so almost everything crosses -- only
	// stream/future (no rontolisp value at all) and flags are refused.
	private static void validateComponentFunc(WitResolver.Func func, String witPath, WitLocations locations,
			WitResolver resolver, WitItem.InterfaceDef iface, String member) {
		for (var param : func.def().func().params()) {
			validateComponentParam(param.type(), witPath, locations, resolver, iface, func, member,
					"parameter '" + param.name() + "'", true);
		}
		WitType result = func.def().func().result();
		if (result != null) {
			validateComponentResult(result, witPath, locations, resolver, iface, func, member, "the result");
		}
	}

	private static void validateComponentParam(WitType type, String witPath, WitLocations locations,
			WitResolver resolver, WitItem.InterfaceDef iface, WitResolver.Func func, String member, String what,
			boolean optionAllowed) {
		WitType t = resolveAliases(type, resolver, iface);
		if (t instanceof WitType.OptionOf opt && optionAllowed) {
			validateComponentParam(opt.element(), witPath, locations, resolver, iface, func, member, what, false);
			return;
		}
		WitTypeMapper.Rep rep = repOf(t, witPath, locations, resolver, iface, func, member, what);
		switch (rep) {
			case INT, BIGNUM_INT, FLOAT, BOOLEAN, STRING, BYTE_STRING, HANDLE -> {
				// crosses as a flat value
			}
			case UNSUPPORTED -> throw new UnsupportedOperationException(witPath + ":" + locations.lineOf(func.def())
					+ ": '" + member + "': the WIT type of " + what + " is a stream or a future, which has no "
					+ "rontolisp value on any backend (it needs language-level async)");
			default -> throw new UnsupportedOperationException(witPath + ":" + locations.lineOf(func.def()) + ": '"
					+ member + "': the WIT type of " + what + " does not cross the component import boundary as a "
					+ "parameter yet (supported: the integer and float scalars, bool, string, list<u8>, resource "
					+ "handles, and option of those). Its rontolisp representation is settled (" + rep.name()
					+ "), and the interpreter and the JVM backend bind it today");
		}
	}

	private static void validateComponentResult(WitType type, String witPath, WitLocations locations,
			WitResolver resolver, WitItem.InterfaceDef iface, WitResolver.Func func, String member, String what) {
		WitType t = resolveAliases(type, resolver, iface);
		switch (t) {
			case WitType.StreamOf ignored ->
				throw new UnsupportedOperationException(witPath + ":" + locations.lineOf(func.def()) + ": '" + member
						+ "': the WIT type of " + what + " is a stream or a future, which has no rontolisp value on "
						+ "any backend (it needs language-level async)");
			case WitType.FutureOf ignored ->
				throw new UnsupportedOperationException(witPath + ":" + locations.lineOf(func.def()) + ": '" + member
						+ "': the WIT type of " + what + " is a stream or a future, which has no rontolisp value on "
						+ "any backend (it needs language-level async)");
			case WitType.ListOf list ->
				validateComponentResult(list.element(), witPath, locations, resolver, iface, func, member, what);
			case WitType.OptionOf opt ->
				validateComponentResult(opt.element(), witPath, locations, resolver, iface, func, member, what);
			case WitType.ResultOf res -> {
				if (res.ok() != null) {
					validateComponentResult(res.ok(), witPath, locations, resolver, iface, func, member, what);
				}
				if (res.err() != null) {
					validateComponentResult(res.err(), witPath, locations, resolver, iface, func, member, what);
				}
			}
			case WitType.TupleOf tuple -> {
				for (WitType element : tuple.elements()) {
					validateComponentResult(element, witPath, locations, resolver, iface, func, member, what);
				}
			}
			case WitType.Named named -> {
				WitItem definition = resolver.resolveType(iface, named.name());
				switch (definition) {
					case WitItem.RecordDef record -> {
						for (WitItem.Field field : record.fields()) {
							validateComponentResult(field.type(), witPath, locations, resolver, iface, func, member,
									what);
						}
					}
					case WitItem.VariantDef variant -> {
						for (WitItem.Case c : variant.cases()) {
							if (c.payload() != null) {
								validateComponentResult(c.payload(), witPath, locations, resolver, iface, func, member,
										what);
							}
						}
					}
					case WitItem.FlagsDef ignored ->
						throw new UnsupportedOperationException(witPath + ":" + locations.lineOf(func.def()) + ": '"
								+ member + "': the WIT type of " + what + " involves flags, which does not cross the "
								+ "component import boundary yet (its rontolisp representation is settled: a keyword "
								+ "list)");
					case WitItem.EnumDef ignored -> {
						// a keyword -- crosses
					}
					case WitItem.ResourceDef ignored -> {
						// a handle -- crosses
					}
					case null -> throw new UnsupportedOperationException(witPath + ":" + locations.lineOf(func.def())
							+ ": '" + member + "': the WIT type of " + what + " is '" + named.name()
							+ "', which the file does not define (nor import with a use clause)");
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
			WitResolver resolver, WitItem.InterfaceDef iface, String member, boolean wasm) {
		List<Param> params = new ArrayList<>();
		boolean method = func.resource() != null && func.def().kind() == WitItem.FuncKind.PLAIN;
		if (method) {
			params.add(new Param("self", ":int"));
		}
		for (var param : func.def().func().params()) {
			String designator = designatorOf(param.type(), witPath, locations, resolver, iface, func, member,
					"parameter '" + param.name() + "'", wasm);
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
		return designatorOf(result, witPath, locations, resolver, iface, func, member, "the result", wasm);
	}

	// The one place a WIT type is judged. On the WASM boundary only the flat set
	// rontolisp:wasm-import can carry crosses; on the interpreter and the JVM the
	// boundary
	// is an ordinary Lisp function call, so every type crosses as its settled house
	// representation (WitTypeMapper) and only stream/future -- which have no rontolisp
	// value at all until language-level async lands -- are refused.
	private static String designatorOf(WitType type, String witPath, WitLocations locations, WitResolver resolver,
			WitItem.InterfaceDef iface, WitResolver.Func func, String member, String what, boolean wasm) {
		WitTypeMapper.Rep rep = repOf(type, witPath, locations, resolver, iface, func, member, what);
		if (rep == WitTypeMapper.Rep.UNSUPPORTED) {
			throw new UnsupportedOperationException(witPath + ":" + locations.lineOf(func.def()) + ": '" + member
					+ "': the WIT type of " + what + " is a stream or a future, which has no rontolisp value on any "
					+ "backend (it needs language-level async)");
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
		if (type instanceof WitType.Named named) {
			WitItem definition = resolver.resolveType(iface, named.name());
			if (definition == null) {
				throw new UnsupportedOperationException(witPath + ":" + locations.lineOf(func.def()) + ": '" + member
						+ "': the WIT type of " + what + " is '" + named.name()
						+ "', which the file does not define (nor import with a use clause)");
			}
			return WitTypeMapper.repOfDefinition(definition);
		}
		return WitTypeMapper.rep(type);
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

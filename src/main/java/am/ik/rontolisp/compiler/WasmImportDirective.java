package am.ik.rontolisp.compiler;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import org.jspecify.annotations.Nullable;

/**
 * A parsed {@code (rontolisp:wasm-import 'name :from "module" :as "field" :params '(...)
 * :returns ...)} directive, shared by the backends.
 *
 * <p>
 * The directive declares a host function imported into the compiled WASM module and
 * callable from Lisp like a top-level {@code defun}. This class only parses the generic
 * surface (name, import module/field, raw type designator keywords); the WASM backend
 * validates the designators against its supported boundary types, and the JVM backend
 * uses the name and arity to synthesize a stub that signals an error when called.
 *
 * @param name the Lisp-visible function name
 * @param module the WASM import module name ({@code :from}, default {@code "env"})
 * @param field the WASM import field name ({@code :as}, default the Lisp name)
 * @param paramTypes the raw parameter type designator keywords, in order
 * @param returnType the raw return type designator keyword, or {@code null} when omitted
 * / declared void
 * @param async whether the host function may SUSPEND ({@code :async t}): the call then
 * returns a future that {@code rontolisp:await} resolves, and the build states the host
 * obligations the suspension creates. The word deliberately matches the export side's
 * {@code :async} (WIT spells both directions {@code async func}); the directive itself
 * carries the direction
 */
public record WasmImportDirective(String name, String module, String field, List<String> paramTypes,
		@Nullable String returnType, boolean async) {

	/** The default import module name when {@code :from} is omitted. */
	public static final String DEFAULT_MODULE = "env";

	/**
	 * Returns whether the given form is a {@code (rontolisp:wasm-import ...)} directive.
	 * @param form the top-level form
	 * @return {@code true} if it is a rontolisp:wasm-import directive
	 */
	public static boolean isImportForm(LispVal form) {
		if (form instanceof LispCons cons && cons.car() instanceof LispSymbol sym) {
			var qn = PackageRegistry.splitQualified(sym.name());
			return qn != null && LispNames.RONTOLISP_PKG.equals(qn.pkg()) && LispNames.WASM_IMPORT.equals(qn.member());
		}
		return false;
	}

	/**
	 * Parses a directive form (in the canonical post-resolution shape
	 * {@code (rontolisp:wasm-import (quote name) :from "module" :as "field" :params
	 * (quote (...)) :returns :type)}).
	 * @param form the directive form
	 * @return the parsed directive
	 * @throws UnsupportedOperationException if the directive is malformed
	 */
	public static WasmImportDirective parse(LispCons form) {
		List<LispVal> items = form.toList();
		if (items.size() < 2) {
			throw new UnsupportedOperationException("Malformed rontolisp:wasm-import: " + form.print());
		}
		String name = quotedSymbolName(items.get(1), form);
		String module = DEFAULT_MODULE;
		@Nullable String field = null;
		@Nullable List<String> params = null;
		@Nullable String returns = null;
		boolean async = false;
		int i = 2;
		while (i < items.size()) {
			String keyword = keywordName(items.get(i), form);
			if (i + 1 >= items.size()) {
				throw new UnsupportedOperationException("Missing value for " + keyword + " in " + form.print());
			}
			LispVal value = items.get(i + 1);
			switch (keyword) {
				case ":FROM" -> module = stringValue(value, keyword, form);
				case ":AS" -> field = stringValue(value, keyword, form);
				case ":PARAMS" -> params = quotedKeywordList(value, form);
				case ":RETURNS" -> returns = returnKeyword(value, form);
				case ":ASYNC" -> async = booleanValue(value, keyword, form);
				default -> throw new UnsupportedOperationException(
						"Unknown rontolisp:wasm-import option " + keyword + " in " + form.print());
			}
			i += 2;
		}
		return new WasmImportDirective(name, module, field == null ? unqualifiedMember(name) : field,
				params == null ? List.of() : params, returns, async);
	}

	// The host-facing default field is the symbol's bare member name, lowercased: the
	// reader upcases Lisp symbols while host import fields are conventionally
	// lowercase, so the derivation maps DRAW back to "draw" (a package qualifier --
	// pkg:name from a directive inside a user package -- is Lisp-side spelling only).
	private static String unqualifiedMember(String name) {
		var qn = PackageRegistry.splitQualified(name);
		return (qn == null ? name : qn.member()).toLowerCase(java.util.Locale.ROOT);
	}

	private static String quotedSymbolName(LispVal value, LispCons form) {
		// (quote name) -> name
		if (value instanceof LispCons cons && cons.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
				&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name) {
			return name.name();
		}
		if (value instanceof LispSymbol sym && !sym.isKeyword()) {
			return sym.name();
		}
		throw new UnsupportedOperationException(
				"rontolisp:wasm-import expects a quoted function name in " + form.print() + ", got: " + value.print());
	}

	private static String keywordName(LispVal value, LispCons form) {
		if (value instanceof LispSymbol sym && sym.isKeyword()) {
			return sym.name();
		}
		throw new UnsupportedOperationException(
				"Expected a keyword option in " + form.print() + ", got: " + value.print());
	}

	// A :from / :as value is a string literal (or, leniently, a quoted symbol).
	private static String stringValue(LispVal value, String keyword, LispCons form) {
		if (value instanceof LispString str) {
			return str.value();
		}
		if (value instanceof LispCons cons && cons.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
				&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispSymbol name) {
			return name.name();
		}
		throw new UnsupportedOperationException("rontolisp:wasm-import " + keyword + " expects a string in "
				+ form.print() + ", got: " + value.print());
	}

	// An :async value is the literal t or nil -- the option is a compile-time fact
	// about the host boundary, so a computed value has nothing it could mean here.
	private static boolean booleanValue(LispVal value, String keyword, LispCons form) {
		if (value instanceof LispNil) {
			return false;
		}
		if (value instanceof am.ik.rontolisp.LispTrue) {
			return true;
		}
		if (value instanceof LispSymbol sym) {
			if ("T".equals(sym.name())) {
				return true;
			}
			if ("NIL".equals(sym.name())) {
				return false;
			}
		}
		throw new UnsupportedOperationException("rontolisp:wasm-import " + keyword + " expects t or nil in "
				+ form.print() + ", got: " + value.print());
	}

	// (quote (:t1 :t2 ...)) -> [":t1", ":t2", ...]; bare nil -> no parameters.
	private static List<String> quotedKeywordList(LispVal value, LispCons form) {
		if (value instanceof LispNil) {
			return List.of();
		}
		if (value instanceof LispCons cons && cons.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
				&& cons.cdr() instanceof LispCons rest) {
			List<String> result = new ArrayList<>();
			if (rest.car() instanceof LispCons list) {
				for (LispVal element : list.toList()) {
					if (element instanceof LispSymbol sym && sym.isKeyword()) {
						result.add(sym.name());
					}
					else {
						throw new UnsupportedOperationException("rontolisp:wasm-import :params expects keyword "
								+ "type designators in " + form.print() + ", got: " + element.print());
					}
				}
			}
			else if (!(rest.car() instanceof LispNil)) {
				throw new UnsupportedOperationException(
						"rontolisp:wasm-import :params expects a list in " + form.print());
			}
			return result;
		}
		throw new UnsupportedOperationException(
				"rontolisp:wasm-import :params expects a quoted list in " + form.print());
	}

	// nil, '() and :void all declare a void result (returned as nil to Lisp).
	private static @Nullable String returnKeyword(LispVal value, LispCons form) {
		if (value instanceof LispNil) {
			return null;
		}
		if (value instanceof LispCons cons && cons.car() instanceof LispSymbol q && LispNames.QUOTE.equals(q.name())
				&& cons.cdr() instanceof LispCons rest && rest.car() instanceof LispNil) {
			return null;
		}
		if (value instanceof LispSymbol sym && sym.isKeyword()) {
			String name = sym.name();
			return ":VOID".equals(name) ? null : name;
		}
		throw new UnsupportedOperationException("rontolisp:wasm-import :returns expects a keyword type designator in "
				+ form.print() + ", got: " + value.print());
	}

}

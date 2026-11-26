package am.ik.rontolisp.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceProvenance;
import am.ik.rontolisp.compiler.CallSiteWalk;
import am.ik.rontolisp.macro.LispMacroExpander;
import org.jspecify.annotations.Nullable;

/**
 * Lowers a {@code require} the inline pass did not consume -- one below the top level,
 * whose module name may be computed -- to what it can do in a compiled program, which
 * loads nothing at run time: a module the build provided (or {@code asdf}/{@code uiop})
 * answers its name like a consumed top-level {@code require}, any other one signals when
 * the call runs. queues' {@code make-queue} autoloads an unloaded queue system that way,
 * in a branch a program that loaded the system never takes. A {@code require} a macro
 * expands to is not seen here and stays the compilers' refusal.
 */
final class NestedRequires {

	/** The module name's variable in the lowered form. */
	private static final String MODULE_VAR = "%REQUIRE-MODULE";

	private NestedRequires() {
	}

	/**
	 * Lowers every {@code require} call in a code position of the program.
	 * @param program the inlined top-level forms
	 * @param provided the module names the inline pass provided
	 * @return the program, itself when it has no such call
	 */
	static List<LispVal> lower(List<LispVal> program, Set<String> provided) {
		List<LispVal> out = null;
		for (int i = 0; i < program.size(); i++) {
			LispVal form = program.get(i);
			LispVal lowered = lowerForm(form, provided);
			if (lowered != form && out == null) {
				out = new ArrayList<>(program.subList(0, i));
			}
			if (out != null) {
				out.add(lowered);
			}
		}
		return out == null ? program : out;
	}

	private static LispVal lowerForm(LispVal form, Set<String> provided) {
		return CallSiteWalk.walk(form, (cons, head) -> {
			if (!LispNames.REQUIRE.equals(LispMacroExpander.clOperatorSpelling(head.name()))) {
				return null;
			}
			List<LispVal> parts = cons.toList();
			if (parts.size() != 2 && parts.size() != 3) {
				return null;
			}
			LispVal pathname = parts.size() == 3 ? lowerForm(parts.get(2), provided) : null;
			return SourceProvenance.inherit(cons, lowered(lowerForm(parts.get(1), provided), pathname, provided));
		});
	}

	// (let ((%require-module (string MODULE))) [PATHNAME]
	// (cond ((string= %require-module "M") 'M) ... (t (error ...))))
	private static LispVal lowered(LispVal module, @Nullable LispVal pathname, Set<String> provided) {
		LispSymbol var = new LispSymbol(MODULE_VAR);
		List<LispVal> clauses = new ArrayList<>();
		// Sorted: the emitted program is deterministic.
		for (String name : new java.util.TreeSet<>(provided)) {
			clauses.add(list(list(sym(LispNames.STRING_EQ), var, new LispString(name)), quoted(name)));
		}
		// The modules AsdfSystems.isImplementationModule answers, case-insensitively.
		for (String implementation : List.of("ASDF", "UIOP")) {
			clauses.add(list(list(sym(LispNames.STRING_EQUAL), var, new LispString(implementation)),
					quoted(implementation)));
		}
		clauses.add(list(sym("T"), list(sym(LispNames.ERROR), new LispString(LispNames.REQUIRE
				+ " cannot load module ~A at run time on the compiled backends (modules are spliced at compile time)"),
				var)));
		List<LispVal> let = new ArrayList<>();
		let.add(sym(LispNames.LET));
		let.add(list(list(var, list(sym(LispNames.STRING), module))));
		if (pathname != null) {
			let.add(pathname);
		}
		let.add(cons(sym(LispNames.COND), clauses));
		return list(let.toArray(LispVal[]::new));
	}

	private static LispVal quoted(String name) {
		return list(sym(LispNames.QUOTE), sym(name));
	}

	private static LispSymbol sym(String name) {
		return new LispSymbol(name);
	}

	private static LispVal cons(LispVal head, List<LispVal> tail) {
		LispVal list = LispNil.INSTANCE;
		for (int i = tail.size() - 1; i >= 0; i--) {
			list = new LispCons(tail.get(i), list);
		}
		return new LispCons(head, list);
	}

	private static LispVal list(LispVal... items) {
		return cons(items[0], List.of(items).subList(1, items.length));
	}

}

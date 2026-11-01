package am.ik.rontolisp.macro;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispComplex;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.PackageRegistry;
import org.jspecify.annotations.Nullable;

/**
 * Template precompilation of the definitions a compiled program hands {@code compile} at
 * run time. A compiled program has no compiler: its {@code compile} evaluates
 * ({@code compile-runtime.lisp}), and the compiled {@code eval} parses no lambda-list
 * keywords ({@code .kb/eval-runtime.md}). The definitions a program compiles while it
 * DEFINES things -- contextl's layered-function callers,
 * {@code (compile nil `(lambda (&rest rest) ... (apply ,gf ...)))}, and the method
 * functions of the generic-function protocol's {@code ensure-method} -- come from code
 * the compile paths' macro-time evaluator already ran when it replayed those definitions.
 * Each definition compiled there becomes a registration form, placed before the top-level
 * form whose replay compiled it:
 *
 * <pre>
 * (%register-compiled-template 'PATTERN #'(lambda (HOLE...) DEFINITION'))
 * </pre>
 *
 * The PATTERN is the definition with a hole for every datum that is no literal -- an
 * object such as a generic function metaobject, a quoted list, a quoted uninterned symbol
 * -- and a numbered marker {@code (%template-gensym% k)} for every other uninterned
 * symbol, k counting the distinct ones (a gensym is a fresh one on every run, and a match
 * binds each k to one run-time uninterned symbol and no two k to the same one); the
 * FACTORY is the definition compiled as a closure over the holes. At run time
 * {@code compile} matches the definition it receives against the patterns and calls the
 * first match's factory on the hole values, so the function is compiled code closed over
 * the run-time objects. A definition no replay compiled is still evaluated.
 * <p>
 * The registration is read again in the package current where it is placed, so a bare
 * symbol the macro-time evaluator holds -- one of {@code cl-user}, or a canonical name of
 * an injected runtime -- is spelled {@code cl-user}-qualified, which reads back as the
 * same symbol; a {@code cl} name stays bare.
 */
public final class CompiledTemplates {

	private static final String HOLE_PREFIX = "#:%TEMPLATE-HOLE-";

	private CompiledTemplates() {
	}

	/**
	 * The registration form of a definition the macro-time evaluator compiled.
	 * @param definition the {@code (lambda ...)} definition handed to {@code compile}
	 * @return the {@code (%register-compiled-template 'pattern factory)} form
	 */
	public static LispVal registration(LispVal definition) {
		List<LispVal> holes = new ArrayList<>();
		LispVal[] split = split(definition, holes, new java.util.HashMap<>());
		LispVal factory = list(symbol(LispNames.FUNCTION), list(symbol(LispNames.LAMBDA), properList(holes), split[1]));
		return list(symbol(LispNames.REGISTER_COMPILED_TEMPLATE), list(symbol(LispNames.QUOTE), split[0]), factory);
	}

	/**
	 * The key two registrations of the same definition share: the printed pattern (their
	 * factories differ only in the names of the gensyms the pattern marks).
	 * @param registration a form {@link #registration} made
	 * @return the printed pattern
	 */
	public static String key(LispVal registration) {
		return ((LispCons) ((LispCons) registration).cdr()).car().print();
	}

	// The pattern and the factory code of one form; GENSYMS numbers the distinct
	// uninterned symbols met so far.
	private static LispVal[] split(LispVal form, List<LispVal> holes, java.util.Map<String, Integer> gensyms) {
		if (form instanceof LispCons cons) {
			LispVal whole = wholeForm(cons);
			if (whole != null) {
				if (!literal(whole)) {
					return hole(list(cons.car(), symbol(LispNames.TEMPLATE_HOLE)), holes);
				}
				LispVal quoted = whole instanceof LispSymbol symbol ? list(cons.car(), spelled(symbol)) : cons;
				return new LispVal[] { quoted, quoted };
			}
			List<LispVal> patterns = new ArrayList<>();
			List<LispVal> codes = new ArrayList<>();
			LispVal rest = cons;
			while (rest instanceof LispCons cell) {
				LispVal[] element = split(cell.car(), holes, gensyms);
				patterns.add(element[0]);
				codes.add(element[1]);
				rest = cell.cdr();
			}
			LispVal[] tail = split(rest, holes, gensyms);
			return new LispVal[] { dotted(patterns, tail[0]), dotted(codes, tail[1]) };
		}
		if (form instanceof LispSymbol symbol) {
			if (symbol.name().startsWith("#:")) {
				int index = gensyms.computeIfAbsent(symbol.name(), name -> gensyms.size());
				return new LispVal[] { list(symbol(LispNames.TEMPLATE_GENSYM), new LispInteger(index)), form };
			}
			LispVal spelled = spelled(symbol);
			return new LispVal[] { spelled, spelled };
		}
		return literal(form) ? new LispVal[] { form, form } : hole(symbol(LispNames.TEMPLATE_HOLE), holes);
	}

	// A symbol as the registration spells it: see the class comment.
	private static LispSymbol spelled(LispSymbol symbol) {
		String name = symbol.name();
		if (symbol.isKeyword() || PackageRegistry.splitQualified(name) != null || PackageRegistry.isClSymbol(name)) {
			return symbol;
		}
		return new LispSymbol(PackageRegistry.qualifyInternal(LispNames.CL_USER_PKG, name));
	}

	// The datum of a (quote X) -- and of a (function X) over no name or lambda
	// expression, which only an object spliced into the definition makes -- or null for
	// any other cons.
	private static @Nullable LispVal wholeForm(LispCons cons) {
		if (!(cons.car() instanceof LispSymbol op) || !(cons.cdr() instanceof LispCons rest)
				|| !(rest.cdr() instanceof LispNil)) {
			return null;
		}
		String member = LispSymbol.memberName(op.name());
		if (LispNames.isQuote(member)) {
			return rest.car();
		}
		if (LispNames.FUNCTION.equals(member) && !(rest.car() instanceof LispSymbol)
				&& !(rest.car() instanceof LispCons)) {
			return rest.car();
		}
		return null;
	}

	// A datum the definition spells: compiled into the factory and matched with equal.
	private static boolean literal(LispVal datum) {
		if (datum instanceof LispSymbol symbol) {
			return !symbol.name().startsWith("#:");
		}
		return datum instanceof LispNil || datum instanceof LispTrue || datum instanceof LispString
				|| datum instanceof LispChar || datum instanceof LispInteger || datum instanceof LispBigInteger
				|| datum instanceof LispRatio || datum instanceof LispDouble || datum instanceof LispComplex;
	}

	// A new hole: its pattern, and the factory parameter standing for its value.
	private static LispVal[] hole(LispVal pattern, List<LispVal> holes) {
		LispVal parameter = symbol(HOLE_PREFIX + holes.size());
		holes.add(parameter);
		return new LispVal[] { pattern, parameter };
	}

	private static LispSymbol symbol(String name) {
		return new LispSymbol(name);
	}

	private static LispVal list(LispVal... elements) {
		return properList(List.of(elements));
	}

	private static LispVal properList(List<LispVal> elements) {
		return dotted(elements, LispNil.INSTANCE);
	}

	private static LispVal dotted(List<LispVal> elements, LispVal tail) {
		LispVal result = tail;
		for (int i = elements.size() - 1; i >= 0; i--) {
			result = new LispCons(elements.get(i), result);
		}
		return result;
	}

}

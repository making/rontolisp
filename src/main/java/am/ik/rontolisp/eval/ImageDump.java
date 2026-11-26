package am.ik.rontolisp.eval;

import java.util.List;
import java.util.function.Function;

import am.ik.rontolisp.LispArray;
import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispFunction;
import am.ik.rontolisp.LispInstance;
import am.ik.rontolisp.LispIntVector;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispLambda;
import am.ik.rontolisp.LispLayout;
import am.ik.rontolisp.LispNames;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The program an interpreted image dumps ({@code uiop:dump-image}): what the image
 * loaded, then the start a restored image runs. A heap cannot be saved here, so the image
 * is written as SOURCE the compilers read -- each system the image's program asked for,
 * loaded as the target reads it, and {@code uiop:restore-image} of the values the start
 * needs. A value is carried only as source that evaluates to an equal one: a named
 * function as its name, a call designator {@code (FUNCTION ARGUMENT...)} as a function
 * applying it, data as its printed form. Anything else -- a closure, an instance -- is
 * refused by name rather than dropped, since the program would start differently without
 * it.
 */
final class ImageDump {

	private ImageDump() {
	}

	/**
	 * The dumped program's source.
	 * @param systems the systems the image's program asked for, in the order their loads
	 * finished
	 * @param entryPoint {@code uiop:*image-entry-point*} at the dump
	 * @param prelude {@code uiop:*image-prelude*} at the dump
	 * @param executable whether the image is an executable (what
	 * {@code uiop:*image-dumped-p*} answers in it)
	 * @param globalFunction the image's global function of a name, {@code null} for none
	 * @return the program
	 */
	static String program(List<String> systems, LispVal entryPoint, LispVal prelude, boolean executable,
			Function<String, @Nullable LispVal> globalFunction) {
		StringBuilder out = new StringBuilder();
		out.append(";;; Dumped by uiop:dump-image: the systems the image loaded, then the start\n");
		out.append(";;; a restored image runs.\n");
		for (String system : systems) {
			out.append("(asdf:load-system ").append(new LispString(system).print()).append(")\n");
		}
		out.append("(setf uiop:*image-dumped-p* ").append(executable ? ":executable" : "t").append(")\n");
		out.append("(uiop:restore-image");
		if (!(entryPoint instanceof LispNil)) {
			out.append(" :entry-point ").append(function(entryPoint, globalFunction, "*image-entry-point*"));
		}
		if (!(prelude instanceof LispNil)) {
			out.append(" :prelude '").append(datum(prelude, "*image-prelude*"));
		}
		return out.append(")\n").toString();
	}

	/**
	 * Source that evaluates to the function a designator means, as
	 * {@code uiop:ensure-function} reads it: a function the image names, a constant
	 * (answered by a function returning it), a symbol, a {@code lambda} form, or a call
	 * {@code (FUNCTION ARGUMENT...)} applying its car to its arguments and the call's. A
	 * string designator is read by the image before it gets here.
	 */
	private static String function(LispVal designator, Function<String, @Nullable LispVal> globalFunction,
			String variable) {
		switch (designator) {
			case LispSymbol symbol when symbol.isKeyword() -> {
				return "(constantly " + symbol.print() + ")";
			}
			case LispTrue t -> {
				return "(constantly t)";
			}
			case LispChar c -> {
				return "(constantly " + datum(c, variable) + ")";
			}
			case LispInteger n -> {
				return "(constantly " + datum(n, variable) + ")";
			}
			case LispSymbol symbol -> {
				return "(function " + symbol.print() + ")";
			}
			case LispLambda lambda -> {
				return "(function " + functionName(lambda.name(), lambda, globalFunction, variable) + ")";
			}
			case LispFunction builtin -> {
				return "(function " + functionName(builtin.name(), builtin, globalFunction, variable) + ")";
			}
			case LispCons cons when cons.isProperList() && cons.car() instanceof LispSymbol head
					&& LispNames.LAMBDA.equals(head.name()) -> {
				return datum(designator, variable);
			}
			case LispCons cons when cons.isProperList() -> {
				List<LispVal> items = cons.toList();
				StringBuilder call = new StringBuilder("(lambda (&rest %image-arguments) (apply ");
				call.append(function(items.get(0), globalFunction, variable));
				for (LispVal argument : items.subList(1, items.size())) {
					call.append(' ').append(argument(argument, globalFunction, variable));
				}
				return call.append(" %image-arguments))").toString();
			}
			default -> throw refusal(variable, designator);
		}
	}

	/** An argument of a call designator: a function by its name, anything else quoted. */
	private static String argument(LispVal value, Function<String, @Nullable LispVal> globalFunction, String variable) {
		if (value instanceof LispLambda || value instanceof LispFunction) {
			return function(value, globalFunction, variable);
		}
		return "'" + datum(value, variable);
	}

	/** The name a function value is the global definition of, or the refusal. */
	private static String functionName(@Nullable String name, LispVal function,
			Function<String, @Nullable LispVal> globalFunction, String variable) {
		if (name == null || globalFunction.apply(name) != function) {
			throw refusal(variable, function);
		}
		return new LispSymbol(name).print();
	}

	/** A value's printed form, which the reader reads back as an equal value. */
	private static String datum(LispVal value, String variable) {
		if (!readable(value)) {
			throw refusal(variable, value);
		}
		return value.print();
	}

	/** Whether a value prints as source that reads back as an equal value. */
	private static boolean readable(LispVal value) {
		LispVal cell = value;
		while (cell instanceof LispCons cons) {
			if (!readable(cons.car())) {
				return false;
			}
			cell = cons.cdr();
		}
		if (cell instanceof LispArray array) {
			if (array.dimensions().length != 1) {
				return false;
			}
			for (int i = 0; i < array.effectiveLength(); i++) {
				if (!readable(array.readFlat(i))) {
					return false;
				}
			}
			return true;
		}
		return cell instanceof LispSymbol || cell instanceof LispNil || cell instanceof LispTrue
				|| cell instanceof LispString || cell instanceof LispChar || cell instanceof LispInteger
				|| cell instanceof LispBigInteger || cell instanceof LispRatio || cell instanceof LispDouble
				|| cell instanceof LispIntVector
				|| (cell instanceof LispInstance instance && instance.layout().kind() == LispLayout.Kind.PATHNAME);
	}

	private static LispEvalException refusal(String variable, LispVal value) {
		return new LispEvalException("UIOP:DUMP-IMAGE: " + variable + " is " + value.print()
				+ ", which the dumped program cannot carry: it names its entry point -- a symbol, a function"
				+ " defined by name, a lambda form or a (function argument...) list of those and data --"
				+ " and a program-op system names it with :entry-point");
	}

}

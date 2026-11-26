package am.ik.rontolisp.clojure;

import java.util.List;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The {@code java.time} builder calls a program makes where the host is not (wasm):
 * malli's load builds two {@code DateTimeFormatter}s at top level out of a
 * {@code DateTimeFormatterBuilder} chain, which never runs there. The construction and
 * the chain evaluate to portable markers -- the builder answers itself per call,
 * {@code toFormatter} the formatter, the {@code ofPattern} and {@code ZoneId/of} statics
 * their own -- so the load runs; anything that would use one ({@code .parse},
 * {@code .format}, {@code Date/from} over a parse) keeps the existing oracle-words
 * refusal, like every unmapped call. Only the members the load names lower this way; any
 * other member stays the host's own.
 *
 * <p>
 * One slice of {@link ClojureLowering}.
 */
final class ClojureDateBuilderLowering {

	/** The builder class. */
	static final String BUILDER = "java.time.format.DateTimeFormatterBuilder";

	/** The formatter class. */
	static final String FORMATTER = "java.time.format.DateTimeFormatter";

	/** The zone class. */
	static final String ZONE_ID = "java.time.ZoneId";

	/** The field class. */
	static final String CHRONO_FIELD = "java.time.temporal.ChronoField";

	private ClojureDateBuilderLowering() {
	}

	/**
	 * A zero-argument {@code DateTimeFormatterBuilder} construction where the host is
	 * not: the builder marker. The callers try it only off a host target; anything else
	 * is null, so the call keeps its {@code java:new} shape.
	 * @param cls the resolved class name
	 * @param argCount the argument count
	 * @return the marker form, or null
	 */
	static @Nullable LispVal construction(String cls, int argCount) {
		if (argCount == 0 && BUILDER.equals(cls)) {
			return builderMarker();
		}
		return null;
	}

	/**
	 * A {@code DateTimeFormatter/ofPattern} call of one pattern, or a {@code ZoneId/of}
	 * call of one name, where the host is not: the formatter or zone marker. The callers
	 * try it only off a host target; anything else is null, so the call keeps its
	 * {@code java:static} shape.
	 * @param cls the class
	 * @param member the static member
	 * @param args the lowered arguments
	 * @return the marker form, or null
	 */
	static @Nullable LispVal staticCall(String cls, String member, List<LispVal> args) {
		if (args.size() == 1 && FORMATTER.equals(cls) && member.equals("ofPattern")) {
			return formatterMarker();
		}
		if (args.size() == 1 && ZONE_ID.equals(cls) && member.equals("of")) {
			return zoneMarker();
		}
		return null;
	}

	/**
	 * A {@code ChronoField} constant the load reads where the host is not: its marker,
	 * which the builder calls take as an evaluated argument. The callers try it only off
	 * a host target; anything else is null, so the read keeps its {@code java:field}
	 * shape.
	 * @param cls the class
	 * @param member the field
	 * @return the marker form, or null
	 */
	static @Nullable LispVal field(String cls, String member) {
		if (!CHRONO_FIELD.equals(cls)) {
			return null;
		}
		return switch (member) {
			case "MICRO_OF_SECOND", "HOUR_OF_DAY", "OFFSET_SECONDS" -> chronoMarker(member);
			default -> null;
		};
	}

	/**
	 * A builder or formatter call over an already-bound receiver where the host is not: a
	 * builder answers itself per call, {@code toFormatter} the formatter marker,
	 * {@code withZone} of a formatter the formatter, so the {@code ->} chain malli's load
	 * threads evaluates to the marker; any other receiver keeps the arm it had (the
	 * oracle-words refusal). The caller tries it only off a host target.
	 * @param recv the bound receiver
	 * @param method the method name
	 * @param args the lowered arguments
	 * @param arm the arm for any other receiver
	 * @return the form
	 */
	static LispVal methodArm(LispSymbol recv, String method, List<LispVal> args, LispVal arm) {
		String key = method + "/" + args.size();
		boolean builderCall = switch (key) {
			case "appendPattern/1", "optionalStart/0", "optionalEnd/0", "appendFraction/4", "appendOffset/2",
					"parseDefaulting/2" ->
				true;
			default -> false;
		};
		if (builderCall) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), builderP(recv), runArgs(args, recv), arm);
		}
		if (key.equals("toFormatter/0")) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), builderP(recv), runArgs(args, formatterMarker()),
					arm);
		}
		if (key.equals("withZone/1")) {
			return ClojureLowerUtil.list(ClojureLowerUtil.sym("if"), formatterP(recv), runArgs(args, recv), arm);
		}
		return arm;
	}

	/** The arguments evaluated for their effects, then the answer. */
	private static LispVal runArgs(List<LispVal> args, LispVal answer) {
		if (args.isEmpty()) {
			return answer;
		}
		List<LispVal> progn = new java.util.ArrayList<>();
		progn.add(ClojureLowerUtil.sym("progn"));
		progn.addAll(args);
		progn.add(answer);
		return ClojureLowerUtil.list(progn);
	}

	/** Whether the value is the builder marker. */
	private static LispVal builderP(LispVal recv) {
		return markerP(recv, ":C%DATE-FORMATTER-BUILDER");
	}

	/** Whether the value is the formatter marker. */
	private static LispVal formatterP(LispVal recv) {
		return markerP(recv, ":C%DATE-FORMATTER");
	}

	/** Whether the value is a {@code (list TAG ...)} marker. */
	private static LispVal markerP(LispVal recv, String tag) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("and"),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("consp"), recv),
				ClojureLowerUtil.list(ClojureLowerUtil.sym("eq"),
						ClojureLowerUtil.list(ClojureLowerUtil.sym("car"), recv), new LispSymbol(tag)));
	}

	/** The builder marker: {@code (list :C%DATE-FORMATTER-BUILDER)}. */
	private static LispVal builderMarker() {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), new LispSymbol(":C%DATE-FORMATTER-BUILDER"));
	}

	/** The formatter marker: {@code (list :C%DATE-FORMATTER)}. */
	private static LispVal formatterMarker() {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), new LispSymbol(":C%DATE-FORMATTER"));
	}

	/** The zone marker: {@code (list :C%ZONE-ID)}. */
	private static LispVal zoneMarker() {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), new LispSymbol(":C%ZONE-ID"));
	}

	/** A field marker: {@code (list :C%CHRONO-FIELD name)}. */
	private static LispVal chronoMarker(String member) {
		return ClojureLowerUtil.list(ClojureLowerUtil.sym("list"), new LispSymbol(":C%CHRONO-FIELD"),
				LispString.literal(member));
	}

}

package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import org.jspecify.annotations.Nullable;

/**
 * The {@code java.util.GregorianCalendar} chain tools.reader's {@code #inst} reader runs
 * ({@code .kb/clojure-frontend.md}, "Portable GregorianCalendar"): where the host is not
 * (wasm) a construction is a Calendar of this front end's own, mutated in place like the
 * host object ({@code clojure.lisp} "Portable GregorianCalendar"), a {@code Calendar}
 * field constant its integer and {@code TimeZone/getTimeZone} of a fixed offset a zone
 * value -- never a {@code java:} call, which wasm refuses. Where the host is, those stay
 * the host's. A Calendar's {@code set}, {@code setTimeZone}, {@code get}, {@code getTime}
 * and {@code getTimeInMillis} answer on every backend ({@link #methodCall}), so a
 * {@code clojure.instant} Calendar takes them too.
 *
 * <p>
 * One slice of {@link ClojureLowering}.
 */
final class ClojurePortableCalendarLowering {

	private ClojurePortableCalendarLowering() {
	}

	/**
	 * The classes a Calendar of this front end's own may stand for: a receiver known to
	 * be one takes the value arms.
	 */
	static final Set<String> CLASSES = Set.of("java.util.Calendar", "java.util.GregorianCalendar");

	private static final String GREGORIAN = "java.util.GregorianCalendar";

	/** A construction of the Calendar of this front end's own. */
	static final String NEW = "RONTOLISP::%CLOJURE-CALENDAR-NEW";

	/** A construction of the Calendar of the current instant. */
	static final String NOW = "RONTOLISP::%CLOJURE-CALENDAR-NOW";

	private static final String TIME_ZONE_OF = "RONTOLISP::%CLOJURE-TIME-ZONE-OF";

	/** The {@code int} constants of {@code java.util.Calendar}, by name. */
	private static final Map<String, Integer> FIELDS = Map.ofEntries(Map.entry("ERA", 0), Map.entry("YEAR", 1),
			Map.entry("MONTH", 2), Map.entry("WEEK_OF_YEAR", 3), Map.entry("WEEK_OF_MONTH", 4), Map.entry("DATE", 5),
			Map.entry("DAY_OF_MONTH", 5), Map.entry("DAY_OF_YEAR", 6), Map.entry("DAY_OF_WEEK", 7),
			Map.entry("DAY_OF_WEEK_IN_MONTH", 8), Map.entry("AM_PM", 9), Map.entry("HOUR", 10),
			Map.entry("HOUR_OF_DAY", 11), Map.entry("MINUTE", 12), Map.entry("SECOND", 13),
			Map.entry("MILLISECOND", 14), Map.entry("ZONE_OFFSET", 15), Map.entry("DST_OFFSET", 16),
			Map.entry("FIELD_COUNT", 17), Map.entry("SUNDAY", 1), Map.entry("MONDAY", 2), Map.entry("TUESDAY", 3),
			Map.entry("WEDNESDAY", 4), Map.entry("THURSDAY", 5), Map.entry("FRIDAY", 6), Map.entry("SATURDAY", 7),
			Map.entry("JANUARY", 0), Map.entry("FEBRUARY", 1), Map.entry("MARCH", 2), Map.entry("APRIL", 3),
			Map.entry("MAY", 4), Map.entry("JUNE", 5), Map.entry("JULY", 6), Map.entry("AUGUST", 7),
			Map.entry("SEPTEMBER", 8), Map.entry("OCTOBER", 9), Map.entry("NOVEMBER", 10), Map.entry("DECEMBER", 11),
			Map.entry("UNDECIMBER", 12), Map.entry("AM", 0), Map.entry("PM", 1), Map.entry("ALL_STYLES", 0),
			Map.entry("SHORT", 1), Map.entry("LONG", 2), Map.entry("NARROW_FORMAT", 4),
			Map.entry("NARROW_STANDALONE", 32772), Map.entry("SHORT_FORMAT", 1), Map.entry("LONG_FORMAT", 2),
			Map.entry("SHORT_STANDALONE", 32769), Map.entry("LONG_STANDALONE", 32770));

	/**
	 * A {@code Calendar} (or {@code GregorianCalendar}) constant as its integer, and
	 * {@code GregorianCalendar}'s {@code BC} and {@code AD}, where the host is not: the
	 * caller checks the target.
	 * @param cls the class
	 * @param member the field
	 * @return the value, or null when this is no such field
	 */
	static @Nullable LispVal field(String cls, String member) {
		if (!CLASSES.contains(cls)) {
			return null;
		}
		Integer value = FIELDS.get(member);
		if (value == null && cls.equals(GREGORIAN)) {
			value = switch (member) {
				case "BC" -> 0;
				case "AD" -> 1;
				default -> null;
			};
		}
		return value == null ? null : new LispInteger(value);
	}

	/**
	 * An untagged {@code GregorianCalendar} construction of none, three, five or six
	 * arguments as the Calendar of this front end's own, where the host is not: the
	 * caller checks the target and that it is untagged. The omitted time fields are 0.
	 * @param cls the class
	 * @param args the lowered arguments
	 * @return the construction, or null for any other class or count
	 */
	static @Nullable LispVal construction(String cls, List<LispVal> args) {
		if (!cls.equals(GREGORIAN)) {
			return null;
		}
		if (args.isEmpty()) {
			return ClojureLowerUtil.list(new LispSymbol(NOW));
		}
		if (args.size() != 3 && args.size() != 5 && args.size() != 6) {
			return null;
		}
		List<LispVal> call = new ArrayList<>();
		call.add(new LispSymbol(NEW));
		call.addAll(args);
		while (call.size() < 7) {
			call.add(new LispInteger(0));
		}
		return ClojureLowerUtil.list(call);
	}

	/**
	 * {@code TimeZone/getTimeZone} of one argument as the zone value, where the host is
	 * not: the caller checks the target.
	 * @param cls the class
	 * @param member the static member
	 * @param args the lowered arguments
	 * @return the call, or null for any other member or count
	 */
	static @Nullable LispVal staticCall(String cls, String member, List<LispVal> args) {
		if (!cls.equals("java.util.TimeZone") || !member.equals("getTimeZone") || args.size() != 1) {
			return null;
		}
		return ClojureLowerUtil.list(new LispSymbol(TIME_ZONE_OF), args.get(0));
	}

	/**
	 * The call of a Calendar's own method over a bound receiver, or null when the method
	 * at that count is none of {@code getTime}, {@code getTimeInMillis}, {@code get},
	 * {@code set} (two, three, five or six arguments) and {@code setTimeZone}. The caller
	 * gates it on the Calendar test.
	 * @param method the method name
	 * @param recv the bound receiver
	 * @param args the lowered (or bound) arguments
	 * @return the call, or null
	 */
	static @Nullable LispVal methodCall(String method, LispSymbol recv, List<LispVal> args) {
		String worker = switch (method + "/" + args.size()) {
			case "getTime/0" -> "RONTOLISP::%CLOJURE-CALENDAR-GET-TIME";
			case "getTimeInMillis/0" -> "RONTOLISP::%CLOJURE-CALENDAR-COMPLETE";
			case "get/1" -> "RONTOLISP::%CLOJURE-CALENDAR-GET";
			case "set/2" -> "RONTOLISP::%CLOJURE-CALENDAR-SET";
			case "set/3", "set/5", "set/6" -> "RONTOLISP::%CLOJURE-CALENDAR-SET-FIELDS";
			case "setTimeZone/1" -> "RONTOLISP::%CLOJURE-CALENDAR-SET-TIME-ZONE";
			default -> null;
		};
		if (worker == null) {
			return null;
		}
		List<LispVal> call = new ArrayList<>();
		call.add(new LispSymbol(worker));
		call.add(recv);
		if (worker.endsWith("-SET-FIELDS")) {
			call.add(ClojureLowerUtil.cons(ClojureLowerUtil.sym("list"), args));
		}
		else {
			call.addAll(args);
		}
		return ClojureLowerUtil.list(call);
	}

}

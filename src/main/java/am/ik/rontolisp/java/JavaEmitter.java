package am.ik.rontolisp.java;

import java.util.List;
import java.util.Set;

import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * What a call of an intrinsic member of the library lowers to: the member's own form over
 * the forms of its receiver and arguments.
 */
@FunctionalInterface
interface JavaEmitter {

	/**
	 * A lowered expression.
	 *
	 * @param form its form
	 * @param type its static type
	 * @param constant its value when it is a constant expression: an {@code Integer}, a
	 * {@code Long}, a {@code Double}, a {@code Boolean}, a {@code Character} or a
	 * {@code String}
	 */
	record Typed(LispVal form, JavaType type, @Nullable Object constant) {

		Typed(LispVal form, JavaType type) {
			this(form, type, null);
		}

	}

	/**
	 * A call of an intrinsic member.
	 *
	 * @param receiver what it is called on, or {@code null} for a static member or a
	 * constructor
	 * @param arguments the arguments: each form converted to its parameter's type, each
	 * type the argument's own
	 * @param discard whether the call's value is not used
	 * @param pos the call's position
	 */
	record Invocation(@Nullable Typed receiver, List<Typed> arguments, boolean discard, int pos) {

		LispVal self() {
			return java.util.Objects.requireNonNull(this.receiver).form();
		}

		LispVal argument(int index) {
			return this.arguments.get(index).form();
		}

	}

	/** What a member asks of the lowering it is called from. */
	interface Context {

		/** A value as text, as string concatenation makes it. */
		LispVal stringOf(Typed value, int pos);

		/**
		 * A call of one of {@code Object}'s methods no class of the receiver declares.
		 */
		LispVal objectMethod(String signature, Typed receiver, Invocation call);

		LispReadException unsupported(int pos, String what);

		/** A variable of its own, for a value a member reads twice. */
		LispSymbol temporary();

		/**
		 * The function answering any value's {@code toString()}: what a member taking
		 * values of any class makes their text with.
		 */
		LispVal stringFunction();

		/** The function answering any value's {@code hashCode()}. */
		LispVal hashFunction();

		/**
		 * The function answering a conversion of {@code String.format} for a number
		 * written by its own digits (a {@code BigInteger}, a {@code BigDecimal}),
		 * {@code nil} in a program that makes no such number.
		 */
		LispVal exactFunction();

		/**
		 * A call of a static method of the library's Java source: a member written in
		 * Java, found by its class, its name and how many arguments it takes.
		 */
		LispVal library(String className, String name, List<LispVal> arguments);

		/**
		 * Whether a class under a class of the library's Java source declares an instance
		 * method of one of these names: what a member of that class asks before it pays
		 * for calling them. The program is closed, so the answer is the program's.
		 */
		boolean declaresBelow(String className, Set<String> names);

		/**
		 * A call of an instance member with no parameter of a class whose values are Lisp
		 * values, found by its signature: the body the receiver's class has for it where
		 * a class of Java source can be under that class, else the member's own form.
		 */
		LispVal member(String className, String signature, @Nullable Typed receiver);

		/**
		 * A read ({@code get}), a write ({@code set}) or a swap ({@code swap}) of the
		 * field a field updater holds the name of: a call of the program's function
		 * choosing the field by that name, the first of the arguments.
		 */
		LispVal updated(String operation, List<LispVal> arguments);

		/**
		 * An array made of another's class, as {@code Arrays.copyOf} makes one: in a
		 * program that records the class of an array, with the original's.
		 */
		LispVal arrayLike(LispVal made, LispVal original);

		/**
		 * A member's form run holding its receiver's monitor, the receiver and the
		 * arguments evaluated ahead of it; the member's own form in a program that cannot
		 * tell a monitor held from one that is not.
		 */
		LispVal holding(Invocation call, JavaEmitter member);

		/**
		 * Notes that the program names a zone where this is called: a region of the tz
		 * database named by a constant is one the program holds, and a name it computes
		 * where it runs ({@code null} here) makes it hold them all. A call written in the
		 * front end's own library names no zone of the program's.
		 */
		void namesZone(@Nullable String name);

		/**
		 * Notes that the program reads a zoned text where this is called: the regions a
		 * constant text names are ones it holds, and a text it computes where it runs
		 * ({@code null} here) makes it hold them all. Only a file of the program reads a
		 * zone so: a library of its source path may reach a parse it never makes.
		 */
		void readsZones(@Nullable String text);

		/** Whether the program holds a region of the tz database. */
		boolean holdsRegions();

		/** The table of the regions the program holds, as its library reads it. */
		LispVal regions();

		/**
		 * The names in words of the regions the program holds, as its library reads them:
		 * the names it writes, or the names it reads ({@code JavaZoneNames}).
		 */
		LispVal zoneNames(boolean read);

		/**
		 * Whether the program can hold a {@code TemporalAccessor} of a class outside
		 * {@code java.time} itself: a formatter's result, a class of its own.
		 */
		boolean foreignTemporals();

		/**
		 * Whether the program reads the static field whose initializer is being lowered.
		 */
		boolean wantedField();

		/**
		 * Whether the program can ask a date or a time for its calendar: it reaches
		 * {@code TemporalQueries.chronology()}, the one way to the question.
		 */
		boolean asksCalendars();

		/**
		 * Notes the pattern of a formatter the program makes where this is called: the
		 * letters of a constant one are ones its patterns can name, and one computed
		 * where it runs ({@code null} here) can name them all.
		 */
		void namesPattern(@Nullable String pattern);

		/** Whether a pattern of the program can name one of these letters. */
		boolean namesLetter(String letters);

		/** Whether the program makes an instance of the library's class of this name. */
		boolean makesClass(String name);

		/**
		 * Whether the program makes a {@code TemporalField} that is no
		 * {@code ChronoField}: one of {@code IsoFields}, {@code JulianFields},
		 * {@code WeekFields} or its own.
		 */
		boolean makesOtherFields();

		/**
		 * Whether the program reaches a method of the library's Java source, by its class
		 * and name ({@code java.time.format.DateTimeFormatter.withDecimalStyle}): one the
		 * library watches ({@code JavaIntrinsics}), the first reach lowering the program
		 * again.
		 */
		boolean reaches(String member);

		/**
		 * What {@code getResourceAsStream} of a class object answers: the stream of the
		 * resource found beside the class it stands for at run time, or {@code null}.
		 */
		LispVal classResource(Invocation call);

		/**
		 * What {@code getResourceAsStream} of the class loader, or
		 * {@code getSystemResourceAsStream}, answers.
		 */
		LispVal loaderResource(Invocation call);

		/**
		 * What {@code getClassLoader} of a class object answers: the loader of the class
		 * it stands for at run time, or {@code null}.
		 */
		LispVal classLoader(Invocation call);

		/**
		 * The files a resource name computed where the program runs may find, as the
		 * library reads them ({@link JavaResourceTable}): none in a program that computes
		 * no name.
		 */
		LispVal resourceTable();

		/**
		 * What {@code ResourceBundle.getBundle} answers: the bundle the loader of the
		 * class calling it finds, or the one given, among the files carried.
		 */
		LispVal bundle(Invocation call);

		/**
		 * What {@code ServiceLoader.load} or {@code loadInstalled} answers: a loader of
		 * the providers the service's files name, found while the program is lowered.
		 * @param kind 0 {@code load(Class)}, 1 {@code load(Class, ClassLoader)}, 2
		 * {@code loadInstalled(Class)}
		 */
		LispVal services(Invocation call, int kind);

		/**
		 * The bundle {@code ResourceBundle.getBundle} makes of a class it finds by its
		 * name, or {@code null}.
		 */
		LispVal classBundle(Invocation call);

	}

	LispVal emit(Context context, Invocation invocation);

}

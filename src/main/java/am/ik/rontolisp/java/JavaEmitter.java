package am.ik.rontolisp.java;

import java.util.List;

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

		/**
		 * A call of a static method of the library's Java source: a member written in
		 * Java, found by its class, its name and how many arguments it takes.
		 */
		LispVal library(String className, String name, List<LispVal> arguments);

		/**
		 * An array made of another's class, as {@code Arrays.copyOf} makes one: in a
		 * program that records the class of an array, with the original's.
		 */
		LispVal arrayLike(LispVal made, LispVal original);

	}

	LispVal emit(Context context, Invocation invocation);

}

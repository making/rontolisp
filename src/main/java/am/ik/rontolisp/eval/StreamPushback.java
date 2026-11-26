package am.ik.rontolisp.eval;

import java.util.function.UnaryOperator;

import am.ik.rontolisp.LispInstance;
import am.ik.rontolisp.LispLayout;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispTrue;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.macro.LispMacroExpander;

/**
 * The interpreter's handle-side pushback of {@code unread-char}: the character parked for
 * a stream until the next character read drains it. No stream the interpreter holds can
 * be un-read (a {@code BufferedReader}'s mark budget is the peek's), so the reads consult
 * this before touching the stream. The compile paths answer the same contract in Lisp
 * ({@code unread-char.lisp}), which is what keeps the four backends identical.
 *
 * <p>
 * WHERE the character lives: on the stream the designator DENOTES, so every designator of
 * one stream reaches one cell. An omitted stream and {@code nil} denote the current
 * {@code *standard-input*}, and a synonym stream the stream its variable holds now. An
 * open stream VALUE carries the character in its own reserved cell
 * ({@link LispLayout#STREAM_PUSHBACK_CELL}), as CL keeps the pushback on the stream -- so
 * a stream closed or dropped with a character parked takes it along, and two streams can
 * each hold one. Anything else the reads accept (the {@code t} designator, the process
 * standard input) shares ONE cell, keyed by the designator.
 */
final class StreamPushback {

	/**
	 * The stream a designator denotes, resolved by {@code Environment}: an omitted stream
	 * (nil) the current {@code *standard-input*}, a synonym stream its target.
	 */
	private final UnaryOperator<LispVal> denoted;

	/** The designator owning the shared cell, nil when the cell is empty. */
	private LispVal sharedKey = LispNil.INSTANCE;

	private LispVal sharedChar = LispNil.INSTANCE;

	/**
	 * @param denoted the stream a designator denotes, nil when nothing is installed for
	 * an omitted stream
	 */
	StreamPushback(UnaryOperator<LispVal> denoted) {
		this.denoted = denoted;
	}

	/**
	 * The character parked for {@code stream}, LEFT in place, or nil when none is.
	 * @param stream the stream argument as given (nil for an omitted one)
	 * @return the parked character, or nil
	 */
	LispVal parked(LispVal stream) {
		return parkedAt(key(stream));
	}

	/**
	 * The character parked for {@code stream}, DRAINED, or nil when none is.
	 * @param stream the stream argument as given (nil for an omitted one)
	 * @return the parked character, or nil
	 */
	LispVal take(LispVal stream) {
		LispVal key = key(stream);
		LispVal parked = parkedAt(key);
		if (!(parked instanceof LispNil)) {
			store(key, LispNil.INSTANCE);
		}
		return parked;
	}

	private LispVal parkedAt(LispVal key) {
		if (key instanceof LispInstance value && value.hasTag(LispLayout.STREAM_TAG)) {
			return value.slot(LispLayout.STREAM_PUSHBACK_CELL);
		}
		return this.sharedKey.equals(key) ? this.sharedChar : LispNil.INSTANCE;
	}

	/**
	 * Parks {@code character} for {@code stream}. A second unread with the cell still
	 * full signals: CL calls two unreads without an intervening read an error.
	 * @param stream the stream argument as given (nil for an omitted one)
	 * @param character the character to park
	 */
	void push(LispVal stream, LispVal character) {
		LispVal key = key(stream);
		boolean full = key instanceof LispInstance value && value.hasTag(LispLayout.STREAM_TAG)
				? !(value.slot(LispLayout.STREAM_PUSHBACK_CELL) instanceof LispNil)
				: !(this.sharedKey instanceof LispNil);
		if (full) {
			throw new LispEvalException(LispMacroExpander.UNREAD_CHAR_TWICE_MESSAGE);
		}
		store(key, character);
	}

	private void store(LispVal key, LispVal character) {
		if (key instanceof LispInstance value && value.hasTag(LispLayout.STREAM_TAG)) {
			value.setSlot(LispLayout.STREAM_PUSHBACK_CELL, character);
			return;
		}
		// An EMPTY shared cell is nil in both halves: the key nil folds onto t, so
		// no live key is ever nil and the two states cannot be confused.
		this.sharedKey = character instanceof LispNil ? LispNil.INSTANCE : key;
		this.sharedChar = character;
	}

	// The stream the designator denotes. A nil left over (no evaluator installed
	// *standard-input*) is the process standard input, the t designator.
	private LispVal key(LispVal stream) {
		LispVal target = this.denoted.apply(stream);
		return target instanceof LispNil ? LispTrue.INSTANCE : target;
	}

}

package am.ik.rontolisp.eval;

import java.io.Closeable;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;

import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispInstance;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispLayout;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.compiler.StreamDesignators;
import am.ik.rontolisp.runtime.RontoStringInputStream;

/**
 * The interpreter's string INPUT streams as the readtable-honoring reads see them
 * ({@code .kb/readtables.md}): a reader macro function is handed a stream over the text
 * being read, positioned where the macro character left it, and a {@code read} it makes
 * reads that text in place. The stream table lives in {@code Environment.createGlobal},
 * which hands this view out with its designator resolution.
 *
 * @param streams the interpreter's stream table
 * @param nextHandle its handle allocator
 * @param target the handle a stream designator acts on ({@code Environment.streamTarget})
 * @param denoted the stream VALUE a designator denotes, through synonym streams
 * ({@code Environment.synonymTarget})
 */
record StringStreamTable(Map<Long, Closeable> streams, AtomicLong nextHandle, UnaryOperator<LispVal> target,
		UnaryOperator<LispVal> denoted) {

	/**
	 * A string input stream over a text, positioned at a UTF-16 offset.
	 * @param text the text
	 * @param offset where the stream reads from
	 * @return the stream value
	 */
	LispVal open(String text, int offset) {
		RontoStringInputStream reader = new RontoStringInputStream(text);
		reader.moveCursor(offset);
		long handle = this.nextHandle.getAndIncrement();
		this.streams.put(handle, reader);
		return StreamDesignators.streamValue(handle, LispLayout.Kinds.STRING_INPUT);
	}

	/**
	 * The string input stream a designator denotes, or {@code null} for any other one.
	 * @param designator a stream designator
	 * @return the stream's reader
	 */
	@Nullable RontoStringInputStream reader(@Nullable LispVal designator) {
		if (designator == null || !(this.target.apply(designator) instanceof LispInteger handle)) {
			return null;
		}
		return this.streams.get(handle.value()) instanceof RontoStringInputStream reader ? reader : null;
	}

	/**
	 * Where a string input stream stands, the character {@code unread-char} parked on it
	 * counted back: the offset a read of its text in place starts at.
	 * @param designator the stream designator
	 * @param reader its reader
	 * @return the UTF-16 offset
	 */
	int logicalOffset(LispVal designator, RontoStringInputStream reader) {
		LispVal parked = this.denoted.apply(designator) instanceof LispInstance value
				&& value.hasTag(LispLayout.STREAM_TAG) ? value.slot(LispLayout.STREAM_PUSHBACK_CELL) : LispNil.INSTANCE;
		return reader.cursor() - (parked instanceof LispChar c ? Character.charCount(c.codePoint()) : 0);
	}

	/**
	 * Leaves a string input stream at an offset, with nothing parked on it.
	 * @param designator the stream designator
	 * @param reader its reader
	 * @param offset the UTF-16 offset
	 */
	void moveTo(LispVal designator, RontoStringInputStream reader, int offset) {
		if (this.denoted.apply(designator) instanceof LispInstance value && value.hasTag(LispLayout.STREAM_TAG)) {
			value.setSlot(LispLayout.STREAM_PUSHBACK_CELL, LispNil.INSTANCE);
		}
		reader.moveCursor(offset);
	}

	/**
	 * Drops a stream {@link #open} made from the table.
	 * @param stream the stream value
	 */
	void close(LispVal stream) {
		if (this.target.apply(stream) instanceof LispInteger handle) {
			this.streams.remove(handle.value());
		}
	}

}

package am.ik.rontolisp.codegen.wasm;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * The UTF-8 decoder behind every CHARACTER read off a WASI descriptor --
 * {@code _read_char}, {@code _peek_char}, {@code _read_line} (through {@code _read_char})
 * and the block arm of {@code _read_seq_chars} -- and the descriptor's byte pushback it
 * needs ({@code .kb/character-sequence-io.md}, "Malformed input").
 *
 * <p>
 * The rule is the JVM's: Java's UTF-8 decoder, which the interpreter and the JVM backend
 * read every character stream through, replaces each MAXIMAL ill-formed prefix with one
 * U+FFFD. A byte that leads no sequence ({@code 80..C1}, {@code F5..FF}) is one U+FFFD; a
 * lead whose sequence a byte outside its continuation range interrupts is one U+FFFD for
 * the bytes before that byte, which then starts the next character; a sequence end of
 * file cuts short is one U+FFFD; the second byte's range is narrowed after {@code E0} (no
 * overlong), {@code F0} (no overlong) and {@code F4} (nothing past {@code U+10FFFF}); and
 * a complete three-byte sequence encoding a surrogate is one U+FFFD. Pinned against
 * {@code InputStreamReader} over every sequence of up to five bytes drawn from the
 * boundary bytes of each range.
 *
 * <p>
 * The byte that interrupts a sequence has been READ by the time it is known to interrupt
 * it, and a descriptor cannot be un-read, so it goes into the descriptor's pushback: up
 * to four bytes in {@link WasmLispCompiler#PUSHBACK_BYTES_ADDR} (the front in the low
 * byte), owned by the descriptor {@link WasmLispCompiler#PUSHBACK_KEY_ADDR} names
 * ({@code (fd + 1) << 3 | count}, 0 = empty). {@code peek-char} parks the bytes it read
 * there too, so a peek is the next read's input verbatim. One descriptor at a time: a
 * push for another replaces it.
 */
final class WasmUtf8StreamDecoder {

	/** U+FFFD REPLACEMENT CHARACTER. */
	static final int REPLACEMENT = 0xFFFD;

	private WasmUtf8StreamDecoder() {
	}

	/**
	 * The i32 locals {@link #emitDecode} works in.
	 *
	 * @param b0 the lead byte, set by the caller before the decode
	 * @param b each further byte, set by the fetch
	 * @param cp the answer: the code point, or U+FFFD
	 * @param need the length the lead byte announces
	 * @param k how many bytes of the sequence are in hand
	 * @param n the answer: how many bytes the character consumed
	 * @param off the answer: 1 when {@code b} holds a fetched byte the character did NOT
	 * consume (it starts the next character), else 0
	 * @param lo the low bound of the next byte's range
	 * @param range the width of the next byte's range
	 */
	record Locals(int b0, int b, int cp, int need, int k, int n, int off, int lo, int range) {
	}

	/**
	 * Emits one character's decode from the lead byte in {@code l.b0()}: sets
	 * {@code l.cp()}, {@code l.n()} and {@code l.off()}. {@code fetch} sets {@code l.b()}
	 * to byte {@code l.k()} of the sequence, or -1 at end of input; it is called at most
	 * three times, in order, and must be a self-contained instruction sequence.
	 */
	static void emitDecode(WasmWriter w, Locals l, Runnable fetch) {
		w.write(Instruction.BLOCK, 0x40); // $done
		// ASCII: itself.
		get(w, l.b0());
		i32(w, 0x80);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.IF, 0x40);
		answer(w, l, () -> get(w, l.b0()), 1, 0);
		w.write(Instruction.BR, 1);
		w.write(Instruction.END);
		// need = C2..DF -> 2, E0..EF -> 3, F0..F4 -> 4, any other byte -> 0
		leadRange(w, l.b0(), 0xC2, 0xDF);
		w.write(Instruction.IF);
		w.write(Type.I32);
		i32(w, 2);
		w.write(Instruction.ELSE);
		leadRange(w, l.b0(), 0xE0, 0xEF);
		w.write(Instruction.IF);
		w.write(Type.I32);
		i32(w, 3);
		w.write(Instruction.ELSE);
		leadRange(w, l.b0(), 0xF0, 0xF4);
		w.write(Instruction.IF);
		w.write(Type.I32);
		i32(w, 4);
		w.write(Instruction.ELSE);
		i32(w, 0);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.END);
		set(w, l.need());
		// A byte that leads no sequence is one U+FFFD.
		get(w, l.need());
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		answer(w, l, () -> i32(w, REPLACEMENT), 1, 0);
		w.write(Instruction.BR, 1);
		w.write(Instruction.END);
		// cp = b0's payload bits: 0xFF >> (need + 1) masks 5, 4 or 3 of them.
		get(w, l.b0());
		i32(w, 0xFF);
		get(w, l.need());
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_SHR_U);
		w.write(Instruction.I32_AND);
		set(w, l.cp());
		// The second byte's range: A0..BF after E0, 90..BF after F0, 80..8F after F4,
		// 80..BF otherwise (and for every byte after it).
		selectOnLead(w, l.b0(), 0xA0, 0x90, 0x80, 0x80);
		set(w, l.lo());
		selectOnLead(w, l.b0(), 0x1F, 0x2F, 0x0F, 0x3F);
		set(w, l.range());
		i32(w, 1);
		set(w, l.k());
		w.write(Instruction.BLOCK, 0x40); // $complete
		w.write(Instruction.LOOP, 0x40); // $more
		get(w, l.k());
		get(w, l.need());
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF, 1);
		fetch.run();
		// End of input inside the sequence: what came of it is one U+FFFD.
		get(w, l.b());
		i32(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, 0x40);
		answer(w, l, () -> i32(w, REPLACEMENT), -1, 0);
		w.write(Instruction.BR, 3);
		w.write(Instruction.END);
		// A byte outside the range: U+FFFD for the bytes before it; it starts the next.
		get(w, l.b());
		get(w, l.lo());
		w.write(Instruction.I32_SUB);
		get(w, l.range());
		w.write(Instruction.I32_GT_U);
		w.write(Instruction.IF, 0x40);
		answer(w, l, () -> i32(w, REPLACEMENT), -1, 1);
		w.write(Instruction.BR, 3);
		w.write(Instruction.END);
		// cp = cp << 6 | b & 0x3F ; the range is the plain continuation range from here
		get(w, l.cp());
		i32(w, 6);
		w.write(Instruction.I32_SHL);
		get(w, l.b());
		i32(w, 0x3F);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_OR);
		set(w, l.cp());
		i32(w, 0x80);
		set(w, l.lo());
		i32(w, 0x3F);
		set(w, l.range());
		get(w, l.k());
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		set(w, l.k());
		w.write(Instruction.BR, 0);
		w.write(Instruction.END); // $more
		w.write(Instruction.END); // $complete
		get(w, l.need());
		set(w, l.n());
		i32(w, 0);
		set(w, l.off());
		// A complete sequence encoding a surrogate (only a three-byte one can) is one
		// U+FFFD, as Java decodes it.
		get(w, l.cp());
		i32(w, -0x800);
		w.write(Instruction.I32_AND);
		i32(w, 0xD800);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		i32(w, REPLACEMENT);
		set(w, l.cp());
		w.write(Instruction.END);
		w.write(Instruction.END); // $done
	}

	/**
	 * Emits {@code target = } the next byte of the descriptor in {@code fdLocal}, or -1
	 * at end of file (or on a read error): the front of its pushback when it holds one,
	 * else one byte off the descriptor through {@code BYTE_SCRATCH_ADDR}.
	 */
	static void emitNextByte(WasmWriter w, int fdLocal, int target) {
		emitHeld(w, fdLocal);
		w.write(Instruction.IF, 0x40);
		// target = bytes & 0xFF ; bytes >>= 8 ; key = count == 1 ? 0 : key - 1
		load(w, WasmLispCompiler.PUSHBACK_BYTES_ADDR);
		i32(w, 0xFF);
		w.write(Instruction.I32_AND);
		set(w, target);
		i32(w, WasmLispCompiler.PUSHBACK_BYTES_ADDR);
		load(w, WasmLispCompiler.PUSHBACK_BYTES_ADDR);
		i32(w, 8);
		w.write(Instruction.I32_SHR_U);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		i32(w, WasmLispCompiler.PUSHBACK_KEY_ADDR);
		i32(w, 0);
		load(w, WasmLispCompiler.PUSHBACK_KEY_ADDR);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		load(w, WasmLispCompiler.PUSHBACK_KEY_ADDR);
		i32(w, 7);
		w.write(Instruction.I32_AND);
		i32(w, 1);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.SELECT);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		w.write(Instruction.ELSE);
		// iov = (BYTE_SCRATCH, 1) ; errno = fd_read(fd, iov, 1, nread)
		i32(w, WasmLispCompiler.IOV_OFFSET);
		i32(w, WasmLispCompiler.BYTE_SCRATCH_ADDR);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		i32(w, WasmLispCompiler.IOV_OFFSET + 4);
		i32(w, 1);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		get(w, fdLocal);
		i32(w, WasmLispCompiler.IOV_OFFSET);
		i32(w, 1);
		i32(w, WasmLispCompiler.NWRITTEN_OFFSET);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_FD_READ);
		// target = errno == 0 && nread != 0 ? mem_u8[BYTE_SCRATCH] : -1
		w.write(Instruction.I32_EQZ);
		load(w, WasmLispCompiler.NWRITTEN_OFFSET);
		i32(w, 0);
		w.write(Instruction.I32_NE);
		w.write(Instruction.I32_AND);
		set(w, target);
		i32(w, WasmLispCompiler.BYTE_SCRATCH_ADDR);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		i32(w, -1);
		get(w, target);
		w.write(Instruction.SELECT);
		set(w, target);
		w.write(Instruction.END);
	}

	/**
	 * Emits the push of {@code count} bytes (1..4, the front in the low byte) to the
	 * FRONT of the pushback of the descriptor in {@code fdLocal}, ahead of what it still
	 * holds; a pushback another descriptor owned is replaced. The caller keeps the total
	 * at four bytes or fewer: it only ever pushes back bytes it took, plus the one byte
	 * it read past them.
	 */
	static void emitPushFront(WasmWriter w, int fdLocal, Runnable value, Runnable count) {
		emitHeld(w, fdLocal);
		w.write(Instruction.IF, 0x40);
		// bytes = value | bytes << (count * 8) ; key += count
		i32(w, WasmLispCompiler.PUSHBACK_BYTES_ADDR);
		value.run();
		load(w, WasmLispCompiler.PUSHBACK_BYTES_ADDR);
		count.run();
		i32(w, 3);
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		i32(w, WasmLispCompiler.PUSHBACK_KEY_ADDR);
		load(w, WasmLispCompiler.PUSHBACK_KEY_ADDR);
		count.run();
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		w.write(Instruction.ELSE);
		// bytes = value ; key = (fd + 1) << 3 | count
		i32(w, WasmLispCompiler.PUSHBACK_BYTES_ADDR);
		value.run();
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		i32(w, WasmLispCompiler.PUSHBACK_KEY_ADDR);
		get(w, fdLocal);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		i32(w, 3);
		w.write(Instruction.I32_SHL);
		count.run();
		w.write(Instruction.I32_OR);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		w.write(Instruction.END);
	}

	/** Pushes whether the pushback holds bytes of the descriptor in {@code fdLocal}. */
	static void emitHeld(WasmWriter w, int fdLocal) {
		load(w, WasmLispCompiler.PUSHBACK_KEY_ADDR);
		i32(w, 3);
		w.write(Instruction.I32_SHR_U);
		get(w, fdLocal);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_EQ);
	}

	/**
	 * Pushes how many bytes the pushback holds for the descriptor in {@code fdLocal}:
	 * read off it but not yet consumed, so {@code file-position} subtracts them.
	 */
	static void emitHeldCount(WasmWriter w, int fdLocal) {
		load(w, WasmLispCompiler.PUSHBACK_KEY_ADDR);
		i32(w, 7);
		w.write(Instruction.I32_AND);
		i32(w, 0);
		emitHeld(w, fdLocal);
		w.write(Instruction.SELECT);
	}

	/**
	 * Emits the drop of the pushback when it holds bytes of the descriptor in
	 * {@code fdLocal}: they belong to a position the descriptor has left, or to a
	 * descriptor being closed (whose number the next open may be handed).
	 */
	static void emitDropHeld(WasmWriter w, int fdLocal) {
		emitHeld(w, fdLocal);
		w.write(Instruction.IF, 0x40);
		i32(w, WasmLispCompiler.PUSHBACK_KEY_ADDR);
		i32(w, 0);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		w.write(Instruction.END);
	}

	// cp = value ; n = consumed (-1: k, the bytes in hand) ; off = offender
	private static void answer(WasmWriter w, Locals l, Runnable value, int consumed, int offender) {
		value.run();
		set(w, l.cp());
		if (consumed < 0) {
			get(w, l.k());
		}
		else {
			i32(w, consumed);
		}
		set(w, l.n());
		i32(w, offender);
		set(w, l.off());
	}

	// pushes (b - from) <=u (to - from): b in [from, to]
	private static void leadRange(WasmWriter w, int bLocal, int from, int to) {
		get(w, bLocal);
		i32(w, from);
		w.write(Instruction.I32_SUB);
		i32(w, to - from);
		w.write(Instruction.I32_LE_U);
	}

	// pushes b0 == E0 ? e0 : b0 == F0 ? f0 : b0 == F4 ? f4 : other
	private static void selectOnLead(WasmWriter w, int b0Local, int e0, int f0, int f4, int other) {
		i32(w, e0);
		i32(w, f0);
		i32(w, f4);
		i32(w, other);
		get(w, b0Local);
		i32(w, 0xF4);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.SELECT);
		get(w, b0Local);
		i32(w, 0xF0);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.SELECT);
		get(w, b0Local);
		i32(w, 0xE0);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.SELECT);
	}

	private static void get(WasmWriter w, int slot) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(slot);
	}

	private static void set(WasmWriter w, int slot) {
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(slot);
	}

	private static void i32(WasmWriter w, int value) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(value);
	}

	private static void load(WasmWriter w, int addr) {
		i32(w, addr);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
	}

}

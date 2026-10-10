package am.ik.rontolisp.runtime;

/**
 * A streaming DEFLATE decoder (RFC 1951) with the zlib (RFC 1950) and gzip (RFC 1952)
 * wrappers: the interpreter's and the JVM backend's {@code rontolisp::%inflate-new},
 * {@code %inflate-update} and {@code %inflate-finish}, whose wasm half is the Lisp
 * decoder {@code inflate.lisp} ({@code .kb/fetch-http.md}, "Decompression"). Both are
 * written the same way, unit for unit, and report what {@code java.util.zip} reports --
 * the decoders babashka.http-client reads a compressed reply through: zlib's messages for
 * malformed data, {@code GZIPInputStream}'s for a gzip header and trailer, and which
 * input was cut short where.
 *
 * <p>
 * Pure Java, not {@code java.util.zip}: an {@code Inflater} is native zlib, which the
 * browser playground's Web Image does not carry, and a decoder of our own is the one the
 * Lisp decoder can be pinned to step for step. Input is fed a chunk at a time; a unit (a
 * block header, a symbol, a match, a header or trailer) that the input runs out inside is
 * put back and decoded again when more arrives, so output never depends on where the
 * chunks were cut.
 *
 * <p>
 * The static methods speak the JVM backend's value representation (an octet vector is the
 * bare {@code byte[]} of its octets, a string its quote-wrapped text, an integer a
 * {@code Long}, nil {@code null}); the class imports nothing, so it travels beside a
 * compiled program that decompresses ({@code .kb/jvm-export.md}, "What travels").
 */
public final class RontoInflate {

	/** A raw DEFLATE stream. */
	public static final int RAW = 0;

	/** A zlib stream: a two-octet header, DEFLATE data, the Adler-32 of the data. */
	public static final int ZLIB = 1;

	/** A gzip stream: members of a header, DEFLATE data, the CRC-32 and size. */
	public static final int GZIP = 2;

	/** {@link #finish()}: a gzip header or trailer is cut short. */
	public static final int SHORT_HEADER = 1;

	/** {@link #finish()}: the compressed data is cut short. */
	public static final int SHORT_DATA = 2;

	private static final int HEADER = 0;

	private static final int BLOCK = 1;

	private static final int STORED = 2;

	private static final int CODES = 3;

	private static final int CHECK = 4;

	private static final int NEXT = 5;

	private static final int DONE = 6;

	private static final int[] LENGTH_BASE = { 3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59,
			67, 83, 99, 115, 131, 163, 195, 227, 258 };

	private static final int[] LENGTH_EXTRA = { 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4,
			5, 5, 5, 5, 0 };

	private static final int[] DIST_BASE = { 1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513,
			769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577 };

	private static final int[] DIST_EXTRA = { 0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10,
			11, 11, 12, 12, 13, 13 };

	private static final int[] ORDER = { 16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15 };

	private static final int[] CRC_TABLE = crcTable();

	/** The input running out inside a unit, which puts the unit back. */
	private static final Stop UNDERFLOW = new Stop("");

	/** The fixed literal/length code. */
	private static final Code FIXED_LENS = fixedLengths();

	/** The fixed distance code: 32 codes of five bits, the last two invalid. */
	private static final Code FIXED_DISTS = fixedDistances();

	private static final byte[] NO_OCTETS = new byte[0];

	private final int kind;

	private int phase;

	private byte[] in = new byte[0];

	private int pos;

	private int hold;

	private int bits;

	private int markPos;

	private int markHold;

	private int markBits;

	private final byte[] window = new byte[32768];

	private int wpos;

	/** The octets of history behind the window position, at most 32768. */
	private int have;

	private boolean last;

	private Code lens = FIXED_LENS;

	private Code dists = FIXED_DISTS;

	private int remaining;

	private int copyLen;

	private int copyDist;

	/** zlib: the Adler-32 sums (a, b); gzip: the CRC-32 register. */
	private int checkA;

	private int checkB;

	private int crc;

	/** gzip: the member's size modulo 2^32. */
	private int size;

	/**
	 * The message of the malformed stream this decoder found, empty while it has none.
	 */
	private String error = "";

	private byte[] out = NO_OCTETS;

	private int opos;

	private int sumFrom;

	/**
	 * A decoder of the given kind.
	 * @param kind {@link #RAW}, {@link #ZLIB} or {@link #GZIP}
	 */
	public RontoInflate(int kind) {
		if (kind < RAW || kind > GZIP) {
			throw new IllegalArgumentException("%inflate-new: no such kind of compressed data: " + kind);
		}
		this.kind = kind;
		this.phase = (kind == RAW) ? BLOCK : HEADER;
		this.checkA = 1;
	}

	/**
	 * {@code %inflate-new} in the JVM backend's representation.
	 * @param kind the kind, a {@code Long}
	 * @return the decoder
	 */
	public static Object create(Object kind) {
		return new RontoInflate(((Long) kind).intValue());
	}

	/**
	 * {@code %inflate-update} in the JVM backend's representation.
	 * @param state the decoder
	 * @param octets the next compressed octets, an octet vector
	 * @param limit the most octets to answer, or {@code null} for no limit
	 * @return an octet vector, or the quote-wrapped message of a malformed stream
	 */
	public static Object update(Object state, Object octets, Object limit) {
		byte[] input = (byte[]) octets;
		Object answer = ((RontoInflate) state).update(input, 0, input.length,
				(limit == null) ? -1 : ((Long) limit).intValue());
		if (answer instanceof String message) {
			return "\"" + message + "\"";
		}
		return answer;
	}

	/**
	 * {@code %inflate-finish} in the JVM backend's representation.
	 * @param state the decoder
	 * @return {@code null}, or the {@code Long} of what is cut short
	 */
	// nil is null in the compiled representation; this package cannot import the
	// annotation that would say so (it would travel with the class).
	@SuppressWarnings("NullAway")
	public static Object finish(Object state) {
		int f = ((RontoInflate) state).finish();
		return (f == 0) ? null : Long.valueOf(f);
	}

	/**
	 * The octets the compressed input given so far, with {@code len} octets of
	 * {@code octets} from {@code off} appended, makes decodable.
	 * @param octets the next compressed octets
	 * @param off where they start
	 * @param len how many there are
	 * @param limit the most octets to answer, or a negative value for no limit (0 reads
	 * the header and stops)
	 * @return the decoded octets, or the message of a malformed stream, after which the
	 * decoder answers that message again
	 */
	public Object update(byte[] octets, int off, int len, int limit) {
		if (!this.error.isEmpty()) {
			return this.error;
		}
		int left = this.in.length - this.pos;
		if (left == 0) {
			this.in = java.util.Arrays.copyOfRange(octets, off, off + len);
		}
		else if (len > 0) {
			byte[] joined = new byte[left + len];
			System.arraycopy(this.in, this.pos, joined, 0, left);
			System.arraycopy(octets, off, joined, left, len);
			this.in = joined;
		}
		else if (this.pos > 0) {
			this.in = java.util.Arrays.copyOfRange(this.in, this.pos, this.in.length);
		}
		this.pos = 0;
		this.out = new byte[Math.max(1024, 4 * this.in.length)];
		this.opos = 0;
		this.sumFrom = 0;
		try {
			run(limit);
		}
		catch (Stop stop) {
			if (!stop.message.isEmpty()) {
				this.out = NO_OCTETS;
				this.error = stop.message;
				return this.error;
			}
			this.pos = this.markPos;
			this.hold = this.markHold;
			this.bits = this.markBits;
		}
		sum();
		byte[] answer = java.util.Arrays.copyOf(this.out, this.opos);
		this.out = NO_OCTETS;
		return answer;
	}

	/**
	 * Whether the compressed input given so far is whole.
	 * @return 0 when it is, {@link #SHORT_HEADER} when a gzip header or trailer is cut
	 * short, {@link #SHORT_DATA} when the compressed data is
	 */
	public int finish() {
		if (this.phase == DONE || this.phase == NEXT) {
			return 0;
		}
		if (this.kind == GZIP && (this.phase == HEADER || this.phase == CHECK)) {
			return SHORT_HEADER;
		}
		return SHORT_DATA;
	}

	private void run(int limit) {
		while (true) {
			mark();
			if (limit >= 0 && this.opos >= limit && this.phase != HEADER) {
				return;
			}
			if (this.copyLen > 0) {
				copy(limit);
				continue;
			}
			switch (this.phase) {
				case CODES -> {
					if (limit < 0 && this.pos + 10 <= this.in.length) {
						fast();
					}
					else {
						codes(limit);
					}
				}
				case BLOCK -> block();
				case STORED -> stored(limit);
				case HEADER -> header();
				case CHECK -> check();
				case NEXT -> next();
				default -> {
					// done: whatever follows is not read
					this.pos = this.in.length;
					return;
				}
			}
		}
	}

	private void mark() {
		this.markPos = this.pos;
		this.markHold = this.hold;
		this.markBits = this.bits;
	}

	private void need(int n) {
		while (this.bits < n) {
			if (this.pos >= this.in.length) {
				throw UNDERFLOW;
			}
			this.hold |= (this.in[this.pos++] & 0xff) << this.bits;
			this.bits += 8;
		}
	}

	private int bits(int n) {
		need(n);
		int v = this.hold & ((1 << n) - 1);
		this.hold >>>= n;
		this.bits -= n;
		return v;
	}

	private int octet() {
		return bits(8);
	}

	private void align() {
		int drop = this.bits & 7;
		this.hold >>>= drop;
		this.bits -= drop;
	}

	private static Stop fail(String message) {
		return new Stop(message);
	}

	private void header() {
		if (this.kind == ZLIB) {
			int cmf = octet();
			int flg = octet();
			if ((cmf * 256 + flg) % 31 != 0) {
				throw fail("incorrect header check");
			}
			if ((cmf & 15) != 8) {
				throw fail("unknown compression method");
			}
			if ((cmf >> 4) + 8 > 15) {
				throw fail("invalid window size");
			}
			if ((flg & 32) != 0) {
				// a preset dictionary's id, after which java.util.zip reads nothing
				for (int i = 0; i < 4; i++) {
					octet();
				}
				this.phase = DONE;
			}
			else {
				this.phase = BLOCK;
			}
			return;
		}
		int start = this.pos;
		int b0 = octet();
		if (b0 != 0x1f || octet() != 0x8b) {
			throw fail("Not in GZIP format");
		}
		if (octet() != 8) {
			throw fail("Unsupported compression method");
		}
		int flg = octet();
		for (int i = 0; i < 6; i++) {
			octet();
		}
		if ((flg & 4) != 0) {
			int m = octet();
			m += 256 * octet();
			for (int i = 0; i < m; i++) {
				octet();
			}
		}
		if ((flg & 8) != 0) {
			while (octet() != 0) {
				// the file name
			}
		}
		if ((flg & 16) != 0) {
			while (octet() != 0) {
				// the comment
			}
		}
		if ((flg & 2) != 0) {
			int v = ~crc(~0, this.in, start, this.pos) & 0xffff;
			int got = octet();
			got += 256 * octet();
			if (v != got) {
				throw fail("Corrupt GZIP header");
			}
		}
		// a member: its check, size and window start afresh
		this.crc = ~0;
		this.size = 0;
		this.have = 0;
		this.last = false;
		this.phase = BLOCK;
	}

	private void next() {
		// the header of a gzip member after the first: none (the end), or one not to be
		// read, ends the stream, as GZIPInputStream ignores a malformed tail
		if (this.pos >= this.in.length) {
			throw UNDERFLOW;
		}
		try {
			header();
		}
		catch (Stop stop) {
			if (stop.message.isEmpty()) {
				throw stop;
			}
			this.phase = DONE;
		}
	}

	private void check() {
		sum();
		align();
		if (this.kind == RAW) {
			this.phase = DONE;
			return;
		}
		if (this.kind == ZLIB) {
			int b0 = octet();
			int b1 = octet();
			int b2 = octet();
			int b3 = octet();
			if (b0 * 256 + b1 != this.checkB || b2 * 256 + b3 != this.checkA) {
				throw fail("incorrect data check");
			}
			this.phase = DONE;
			return;
		}
		int c = octet();
		c |= octet() << 8;
		c |= octet() << 16;
		c |= octet() << 24;
		if (c != ~this.crc) {
			throw fail("Corrupt GZIP trailer");
		}
		int s = octet();
		s |= octet() << 8;
		s |= octet() << 16;
		s |= octet() << 24;
		if (s != this.size) {
			throw fail("Corrupt GZIP trailer");
		}
		this.phase = NEXT;
	}

	private void block() {
		// a block header, one unit: the state changes only once it is whole
		boolean isLast = bits(1) == 1;
		int type = bits(2);
		switch (type) {
			case 0 -> {
				align();
				int len = bits(16);
				int nlen = bits(16);
				if (len != (nlen ^ 0xffff)) {
					throw fail("invalid stored block lengths");
				}
				this.remaining = len;
				this.last = isLast;
				this.phase = STORED;
			}
			case 1 -> {
				this.lens = FIXED_LENS;
				this.dists = FIXED_DISTS;
				this.last = isLast;
				this.phase = CODES;
			}
			case 2 -> {
				dynamic();
				this.last = isLast;
				this.phase = CODES;
			}
			default -> throw fail("invalid block type");
		}
	}

	private void dynamic() {
		// a dynamic block's code definitions, zlib's checks in zlib's order
		int nlen = bits(5) + 257;
		int ndist = bits(5) + 1;
		int ncode = bits(4) + 4;
		if (nlen > 286 || ndist > 30) {
			throw fail("too many length or distance symbols");
		}
		int[] lengths = new int[320];
		for (int i = 0; i < ncode; i++) {
			lengths[ORDER[i]] = bits(3);
		}
		Code lencode = Code.of(lengths, 0, 19, true);
		if (lencode == Code.REFUSED) {
			throw fail("invalid code lengths set");
		}
		java.util.Arrays.fill(lengths, 0);
		int have = 0;
		int total = nlen + ndist;
		while (have < total) {
			int sym = decode(lencode);
			// a code length code with no code reads every length as 0, a bit each
			if (sym < 16) {
				lengths[have++] = Math.max(sym, 0);
				continue;
			}
			int len = 0;
			int copy;
			if (sym == 16) {
				need(2);
				if (have == 0) {
					throw fail("invalid bit length repeat");
				}
				len = lengths[have - 1];
				copy = 3 + bits(2);
			}
			else if (sym == 17) {
				copy = 3 + bits(3);
			}
			else {
				copy = 11 + bits(7);
			}
			if (have + copy > total) {
				throw fail("invalid bit length repeat");
			}
			while (copy-- > 0) {
				lengths[have++] = len;
			}
		}
		if (lengths[256] == 0) {
			throw fail("invalid code -- missing end-of-block");
		}
		Code lenCode = Code.of(lengths, 0, nlen, false);
		if (lenCode == Code.REFUSED) {
			throw fail("invalid literal/lengths set");
		}
		Code distCode = Code.of(lengths, nlen, ndist, false);
		if (distCode == Code.REFUSED) {
			throw fail("invalid distances set");
		}
		this.lens = lenCode;
		this.dists = distCode;
	}

	private int decode(Code code) {
		// the next symbol of CODE, the bits taken one at a time; -1 for bits no symbol
		// has (an incomplete code's after its longest code, a bit of a code with none)
		if (code.max == 0) {
			bits(1);
			return -1;
		}
		int c = 0;
		int first = 0;
		int index = 0;
		for (int len = 1;; len++) {
			c |= bits(1);
			int k = code.count[len];
			if (c - k < first) {
				return code.symbols[index + (c - first)];
			}
			index += k;
			first = (first + k) << 1;
			c <<= 1;
			if (len >= code.max) {
				return -1;
			}
		}
	}

	private void room(int n) {
		if (this.opos + n > this.out.length) {
			this.out = java.util.Arrays.copyOf(this.out, Math.max(2 * this.out.length, this.opos + n));
		}
	}

	private void put(int b) {
		this.window[this.wpos] = (byte) b;
		this.wpos = (this.wpos + 1) & 32767;
		if (this.have < 32768) {
			this.have++;
		}
		room(1);
		this.out[this.opos++] = (byte) b;
	}

	private void copy(int limit) {
		while (this.copyLen > 0 && (limit < 0 || this.opos < limit)) {
			put(this.window[(this.wpos - this.copyDist) & 32767] & 0xff);
			this.copyLen--;
		}
	}

	private void codes(int limit) {
		// one unit of a Huffman block: a literal, a match (copied while the output is
		// under LIMIT) or the block's end
		int sym = decode(this.lens);
		if (sym < 0) {
			throw fail("invalid literal/length code");
		}
		if (sym < 256) {
			put(sym);
			return;
		}
		if (sym == 256) {
			this.phase = this.last ? CHECK : BLOCK;
			return;
		}
		if (sym > 285) {
			throw fail("invalid literal/length code");
		}
		int len = LENGTH_BASE[sym - 257] + bits(LENGTH_EXTRA[sym - 257]);
		int d = decode(this.dists);
		if (d < 0 || d > 29) {
			throw fail("invalid distance code");
		}
		int dist = DIST_BASE[d] + bits(DIST_EXTRA[d]);
		if (dist > this.have) {
			throw fail("invalid distance too far back");
		}
		this.copyLen = len;
		this.copyDist = dist;
		copy(limit);
	}

	private void fast() {
		// zlib's inflate_fast: Huffman symbols decoded while at least ten octets of input
		// are left, which no symbol can run past
		byte[] input = this.in;
		int end = input.length - 10;
		int p = this.pos;
		int h = this.hold;
		int b = this.bits;
		Code lcode = this.lens;
		Code dcode = this.dists;
		String failure = null;
		while (failure == null && p <= end) {
			while (b < 16) {
				h |= (input[p++] & 0xff) << b;
				b += 8;
			}
			int e = lcode.table[h & 511];
			if (e == 0) {
				e = lcode.lookup(h);
			}
			if (e < 0) {
				failure = "invalid literal/length code";
				break;
			}
			int sym = e >> 4;
			int l = e & 15;
			h >>>= l;
			b -= l;
			if (sym < 256) {
				room(1);
				this.window[this.wpos] = (byte) sym;
				this.wpos = (this.wpos + 1) & 32767;
				this.out[this.opos++] = (byte) sym;
				if (this.have < 32768) {
					this.have++;
				}
				continue;
			}
			if (sym == 256) {
				this.phase = this.last ? CHECK : BLOCK;
				break;
			}
			if (sym > 285) {
				failure = "invalid literal/length code";
				break;
			}
			int x = LENGTH_EXTRA[sym - 257];
			int len = LENGTH_BASE[sym - 257];
			if (x > 0) {
				while (b < 16) {
					h |= (input[p++] & 0xff) << b;
					b += 8;
				}
				len += h & ((1 << x) - 1);
				h >>>= x;
				b -= x;
			}
			while (b < 16) {
				h |= (input[p++] & 0xff) << b;
				b += 8;
			}
			int e2 = dcode.table[h & 511];
			if (e2 == 0) {
				e2 = dcode.lookup(h);
			}
			if (e2 < 0 || (e2 >> 4) > 29) {
				failure = "invalid distance code";
				break;
			}
			int d = e2 >> 4;
			int dl = e2 & 15;
			h >>>= dl;
			b -= dl;
			int dist = DIST_BASE[d];
			int dx = DIST_EXTRA[d];
			if (dx > 0) {
				while (b < 16) {
					h |= (input[p++] & 0xff) << b;
					b += 8;
				}
				dist += h & ((1 << dx) - 1);
				h >>>= dx;
				b -= dx;
			}
			if (dist > this.have) {
				failure = "invalid distance too far back";
				break;
			}
			room(len);
			for (int k = 0; k < len; k++) {
				byte v = this.window[(this.wpos - dist) & 32767];
				this.window[this.wpos] = v;
				this.wpos = (this.wpos + 1) & 32767;
				this.out[this.opos++] = v;
			}
			this.have = Math.min(32768, this.have + len);
		}
		this.pos = p;
		this.hold = h;
		this.bits = b;
		if (failure != null) {
			throw fail(failure);
		}
	}

	private void stored(int limit) {
		// what of a stored block has arrived, copied while the output is under LIMIT;
		// what is copied stays copied when the input runs out
		int k = Math.min(this.remaining, this.in.length - this.pos);
		if (limit >= 0) {
			k = Math.min(k, limit - this.opos);
		}
		room(k);
		System.arraycopy(this.in, this.pos, this.out, this.opos, k);
		this.opos += k;
		int from = this.pos;
		int n = k;
		while (n > 0) {
			int chunk = Math.min(n, 32768 - this.wpos);
			System.arraycopy(this.in, from, this.window, this.wpos, chunk);
			this.wpos = (this.wpos + chunk) & 32767;
			from += chunk;
			n -= chunk;
		}
		this.have = Math.min(32768, this.have + k);
		this.pos += k;
		this.remaining -= k;
		if (this.remaining == 0) {
			this.phase = this.last ? CHECK : BLOCK;
		}
		else if (this.pos >= this.in.length) {
			mark();
			throw UNDERFLOW;
		}
	}

	private void sum() {
		// the check and the size run over the octets decoded since the last sum
		if (this.kind == GZIP) {
			this.crc = crc(this.crc, this.out, this.sumFrom, this.opos);
			this.size += this.opos - this.sumFrom;
		}
		else if (this.kind == ZLIB) {
			int a = this.checkA;
			int b = this.checkB;
			for (int i = this.sumFrom; i < this.opos; i++) {
				a += this.out[i] & 0xff;
				if (a >= 65521) {
					a -= 65521;
				}
				b += a;
				if (b >= 65521) {
					b -= 65521;
				}
			}
			this.checkA = a;
			this.checkB = b;
		}
		this.sumFrom = this.opos;
	}

	private static int crc(int register, byte[] octets, int start, int end) {
		int c = register;
		for (int i = start; i < end; i++) {
			c = CRC_TABLE[(c ^ octets[i]) & 0xff] ^ (c >>> 8);
		}
		return c;
	}

	private static Code fixedLengths() {
		int[] lengths = new int[288];
		for (int s = 0; s < 288; s++) {
			lengths[s] = (s < 144) ? 8 : (s < 256) ? 9 : (s < 280) ? 7 : 8;
		}
		return Code.of(lengths, 0, 288, false);
	}

	private static Code fixedDistances() {
		int[] lengths = new int[32];
		java.util.Arrays.fill(lengths, 5);
		return Code.of(lengths, 0, 32, false);
	}

	private static int[] crcTable() {
		int[] table = new int[256];
		for (int n = 0; n < 256; n++) {
			int c = n;
			for (int k = 0; k < 8; k++) {
				c = ((c & 1) != 0) ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
			}
			table[n] = c;
		}
		return table;
	}

	/**
	 * A canonical Huffman code: how many codes of each length, the symbols in code order,
	 * and a table from the next nine bits to {@code (symbol << 4) | length} for every
	 * code of at most nine bits (0 elsewhere).
	 */
	private static final class Code {

		/** What {@link #of} answers for a set of lengths no code has. */
		static final Code REFUSED = new Code(0, 0);

		final int[] count = new int[16];

		final int[] symbols;

		final int[] table = new int[512];

		final int max;

		private Code(int n, int max) {
			this.symbols = new int[n];
			this.max = max;
		}

		/**
		 * The code of the {@code n} lengths from {@code from}, or {@link #REFUSED} for an
		 * over-subscribed set, and for an incomplete one unless it is a single code of
		 * length 1 and not {@code strict} -- zlib's {@code inflate_table}, which takes a
		 * set with no code at all.
		 */
		static Code of(int[] lengths, int from, int n, boolean strict) {
			int[] count = new int[16];
			for (int s = 0; s < n; s++) {
				count[lengths[from + s]]++;
			}
			int left = 1;
			int max = 0;
			for (int len = 1; len <= 15; len++) {
				if (count[len] > 0) {
					max = len;
				}
				left = left * 2 - count[len];
				if (left < 0) {
					return REFUSED;
				}
			}
			Code code = new Code(n, max);
			System.arraycopy(count, 0, code.count, 0, 16);
			if (max == 0) {
				return code;
			}
			if (left > 0 && (strict || max != 1)) {
				return REFUSED;
			}
			int[] offs = new int[16];
			int[] next = new int[16];
			int c = 0;
			for (int len = 1; len <= 15; len++) {
				c = (c + ((len == 1) ? 0 : count[len - 1])) << 1;
				next[len] = c;
				if (len < 15) {
					offs[len + 1] = offs[len] + count[len];
				}
			}
			for (int s = 0; s < n; s++) {
				int l = lengths[from + s];
				if (l == 0) {
					continue;
				}
				code.symbols[offs[l]++] = s;
				int v = next[l]++;
				if (l <= 9) {
					int r = 0;
					for (int k = 0; k < l; k++) {
						r = (r << 1) | ((v >>> k) & 1);
					}
					for (int i = r; i < 512; i += 1 << l) {
						code.table[i] = (s << 4) | l;
					}
				}
			}
			return code;
		}

		/**
		 * The symbol the bits of {@code hold} (at least the longest code's) start with,
		 * as {@code (symbol << 4) | length}; -1 for bits no symbol has.
		 */
		int lookup(int hold) {
			if (this.max == 0) {
				return -1;
			}
			int c = 0;
			int first = 0;
			int index = 0;
			for (int len = 1;; len++) {
				c |= (hold >>> (len - 1)) & 1;
				int k = this.count[len];
				if (c - k < first) {
					return (this.symbols[index + (c - first)] << 4) | len;
				}
				index += k;
				first = (first + k) << 1;
				c <<= 1;
				if (len >= this.max) {
					return -1;
				}
			}
		}

	}

	/** Where a unit stops: an underflow (an empty message) or a malformed stream. */
	private static final class Stop extends RuntimeException {

		private static final long serialVersionUID = 1L;

		final transient String message;

		Stop(String message) {
			super(message, null, false, false);
			this.message = message;
		}

	}

}

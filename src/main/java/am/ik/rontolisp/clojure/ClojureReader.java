package am.ik.rontolisp.clojure;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import am.ik.rontolisp.LispBigInteger;
import am.ik.rontolisp.LispChar;
import am.ik.rontolisp.LispCons;
import am.ik.rontolisp.LispDouble;
import am.ik.rontolisp.LispInteger;
import am.ik.rontolisp.LispNil;
import am.ik.rontolisp.LispRatio;
import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The EXPERIMENTAL Clojure front end's reader: source text to DATUMS, case-sensitively. A
 * datum is an ordinary {@link LispVal} the lowering walks: an identifier a
 * {@link LispSymbol} holding its spelling verbatim, a keyword a symbol whose name starts
 * with {@code :}, the empty list {@link LispNil}, a vector the marked list
 * {@code (%vector ...)}, a map the marked list {@code (%hash-map ...)} and a set the
 * marked list {@code (%hash-set ...)}: markers the lowering consumes and no identifier
 * can spell (user identifiers are mangled behind {@code ClojureLowering.PREFIX}).
 * {@code nil} / {@code true} / {@code false} stay symbols; the lowering decides what they
 * mean. A {@code #(...)} anonymous function reads as the oracle's reader reads it,
 * {@code (fn* [p1__N# ...] (body...))}, every argument literal replaced by its generated
 * parameter.
 *
 * <p>
 * A reader conditional ({@code #?(...)}, splicing {@code #?@(...)}) reads like the
 * oracle's {@code LispReader.ConditionalReader} where it is allowed -- a {@code .cljc}
 * file, a session -- and is the oracle's {@code Conditional read not allowed} elsewhere.
 * It takes the first branch whose feature is one of {@link #FEATURES} or
 * {@code :default}; every other form up to its closing parenthesis reads with tagged
 * literals suppressed and drops. A splice pushes its members onto the pending forms of
 * the enclosing list, which every read takes first, so a splice under a quote or a
 * discard leaves its rest to that list, as the oracle's {@code pendingForms} do.
 */
final class ClojureReader {

	/**
	 * The features a reader conditional takes a branch for besides {@code :default}: this
	 * front end's own, and the oracle's platform, whose branches are the ones written for
	 * the JVM Clojure this front end follows ({@code .kb/clojure-frontend.md}, "Reader
	 * conditionals").
	 */
	static final Set<String> FEATURES = Set.of(":rontolisp", ":clj");

	static final LispSymbol VECTOR = new LispSymbol("%vector");

	private static final LispSymbol HASH_MAP = new LispSymbol("%hash-map");

	private static final LispSymbol HASH_SET = new LispSymbol("%hash-set");

	/** Marks a regex literal's source string: the lowering compiles it to a pattern. */
	static final LispSymbol REGEX = new LispSymbol("%regex");

	/**
	 * Marks a record literal {@code (%record ns.Name body)}: the lowering builds the
	 * record over the quoted body.
	 */
	static final LispSymbol RECORD = new LispSymbol("%record");

	private static final String DELIMS = " \t\n\r\f,()[]{}\";'@^`~#";

	/**
	 * What {@link #readDatum} answers for a {@code #_} discard (the discarded datum
	 * already read): every collection and {@link #readAll} skips it, so a discard before
	 * a closing bracket or the end of input drops like the oracle's, and
	 * {@link #readRequired} reads on past it.
	 */
	private static final LispSymbol DISCARD = new LispSymbol("%discard");

	/**
	 * Heads what a tagged literal or {@code #=} reads as in a branch not taken,
	 * {@code (%tagged tag form)}: the branch drops, so only a set's duplicate check ever
	 * sees one, telling two literals apart by their forms like the oracle's.
	 */
	private static final LispSymbol TAGGED = new LispSymbol("%tagged");

	private final String source;

	private final @Nullable String file;

	private int pos;

	private int line = 1;

	private int column = 1;

	/**
	 * Where each datum starts, by identity: the offset of its first character, so the
	 * lowering can position its errors at the offending form. The innermost read wins (a
	 * {@code #_}-discarded prefix keeps the surviving datum's own start).
	 */
	private final Map<LispVal, Integer> offsets = new IdentityHashMap<>();

	/**
	 * The parameters of the {@code #(...)} being read, by argument number ({@code -1} for
	 * {@code %&}), or null outside one.
	 */
	private @Nullable TreeMap<Integer, LispSymbol> anonArgs;

	/**
	 * The last number a generated {@code #(...)} parameter took. It restarts at each
	 * top-level form: the oracle's counter is process-wide, but a parameter only has to
	 * differ from the ones a form can nest it in, and a form's spelling then stays the
	 * same wherever it sits in its file.
	 */
	private int anonId;

	/** Whether a reader conditional reads here, rather than being refused. */
	private final boolean conditionals;

	/**
	 * The forms a splicing reader conditional left for the list being read, taken ahead
	 * of the source by every read; null at the top level, where a splice is refused.
	 */
	private @Nullable ArrayDeque<LispVal> pending;

	/**
	 * Whether the datum being read is in a reader conditional's branch not taken: a
	 * tagged literal then reads without a reader for its tag, like the oracle's
	 * {@code *suppress-read*}.
	 */
	private boolean suppress;

	/**
	 * A reader of a file's text, taking reader conditionals where the oracle's
	 * {@code Compiler.load} does: in a {@code .cljc} file.
	 * @param source the text
	 * @param file the file it came from, or {@code null}
	 */
	ClojureReader(String source, @Nullable String file) {
		this(source, file, file != null && file.endsWith(".cljc"));
	}

	/**
	 * A reader of the text.
	 * @param source the text
	 * @param file the file it came from, or {@code null}
	 * @param conditionals whether a reader conditional reads (a {@code .cljc} file, a
	 * session) rather than being refused
	 */
	ClojureReader(String source, @Nullable String file, boolean conditionals) {
		this.source = source;
		this.file = file;
		this.conditionals = conditionals;
	}

	/**
	 * The file the text came from.
	 * @return the path, or {@code null} when unknown
	 */
	@Nullable String file() {
		return this.file;
	}

	/**
	 * Where the datum starts, or null when it did not come out of this read.
	 * @param datum the datum
	 * @return the position
	 */
	@Nullable SourceLocation locate(LispVal datum) {
		Integer offset = this.offsets.get(datum);
		if (offset == null) {
			return null;
		}
		return SourceLocation.at(this.file, offset, this.source);
	}

	List<LispVal> readAll() {
		List<LispVal> forms = new ArrayList<>();
		skipSpace();
		this.endsInDiscard = false;
		while (this.pos < this.source.length()) {
			this.anonId = 0;
			LispVal datum = readDatum();
			this.endsInDiscard = datum == DISCARD;
			if (datum != DISCARD) {
				forms.add(datum);
			}
			skipSpace();
		}
		return forms;
	}

	/**
	 * Whether the last {@link #readAll} ended in a {@code #_} discard: a session buffer
	 * like that waits for the datum after it, like the oracle's REPL reading on.
	 * @return whether the text ends in a discard
	 */
	boolean endsInDiscard() {
		return this.endsInDiscard;
	}

	private boolean endsInDiscard;

	/**
	 * The next datum past whitespace and {@code #_} discards: what a quote, a deref, a
	 * var, metadata and a discard itself read. The end of input is the
	 * {@code unexpected end of input} refusal.
	 */
	private LispVal readRequired() {
		ArrayDeque<LispVal> outer = this.pending;
		if (outer == null) {
			this.pending = new ArrayDeque<>();
		}
		try {
			LispVal datum;
			do {
				skipSpace();
				datum = readDatum();
			}
			while (datum == DISCARD);
			return datum;
		}
		finally {
			this.pending = outer;
		}
	}

	/**
	 * Whether a `#` right after an atom ends its token: before the end of input,
	 * whitespace or a delimiter -- never before a dispatch continuation (`'`, `_`, `(`,
	 * `{`, `"`), which still dispatches.
	 */
	private boolean hashEndsToken() {
		if (this.pos + 1 >= this.source.length()) {
			return true;
		}
		char after = this.source.charAt(this.pos + 1);
		return " \t\n\r\f,()[]{}\";".indexOf(after) >= 0;
	}

	private LispVal readDatum() {
		if (this.pending != null && !this.pending.isEmpty()) {
			return this.pending.poll();
		}
		if (this.pos >= this.source.length()) {
			throw error("unexpected end of input");
		}
		int start = this.pos;
		char c = peek();
		LispVal datum = switch (c) {
			case '(' -> readList(')');
			case ')' -> throw error("unexpected ')'");
			case '[' -> readVector();
			case ']' -> throw error("unexpected ']'");
			case '{' -> readBraced();
			case '}' -> throw error("unexpected '}'");
			case '"' -> readString();
			case '\'' -> {
				next();
				skipSpace();
				yield quoted("quote");
			}
			case '`' -> {
				next();
				skipSpace();
				yield quoted("syntax-quote");
			}
			case '~' -> {
				next();
				if (this.pos < this.source.length() && peek() == '@') {
					next();
					skipSpace();
					yield quoted("unquote-splicing");
				}
				skipSpace();
				yield quoted("unquote");
			}
			case '@' -> {
				next();
				yield list("deref", readRequired());
			}
			case '^' -> readMeta();
			case '#' -> readDispatch();
			case '\\' -> readCharLiteral();
			default -> readAtom();
		};
		if (datum != DISCARD) {
			this.offsets.putIfAbsent(datum, start);
		}
		return datum;
	}

	private LispVal readDispatch() {
		next();
		if (this.pos >= this.source.length()) {
			throw error("unexpected end of input");
		}
		if (peek() == '\'') { // var
			next();
			return list("var", readRequired());
		}
		if (peek() == '_') { // skip the next form
			next();
			readRequired();
			return DISCARD;
		}
		if (peek() == '(') {
			return readAnonFn();
		}
		if (peek() == '{') { // set: its own marked list, so the lowering names it
			next();
			return readSet();
		}
		if (peek() == '"') { // a regex literal: its source travels to the lowering
			LispVal source = readRegexSource();
			List<LispVal> regex = new ArrayList<>();
			regex.add(REGEX);
			regex.add(source);
			return list(regex);
		}
		if (peek() == '^') { // the legacy spelling of ^ metadata
			return readMeta();
		}
		if (peek() == '#') { // a symbolic value: ##NaN, ##Inf, ##-Inf
			next();
			return readSymbolicValue();
		}
		if (peek() == '?') {
			next();
			return readConditional();
		}
		if (Character.isLetter(peek())) {
			return readRecordLiteral();
		}
		if (peek() == '=' && this.suppress) { // a branch not taken evaluates nothing
			next();
			return list(List.of(TAGGED, new LispSymbol("="), readRequired()));
		}
		throw error("unsupported reader form #" + peek());
	}

	/**
	 * One reader conditional, positioned after {@code #?}: the oracle's
	 * {@code readCondDelimited}. Features and the taken branch read as ever; after a
	 * feature not taken, and after the taken branch, every form up to the closing
	 * parenthesis reads suppressed and drops, unpaired. No branch taken reads as a
	 * discard; a splice's members go onto the pending forms, ahead of the rest of the
	 * enclosing list.
	 */
	private LispVal readConditional() {
		if (!this.conditionals) {
			throw error("Conditional read not allowed");
		}
		if (this.pos >= this.source.length()) {
			throw error("unexpected end of input");
		}
		boolean splicing = false;
		if (peek() == '@') {
			next();
			splicing = true;
		}
		while (this.pos < this.source.length() && " \t\n\r\f,".indexOf(peek()) >= 0) {
			next();
		}
		if (this.pos >= this.source.length()) {
			throw error("unexpected end of input");
		}
		if (peek() != '(') {
			throw error("read-cond body must be a list");
		}
		next();
		ArrayDeque<LispVal> forms = this.pending;
		boolean topLevel = forms == null;
		if (forms == null) {
			forms = new ArrayDeque<>();
			this.pending = forms;
		}
		try {
			LispVal result = null;
			while (true) {
				if (result == null) {
					LispVal feature = readItem(')');
					if (feature == null) {
						break;
					}
					if (ClojureLowerUtil.isSymbolNamed(feature, ":else")
							|| ClojureLowerUtil.isSymbolNamed(feature, ":none")) {
						throw error("Feature name " + ((LispSymbol) feature).name() + " is reserved.");
					}
					if (!(feature instanceof LispSymbol keyword && keyword.name().startsWith(":"))) {
						throw error("Feature should be a keyword: " + strOf(feature));
					}
					if (keyword.name().equals(":default") || FEATURES.contains(keyword.name())) {
						result = readItem(')');
						if (result == null) {
							throw error("read-cond requires an even number of forms.");
						}
						continue;
					}
				}
				boolean outer = this.suppress;
				this.suppress = true;
				try {
					if (readItem(')') == null) {
						break;
					}
				}
				finally {
					this.suppress = outer;
				}
			}
			if (result == null) {
				return DISCARD;
			}
			if (!splicing) {
				return result;
			}
			List<LispVal> members = spliceMembers(result);
			if (members == null) {
				throw error("Spliced form list in read-cond-splicing must implement java.util.List");
			}
			if (topLevel) {
				throw error("Reader conditional splicing not allowed at the top level.");
			}
			for (int i = members.size() - 1; i >= 0; i--) {
				forms.addFirst(members.get(i));
			}
			return DISCARD;
		}
		finally {
			if (topLevel) {
				this.pending = null;
			}
		}
	}

	/**
	 * The members a splice takes from its branch when the oracle's form is a
	 * {@code java.util.List} -- a list or a vector, through any reader metadata -- else
	 * null (a map, a set, a regex, a scalar).
	 */
	private static @Nullable List<LispVal> spliceMembers(LispVal form) {
		List<LispVal> items = ClojureLowerUtil.items(form);
		while (items != null && items.size() == 3
				&& ClojureLowerUtil.isSymbolNamed(items.get(0), ClojureLowerUtil.READER_META)) {
			items = ClojureLowerUtil.items(items.get(1));
		}
		if (items == null || items.isEmpty()) {
			return items;
		}
		LispVal head = items.get(0);
		if (head == VECTOR) {
			return items.subList(1, items.size());
		}
		if (head == HASH_MAP || head == HASH_SET || head == REGEX || head == RECORD || head == TAGGED) {
			return null;
		}
		return items;
	}

	/**
	 * The next datum of the list being read -- a pending form first, discards skipped --
	 * or null at its closing character, consumed.
	 */
	private @Nullable LispVal readItem(char close) {
		while (true) {
			if (this.pending != null && !this.pending.isEmpty()) {
				return this.pending.poll();
			}
			skipSpace();
			if (peekClose(close)) {
				next();
				return null;
			}
			LispVal datum = readDatum();
			if (datum != DISCARD) {
				return datum;
			}
		}
	}

	/**
	 * One symbolic value {@code ##NaN}, {@code ##Inf} or {@code ##-Inf}, positioned after
	 * both hashes: the double. Like the oracle it reads the NEXT FORM (so {@code ## Inf}
	 * reads too) and refuses a symbol it does not know, or a form that is no symbol, by
	 * name.
	 */
	private LispVal readSymbolicValue() {
		LispVal form = readRequired();
		if (form instanceof LispSymbol symbol) {
			switch (symbol.name()) {
				case "NaN" -> {
					return new LispDouble(Double.NaN);
				}
				case "Inf" -> {
					return new LispDouble(Double.POSITIVE_INFINITY);
				}
				case "-Inf" -> {
					return new LispDouble(Double.NEGATIVE_INFINITY);
				}
				default -> {
				}
			}
			if (!List.of("nil", "true", "false").contains(symbol.name())) {
				throw error("Unknown symbolic value: ##" + symbol.name());
			}
		}
		throw error("Invalid token: ##" + strOf(form));
	}

	/**
	 * The form as the oracle's refusals spell one ({@code "" + form}): a string or a
	 * character bare, nil as {@code null}, a collection the way {@code pr} writes it.
	 */
	private static String strOf(LispVal form) {
		if (form instanceof LispString string) {
			return string.value();
		}
		if (form instanceof LispChar c) {
			return Character.toString(c.codePoint());
		}
		if (form instanceof LispSymbol symbol && symbol.name().equals("nil")) {
			return "null";
		}
		return prOf(form);
	}

	/** The datum as {@code pr} writes it, for {@link #strOf}. */
	private static String prOf(LispVal form) {
		if (form instanceof LispSymbol symbol) {
			return symbol.name();
		}
		List<LispVal> items = ClojureLowerUtil.items(form);
		if (items == null) {
			return form.print();
		}
		LispVal head = items.isEmpty() ? LispNil.INSTANCE : items.get(0);
		String open = head == VECTOR ? "[" : head == HASH_SET ? "#{" : head == HASH_MAP ? "{" : "(";
		String close = head == VECTOR ? "]" : head == HASH_SET || head == HASH_MAP ? "}" : ")";
		List<LispVal> members = open.equals("(") ? items : items.subList(1, items.size());
		StringBuilder text = new StringBuilder(open);
		for (int i = 0; i < members.size(); i++) {
			if (i > 0) {
				text.append(head == HASH_MAP && i % 2 == 0 ? ", " : " ");
			}
			text.append(prOf(members.get(i)));
		}
		return text.append(close).toString();
	}

	/**
	 * One {@code ^meta form} (or legacy {@code #^meta form}), positioned at the caret:
	 * {@code (%with-meta form meta)} -- a head no Clojure call spells, so the lowering
	 * tells reader metadata (dropped, except on a collection literal) from a
	 * {@code with-meta} call (which attaches).
	 */
	private LispVal readMeta() {
		next();
		LispVal meta = readRequired();
		return list(ClojureLowerUtil.READER_META, readRequired(), meta);
	}

	/**
	 * One record literal {@code #ns.Name{:k v ...}} / {@code #ns.Name[v ...]}, positioned
	 * after the hash: {@code (%record ns.Name body)}, the body read as data (the oracle
	 * never evaluates it). Like the oracle, only a dotted class name is a record literal
	 * (an undotted tag is a tagged literal, and no reader function is installed for one);
	 * a body that is neither a map nor a vector is unreadable, and a map body takes
	 * distinct keyword keys only.
	 */
	private LispVal readRecordLiteral() {
		int start = this.pos;
		while (this.pos < this.source.length() && DELIMS.indexOf(peek()) < 0) {
			next();
		}
		String tag = this.source.substring(start, this.pos);
		if (this.suppress) { // a branch not taken needs no reader for its tag
			return list(List.of(TAGGED, new LispSymbol(tag), readRequired()));
		}
		if (tag.indexOf('.') < 0) {
			if (tag.equals("inst") || tag.equals("uuid")) {
				throw error("unsupported reader form #" + tag);
			}
			throw error("No reader function for tag " + tag);
		}
		skipSpace();
		LispVal body;
		if (this.pos < this.source.length() && peek() == '[') {
			body = readVector();
		}
		else if (this.pos < this.source.length() && peek() == '{') {
			next();
			List<LispVal> items = readSeq('}');
			if (items.size() % 2 != 0) {
				throw error("a map literal needs an even number of forms");
			}
			Set<String> seen = new HashSet<>();
			for (int i = 0; i < items.size(); i += 2) {
				if (!(items.get(i) instanceof LispSymbol key && key.name().startsWith(":"))) {
					throw error("Unreadable defrecord form: key must be of type clojure.lang.Keyword, got "
							+ items.get(i).print());
				}
				if (!seen.add(key.name())) {
					throw error("Duplicate key: " + key.name());
				}
			}
			body = marked(HASH_MAP, items);
		}
		else {
			throw error("Unreadable constructor form starting with \"#" + tag + "\"");
		}
		return list(List.of(RECORD, new LispSymbol(tag), body));
	}

	/**
	 * One character literal: a single character, a lowercase name ({@code newline},
	 * {@code space}, {@code tab}, {@code return}, {@code backspace}, {@code formfeed}), a
	 * {@code u} plus four hex digits or an {@code o} plus one to three octal digits --
	 * exactly the oracle's (Clojure CLI 1.12) spellings, case-sensitively. Like the
	 * oracle's, the character behind the backslash belongs to the literal whatever it is
	 * (so {@code \(}, what {@code pr} spells for the parenthesis, reads back) and a
	 * backslash ends it ({@code [\a\b]} is two characters). Anything else is the oracle's
	 * {@code Unsupported character} refusal.
	 */
	private LispVal readCharLiteral() {
		next(); // the backslash
		int start = this.pos;
		if (this.pos < this.source.length()) {
			next(); // the first character, whatever it is
		}
		while (this.pos < this.source.length() && DELIMS.indexOf(peek()) < 0 && peek() != '\\') {
			next();
		}
		String token = this.source.substring(start, this.pos);
		if (token.length() == 1) {
			return new LispChar(token.charAt(0));
		}
		if (token.length() == 5 && token.charAt(0) == 'u') {
			try {
				int cp = Integer.parseInt(token.substring(1), 16);
				if (Character.isValidCodePoint(cp) && (cp < 0xD800 || cp > 0xDFFF)) {
					return new LispChar(cp);
				}
			}
			catch (NumberFormatException ex) {
				// the refusal below
			}
			throw error("Unsupported character: \\" + token);
		}
		if (token.length() > 1 && token.charAt(0) == 'o' && token.length() <= 4) {
			try {
				int cp = Integer.parseInt(token.substring(1), 8);
				return new LispChar(cp);
			}
			catch (NumberFormatException ex) {
				throw error("Unsupported character: \\" + token);
			}
		}
		int named = switch (token) {
			case "newline" -> '\n';
			case "space" -> ' ';
			case "tab" -> '\t';
			case "return" -> '\r';
			case "backspace" -> '\b';
			case "formfeed" -> '\f';
			default -> -1;
		};
		if (named >= 0) {
			return new LispChar(named);
		}
		throw error("Unsupported character: \\" + token);
	}

	/**
	 * One {@code #(...)}, positioned at its parenthesis: the oracle's
	 * {@code (fn* [params] (body...))}. Each argument literal in the body reads as its
	 * parameter ({@link #anonArg}); the vector runs from {@code p1} to the highest number
	 * used, a number the body skipped generated after it, then {@code & rest} when
	 * {@code %&} occurs. A {@code #(...)} inside another is refused, like the oracle's.
	 */
	private LispVal readAnonFn() {
		if (this.anonArgs != null) {
			throw error("Nested #()s are not allowed");
		}
		int start = this.pos;
		TreeMap<Integer, LispSymbol> args = new TreeMap<>();
		this.anonArgs = args;
		LispVal body;
		try {
			next();
			body = list(readSeq(')'));
		}
		finally {
			this.anonArgs = null;
		}
		this.offsets.putIfAbsent(body, start);
		List<LispVal> params = new ArrayList<>();
		int high = args.isEmpty() ? 0 : Math.max(args.lastKey(), 0);
		for (int n = 1; n <= high; n++) {
			params.add(args.computeIfAbsent(n, this::anonParam));
		}
		LispSymbol rest = args.get(-1);
		if (rest != null) {
			params.add(new LispSymbol("&"));
			params.add(rest);
		}
		return list(List.of(new LispSymbol("fn*"), marked(VECTOR, params), body));
	}

	/**
	 * The parameter an argument literal of the {@code #(...)} being read stands for:
	 * {@code %} and {@code %1} the first, {@code %N} the Nth, {@code %&} the rest, each
	 * generated on its first use. Anything else after a {@code %} is the oracle's
	 * refusal.
	 */
	private LispSymbol anonArg(String token, TreeMap<Integer, LispSymbol> args) {
		int n;
		if (token.equals("%")) {
			n = 1;
		}
		else if (token.equals("%&")) {
			n = -1;
		}
		else if (token.length() > 1 && token.substring(1).chars().allMatch(c -> c >= '0' && c <= '9')) {
			try {
				n = Integer.parseInt(token.substring(1));
			}
			catch (NumberFormatException ex) {
				throw error("arg literal must be %, %& or %integer");
			}
		}
		else {
			throw error("arg literal must be %, %& or %integer");
		}
		return args.computeIfAbsent(n, this::anonParam);
	}

	/** A fresh parameter for argument number N, spelled like the oracle's. */
	private LispSymbol anonParam(int n) {
		this.anonId++;
		return new LispSymbol((n == -1 ? "rest" : "p" + n) + "__" + this.anonId + "#");
	}

	private LispVal readBraced() {
		next();
		List<LispVal> items = readSeq('}');
		if (items.size() % 2 != 0) {
			throw error("a map literal needs an even number of forms");
		}
		return marked(HASH_MAP, items);
	}

	/**
	 * One set literal: the items as read, refusing a repeated element by its spelling.
	 * The spelling check is what makes the common duplicate ({@code #{1 1}}) fail like
	 * the oracle's {@code Duplicate key}; two differently-spelled elements that happen to
	 * be equal at run time still dedupe silently.
	 */
	private LispVal readSet() {
		List<LispVal> items = readSeq('}');
		Set<String> seen = new HashSet<>();
		for (LispVal item : items) {
			String spelling = item.print();
			if (!seen.add(spelling)) {
				throw error("Duplicate key: " + spelling);
			}
		}
		return marked(HASH_SET, items);
	}

	private List<LispVal> readSeq(char close) {
		List<LispVal> items = new ArrayList<>();
		ArrayDeque<LispVal> outer = this.pending;
		if (outer == null) {
			this.pending = new ArrayDeque<>();
		}
		try {
			for (LispVal datum = readItem(close); datum != null; datum = readItem(close)) {
				items.add(datum);
			}
			return items;
		}
		finally {
			this.pending = outer;
		}
	}

	private boolean peekClose(char close) {
		if (this.pos >= this.source.length()) {
			throw error("unclosed form, expected '" + close + "'");
		}
		return peek() == close;
	}

	private LispVal readList(char close) {
		next();
		return list(readSeq(close));
	}

	private LispVal readVector() {
		next();
		return marked(VECTOR, readSeq(']'));
	}

	private LispVal quoted(String name) {
		return list(name, readRequired());
	}

	/**
	 * One regex literal's source: every escape validates like the oracle's but stays
	 * verbatim for the pattern parser (which names what it cannot lower), measured on
	 * {@code clj} 1.12.6.1673 (2026-10-02). {@code \\} stays two characters (so
	 * {@code #"\\d"} reads a literal backslash plus {@code d}, where the old read halved
	 * the run and answered the digit class); {@code \"} and {@code \'} stay two
	 * characters without ending the literal; the recognized escape letters stay as
	 * written while any other letter is the oracle's read-time refusal; {@code \\u} takes
	 * four hex digits and {@code \x} two; a {@code \0} needs an octal digit behind it;
	 * {@code \Q} copies raw through {@code \E} (a lone {@code \E} is refused).
	 */
	private LispVal readRegexSource() {
		next();
		StringBuilder text = new StringBuilder();
		while (true) {
			if (this.pos >= this.source.length()) {
				throw error("unterminated string");
			}
			char c = next();
			if (c == '"') {
				return LispString.literal(text.toString());
			}
			if (c != '\\') {
				text.append(c);
				continue;
			}
			if (this.pos >= this.source.length()) {
				throw error("unterminated escape");
			}
			char e = next();
			switch (e) {
				case 'n', 't', 'r', 'f', 'b', 'a', 'e', 'h', 'v', 'w', 'd', 's', 'c', 'p', 'z', 'A', 'B', 'D', 'G', 'H',
						'P', 'R', 'S', 'V', 'W', 'X', 'Z' -> {
					text.append('\\');
					text.append(e);
				}
				case '\\', '"', '\'', '/' -> {
					text.append('\\');
					text.append(e);
				}
				case 'Q' -> {
					text.append('\\');
					text.append('Q');
					readRegexQuoted(text);
				}
				case 'E' -> throw error("Illegal/unsupported escape sequence: \\E");
				case 'u' -> {
					text.append('\\');
					text.append('u');
					text.append(readRegexHex(4, "Unicode"));
				}
				case 'x' -> {
					text.append('\\');
					text.append('x');
					text.append(readRegexHex(2, "hexadecimal"));
				}
				// Octal `0`-`7` stays verbatim (up to two more `0`-`7`): the
				// oracle refuses a `\0` with no octal digit behind it, while
				// `8`/`9` stay verbatim here (the pattern parser reads the
				// backreference, like the oracle) where the string reader
				// refuses them instead.
				case '0', '1', '2', '3', '4', '5', '6', '7' -> {
					if (e == '0' && (this.pos >= this.source.length() || !isOctalDigit(peek()))) {
						throw error("Illegal octal escape sequence: \\0");
					}
					text.append('\\');
					text.append(e);
					int count = 1;
					while (count < 3 && this.pos < this.source.length() && isOctalDigit(peek())) {
						text.append(next());
						count++;
					}
				}
				case '8', '9' -> {
					text.append('\\');
					text.append(e);
				}
				default -> {
					if (Character.isLetter(e)) {
						throw error("Illegal/unsupported escape sequence: \\" + e);
					}
					text.append('\\');
					text.append(e);
				}
			}
		}
	}

	/**
	 * A {@code \Q..\E} span's raw body onto {@code text}: every character copies verbatim
	 * (even the closing quote, which does not end the literal here), through the closing
	 * {@code \E} when one comes, like the oracle.
	 * @param text the source being built
	 */
	private void readRegexQuoted(StringBuilder text) {
		while (true) {
			if (this.pos >= this.source.length()) {
				throw error("unterminated string");
			}
			char c = next();
			if (c == '\\' && this.pos < this.source.length() && peek() == 'E') {
				text.append(c);
				text.append(next());
				return;
			}
			text.append(c);
		}
	}

	/**
	 * {@code count} hex digits of a regex {@code \\u}/{@code \x} escape, kept verbatim:
	 * anything else is the oracle's refusal.
	 * @param count the digits the escape takes (four for {@code \\u}, two for {@code \x})
	 * @param kind the escape's name for the refusal
	 * @return the digits as written
	 */
	private String readRegexHex(int count, String kind) {
		StringBuilder raw = new StringBuilder();
		for (int i = 0; i < count; i++) {
			if (this.pos >= this.source.length() || Character.digit(peek(), 16) < 0) {
				throw error("Illegal " + kind + " escape sequence");
			}
			raw.append(next());
		}
		return raw.toString();
	}

	private static boolean isOctalDigit(char c) {
		return c >= '0' && c <= '7';
	}

	private LispVal readString() {
		next();
		StringBuilder text = new StringBuilder();
		while (true) {
			if (this.pos >= this.source.length()) {
				throw error("unterminated string");
			}
			char c = next();
			if (c == '"') {
				return LispString.literal(text.toString());
			}
			if (c != '\\') {
				text.append(c);
				continue;
			}
			if (this.pos >= this.source.length()) {
				throw error("unterminated escape");
			}
			char e = next();
			switch (e) {
				case 'n' -> text.append('\n');
				case 't' -> text.append('\t');
				case 'r' -> text.append('\r');
				case 'f' -> text.append('\f');
				case 'b' -> text.append('\b');
				case '\\' -> text.append('\\');
				case '"' -> text.append('"');
				case 'u' -> text.append(Character.toChars(readUnicodeEscape()));
				// Octal `\0`-`\7` (up to two more `0`-`7`), like the oracle: a
				// following `8`/`9` -- or any other non-octal char the reader would
				// not stop at -- is the oracle's `Invalid digit` refusal, and a
				// value past `\377` the oracle's range refusal. `\'` is refused
				// below with the other unknown escapes (the oracle signals
				// `Unsupported escape character: \'`, so the old lenient read as
				// `'` goes).
				case '0', '1', '2', '3', '4', '5', '6', '7' -> {
					int value = e - '0';
					int count = 1;
					while (count < 3 && this.pos < this.source.length() && !escapeStops(peek())) {
						int digit = Character.digit(peek(), 8);
						if (digit < 0) {
							throw error("Invalid digit: " + peek());
						}
						next();
						value = value * 8 + digit;
						count++;
					}
					if (value > 0377) {
						throw error("Octal escape sequence must be in range [0, 377].");
					}
					text.append((char) value);
				}
				case '8', '9' -> throw error("Invalid digit: " + e);
				// like the oracle: an unknown escape signals instead of reading
				// on.
				default -> throw error("Unsupported escape character: \\" + e);

			}
		}
	}

	/**
	 * One backslash-u escape's character, like the oracle ({@code clj} 1.12.6.1673,
	 * measured 2026-10-01): its string reader checks the first digit itself
	 * ({@code Invalid unicode escape}, naming even the closing quote), then runs the
	 * shared unicode reader over exactly four hex digits. A non-hex later digit is
	 * {@code Invalid digit}; fewer than four digits before a stop -- whitespace (the
	 * comma counts as one there), a macro character, or the closing quote -- is
	 * {@code Invalid character length}. A buffer that ends mid-escape stays the
	 * {@code truncated} refusal (a deliberate deviation: the oracle reports the
	 * escape/length refusal even at end of input, but here the end of input may be a REPL
	 * buffer boundary, so {@code ClojureSession.isComplete} keeps waiting for the rest
	 * instead of reporting a complete-but-wrong form).
	 */

	private int readUnicodeEscape() {
		if (this.pos >= this.source.length()) {
			throw error("truncated \\u escape");
		}
		char first = peek();
		if (Character.digit(first, 16) < 0) {
			throw error("Invalid unicode escape: \\u" + first);
		}
		int value = Character.digit(next(), 16);
		int count = 1;
		while (count < 4 && this.pos < this.source.length() && !escapeStops(peek())) {
			int digit = Character.digit(peek(), 16);
			if (digit < 0) {
				throw error("Invalid digit: " + peek());
			}
			next();
			value = value * 16 + digit;
			count++;
		}
		if (count != 4) {
			if (this.pos >= this.source.length()) {
				throw error("truncated \\u escape");
			}
			throw error("Invalid character length: " + count + ", should be: 4");
		}
		return value;

	}

	/**
	 * Whether the oracle's string reader would stop a backslash-u or octal escape before
	 * this character: its shared unicode reader stops at the end of input, at whitespace
	 * (the comma counts as one there) or at a macro character, leaving the character for
	 * the string body. Anything else must be a digit of the escape's base (measured on
	 * {@code clj} 1.12.6.1673 for both arms: a colon refuses with {@code Invalid digit}
	 * while a comma, a semicolon, an open paren and a hash stop the escape there).
	 */
	private static boolean escapeStops(char c) {
		return Character.isWhitespace(c) || c == ',' || "\";'@^`~()[]{}\\%#".indexOf(c) >= 0;
	}

	private LispVal readAtom() {
		int start = this.pos;
		// a quote is a constituent of a symbol (coll', a'b), the oracle's
		// non-terminating macro character, while a number stops at it like the
		// oracle's number reader
		char first = peek();
		boolean number = Character.isDigit(first) || ((first == '+' || first == '-')
				&& this.pos + 1 < this.source.length() && Character.isDigit(this.source.charAt(this.pos + 1)));
		while (this.pos < this.source.length() && (DELIMS.indexOf(peek()) < 0 || (!number && peek() == '\''))) {
			next();
		}
		// a gensym suffix: `x#` reads as one identifier when the `#` ends the
		// token (before whitespace, a delimiter or the end of input); a dispatch
		// form (`#'`, `#(`, `#"`, ...) still dispatches
		while (this.pos < this.source.length() && peek() == '#' && hashEndsToken()) {
			next();
		}
		String token = this.source.substring(start, this.pos);
		if (token.isEmpty()) {
			throw error("expected a form");
		}
		if (token.equals("nil") || token.equals("true") || token.equals("false")) {
			return new LispSymbol(token);
		}
		if (this.anonArgs != null && token.charAt(0) == '%') {
			return anonArg(token, this.anonArgs);
		}
		LispVal n = tryNumber(token);
		if (n != null) {
			return n;
		}
		return new LispSymbol(token);
	}

	/** The number the token spells, or null when it is an identifier. */
	private @Nullable LispVal tryNumber(String token) {
		if (!numberShaped(token)) {
			return null;
		}
		try {
			return parseNumber(token);
		}
		catch (NumberFormatException | ArithmeticException ex) {
			throw error("Invalid number: " + token);
		}
	}

	/**
	 * Whether the token is number-shaped: a leading digit, a sign followed by a digit or
	 * a dot, or a dot followed by a digit. Anything else is an identifier, so
	 * {@code s/join} never reaches the number parser; a shaped token that parses to
	 * nothing ( {@code 09}, {@code 1e}, {@code 2r}) is the oracle's
	 * {@code Invalid number} refusal.
	 */
	private static boolean numberShaped(String token) {
		if (token.isEmpty()) {
			return false;
		}
		char first = token.charAt(0);
		if (Character.isDigit(first)) {
			return true;
		}
		if ((first == '+' || first == '-') && token.length() > 1) {
			char second = token.charAt(1);
			return Character.isDigit(second) || second == '.';
		}
		return first == '.' && token.length() > 1 && Character.isDigit(token.charAt(1));
	}

	/**
	 * The number the shaped token spells: a ratio, a radix integer ({@code 0x},
	 * {@code Nr}, a leading {@code 0} for octal), an exact ratio for the {@code M}
	 * suffix, a double, or a long (a {@link LispBigInteger} past the {@code long} range,
	 * with or without the {@code N} suffix). The {@code M} suffix lowers to an exact
	 * ratio -- {@code 0.1M} is {@code 1/10}, so decimal arithmetic stays exact instead of
	 * the double's precision loss; it prints as the ratio, not {@code 0.1M}.
	 */
	private static LispVal parseNumber(String token) {
		String unsigned = token.startsWith("+") || token.startsWith("-") ? token.substring(1) : token;
		int slash = token.indexOf('/');
		if (slash > 0 && isDecimalRatio(token)) {
			return LispRatio.valueOf(new java.math.BigInteger(token.substring(0, slash)),
					new java.math.BigInteger(token.substring(slash + 1)));
		}
		if (unsigned.startsWith("0x") || unsigned.startsWith("0X")) {
			return signedOf(new java.math.BigInteger(stripBigSuffix(unsigned.substring(2)), 16), token);
		}
		int mark = Math.max(unsigned.indexOf('r'), unsigned.indexOf('R'));
		if (mark > 0) {
			int radix = Integer.parseInt(unsigned.substring(0, mark));
			if (radix < 2 || radix > 36) {
				throw new NumberFormatException(token);
			}
			return signedOf(new java.math.BigInteger(stripBigSuffix(unsigned.substring(mark + 1)), radix), token);
		}
		if (unsigned.length() > 1 && unsigned.charAt(0) == '0' && isDigits(unsigned)) {
			if (!isOctalDigits(unsigned)) {
				throw new NumberFormatException(token);
			}
			return signedOf(new java.math.BigInteger(unsigned, 8), token);
		}
		if (token.endsWith("M")) {
			java.math.BigDecimal decimal = new java.math.BigDecimal(token.substring(0, token.length() - 1));
			java.math.BigInteger numerator = decimal.unscaledValue();
			int scale = decimal.scale();
			if (scale >= 0) {
				return LispRatio.valueOf(numerator, java.math.BigInteger.TEN.pow(scale));
			}
			return LispRatio.valueOf(numerator.multiply(java.math.BigInteger.TEN.pow(-scale)),
					java.math.BigInteger.ONE);
		}
		if (token.indexOf('.') >= 0 || token.indexOf('e') >= 0 || token.indexOf('E') >= 0) {
			if (token.endsWith("N")) {
				throw new NumberFormatException(token);
			}
			return new LispDouble(Double.parseDouble(token));
		}
		return integerOf(new java.math.BigInteger(stripBigSuffix(token), 10));
	}

	/** Whether the token is a plain decimal ratio: digits around one slash, no suffix. */
	private static boolean isDecimalRatio(String token) {
		for (int i = 0; i < token.length(); i++) {
			char c = token.charAt(i);
			if (c == '/') {
				continue;
			}
			if (c == '+' || c == '-') {
				continue;
			}
			if (!Character.isDigit(c)) {
				return false;
			}
		}
		return true;
	}

	private static boolean isDigits(String text) {
		for (int i = 0; i < text.length(); i++) {
			if (!Character.isDigit(text.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	private static boolean isOctalDigits(String text) {
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c < '0' || c > '7') {
				return false;
			}
		}
		return true;
	}

	private static String stripBigSuffix(String digits) {
		return digits.endsWith("N") ? digits.substring(0, digits.length() - 1) : digits;
	}

	/** A long when it fits, a {@link LispBigInteger} past the {@code long} range. */
	private static LispVal integerOf(java.math.BigInteger value) {
		return value.bitLength() < 64 ? new LispInteger(value.longValue()) : new LispBigInteger(value);
	}

	/** A radix value with the token's sign applied (the prefix tests read unsigned). */
	private static LispVal signedOf(java.math.BigInteger value, String token) {
		if (token.startsWith("-")) {
			value = value.negate();
		}
		return integerOf(value);
	}

	private LispVal list(String head, LispVal... args) {
		List<LispVal> items = new ArrayList<>();
		items.add(new LispSymbol(head));
		items.addAll(List.of(args));
		return list(items);
	}

	private static LispVal marked(LispSymbol marker, List<LispVal> items) {
		List<LispVal> call = new ArrayList<>();
		call.add(marker);
		call.addAll(items);
		return list(call);
	}

	private static LispVal list(List<LispVal> items) {
		LispVal tail = LispNil.INSTANCE;
		for (int i = items.size() - 1; i >= 0; i--) {
			tail = new LispCons(items.get(i), tail);
		}
		return tail;
	}

	private void skipSpace() {
		while (this.pos < this.source.length()) {
			char c = peek();
			if (c == ';' || (c == '#' && this.pos == 0 && this.source.startsWith("#!", this.pos))) {
				while (this.pos < this.source.length() && peek() != '\n') {
					next();
				}
				continue;
			}
			if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == ',') {
				next();
				continue;
			}
			return;
		}
	}

	private char peek() {
		return this.source.charAt(this.pos);
	}

	private char next() {
		char c = this.source.charAt(this.pos++);
		if (c == '\n') {
			this.line++;
			this.column = 1;
		}
		else {
			this.column++;
		}
		return c;
	}

	private LispReadException error(String message) {
		return new LispReadException(
				this.file == null ? message : this.file + ":" + this.line + ":" + this.column + ": " + message);
	}

}

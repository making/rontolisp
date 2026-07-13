package am.ik.wit;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Recursive-descent parser for the WIT textual format, covering the grammar subset the
 * WASI ecosystem ships (see the vendored {@code wasi:*} WIT and the worlds
 * {@code wasm-tools component wit} prints): package headers and explicit package blocks,
 * worlds ({@code import}/{@code export}/{@code include}/{@code use}), interfaces,
 * functions ({@code async}/{@code static}/{@code constructor}), the full type-definition
 * set ({@code record}/{@code variant}/{@code enum}/{@code flags}/{@code resource}/
 * {@code type}), versions, gate attributes ({@code @since}/{@code @unstable}/
 * {@code @deprecated}) and {@code ///} doc comments (preserved into
 * {@link WitMeta#docs()}). Constructs outside this subset (nested namespaces,
 * {@code include ... with}, fixed-size lists) fail with a clear
 * {@link WitParseException}.
 */
public final class WitParser {

	private static final Set<String> PRIMITIVES = Set.of("bool", "u8", "u16", "u32", "u64", "s8", "s16", "s32", "s64",
			"f32", "f64", "char", "string");

	private final String source;

	private final List<WitToken> tokens;

	private int pos;

	private WitParser(String source) {
		this.source = source;
		this.tokens = WitLexer.lex(source);
	}

	/**
	 * Parses a WIT source text into a document model.
	 * @param source the WIT text
	 * @return the parsed document
	 * @throws WitParseException on a syntax error or an unsupported construct
	 */
	public static WitDocument parse(String source) {
		return new WitParser(source).parseDocument();
	}

	private WitDocument parseDocument() {
		List<WitItem> items = new ArrayList<>();
		while (peek().kind() != WitToken.Kind.EOF) {
			WitMeta meta = parseMeta();
			WitToken keyword = peek();
			if (keyword.isWord("package")) {
				items.add(parsePackage(meta));
			}
			else if (keyword.isWord("world")) {
				items.add(parseWorld(meta));
			}
			else if (keyword.isWord("interface")) {
				items.add(parseInterface(meta));
			}
			else {
				throw error("Expected 'package', 'world' or 'interface'", keyword);
			}
		}
		return new WitDocument(List.copyOf(items));
	}

	private WitItem parsePackage(WitMeta meta) {
		expectWord("package");
		WitPackageName name = parsePackageName();
		if (peek().isPunct(";")) {
			next();
			return new WitItem.PackageHeader(meta, name);
		}
		expectPunct("{");
		List<WitItem> items = new ArrayList<>();
		while (!peek().isPunct("}")) {
			WitMeta itemMeta = parseMeta();
			WitToken keyword = peek();
			if (keyword.isWord("world")) {
				items.add(parseWorld(itemMeta));
			}
			else if (keyword.isWord("interface")) {
				items.add(parseInterface(itemMeta));
			}
			else {
				throw error("Expected 'world' or 'interface' in package block", keyword);
			}
		}
		expectPunct("}");
		return new WitItem.PackageBlock(meta, name, List.copyOf(items));
	}

	private WitPackageName parsePackageName() {
		String namespace = expectWordText();
		expectPunct(":");
		String name = expectWordText();
		String version = null;
		if (peek().isPunct("@")) {
			next();
			version = expectVersionText();
		}
		return new WitPackageName(namespace, name, version);
	}

	private WitItem.World parseWorld(WitMeta meta) {
		expectWord("world");
		String name = expectWordText();
		expectPunct("{");
		List<WitItem> items = new ArrayList<>();
		while (!peek().isPunct("}")) {
			items.add(parseWorldItem());
		}
		expectPunct("}");
		return new WitItem.World(meta, name, List.copyOf(items));
	}

	private WitItem parseWorldItem() {
		WitMeta meta = parseMeta();
		WitToken keyword = peek();
		if (keyword.isWord("import") || keyword.isWord("export")) {
			boolean isImport = keyword.isWord("import");
			next();
			String first = expectWordText();
			// `name: func(...)` / `name: interface {...}` versus a path -- a path's
			// ':' is followed by the package name and then '/', an extern's ':' by
			// 'func' / 'async' / 'static'? no: by the extern keyword.
			if (peek().isPunct(":") && isExternKeyword(peekAt(1))) {
				next();
				WitItem.Extern extern = parseExtern();
				return isImport ? new WitItem.ImportNamed(meta, first, extern)
						: new WitItem.ExportNamed(meta, first, extern);
			}
			WitRef target = parseRef(first);
			expectPunct(";");
			return isImport ? new WitItem.ImportRef(meta, target) : new WitItem.ExportRef(meta, target);
		}
		if (keyword.isWord("include")) {
			next();
			WitRef target = parseRef(expectWordText());
			if (peek().isWord("with")) {
				throw error("'include ... with' is not supported", peek());
			}
			expectPunct(";");
			return new WitItem.Include(meta, target);
		}
		if (keyword.isWord("use")) {
			return parseUse(meta);
		}
		if (isTypeDefKeyword(keyword)) {
			return parseTypeDef(meta);
		}
		throw error("Unexpected world item", keyword);
	}

	private boolean isExternKeyword(WitToken token) {
		return token.isWord("func") || token.isWord("async") || token.isWord("interface");
	}

	private WitItem.Extern parseExtern() {
		if (peek().isWord("interface")) {
			next();
			expectPunct("{");
			List<WitItem> items = new ArrayList<>();
			while (!peek().isPunct("}")) {
				items.add(parseInterfaceItem());
			}
			expectPunct("}");
			return new WitItem.Extern.ExternInterface(List.copyOf(items));
		}
		WitFunc func = parseFuncType();
		expectPunct(";");
		return new WitItem.Extern.ExternFunc(func);
	}

	// Parses the rest of a reference whose first word is already consumed:
	// `types` | `wasi:io/streams@0.2.0`.
	private WitRef parseRef(String first) {
		if (!peek().isPunct(":")) {
			return WitRef.local(first);
		}
		next();
		String pkgName = expectWordText();
		expectPunct("/");
		String iface = expectWordText();
		String version = null;
		if (peek().isPunct("@")) {
			next();
			version = expectVersionText();
		}
		return new WitRef(new WitPackageName(first, pkgName, version), iface);
	}

	private WitItem.InterfaceDef parseInterface(WitMeta meta) {
		expectWord("interface");
		String name = expectWordText();
		expectPunct("{");
		List<WitItem> items = new ArrayList<>();
		while (!peek().isPunct("}")) {
			items.add(parseInterfaceItem());
		}
		expectPunct("}");
		return new WitItem.InterfaceDef(meta, name, List.copyOf(items));
	}

	private WitItem parseInterfaceItem() {
		WitMeta meta = parseMeta();
		WitToken keyword = peek();
		if (keyword.isWord("use")) {
			return parseUse(meta);
		}
		if (isTypeDefKeyword(keyword)) {
			return parseTypeDef(meta);
		}
		// A named function: `name: [static] [async] func(...) [-> type];`
		String name = expectWordText();
		expectPunct(":");
		WitItem.FuncKind kind = WitItem.FuncKind.PLAIN;
		if (peek().isWord("static")) {
			next();
			kind = WitItem.FuncKind.STATIC;
		}
		WitFunc func = parseFuncType();
		expectPunct(";");
		return new WitItem.FuncDef(meta, name, kind, func);
	}

	private boolean isTypeDefKeyword(WitToken token) {
		return token.isWord("type") || token.isWord("record") || token.isWord("variant") || token.isWord("enum")
				|| token.isWord("flags") || token.isWord("resource");
	}

	private WitItem parseTypeDef(WitMeta meta) {
		WitToken keyword = next();
		String name = expectWordText();
		switch (keyword.text()) {
			case "type": {
				expectPunct("=");
				WitType target = parseType();
				expectPunct(";");
				return new WitItem.TypeAlias(meta, name, target);
			}
			case "record": {
				expectPunct("{");
				List<WitItem.Field> fields = new ArrayList<>();
				while (!peek().isPunct("}")) {
					WitMeta fieldMeta = parseMeta();
					String fieldName = expectWordText();
					expectPunct(":");
					WitType type = parseType();
					fields.add(new WitItem.Field(fieldMeta, fieldName, type));
					if (!consumeIfPunct(",")) {
						break;
					}
				}
				expectPunct("}");
				return new WitItem.RecordDef(meta, name, List.copyOf(fields));
			}
			case "variant": {
				List<WitItem.Case> cases = parseCases(true);
				return new WitItem.VariantDef(meta, name, cases);
			}
			case "enum": {
				List<WitItem.Case> cases = parseCases(false);
				return new WitItem.EnumDef(meta, name, cases);
			}
			case "flags": {
				List<WitItem.Case> cases = parseCases(false);
				return new WitItem.FlagsDef(meta, name, cases);
			}
			case "resource": {
				if (consumeIfPunct(";")) {
					return new WitItem.ResourceDef(meta, name, null);
				}
				expectPunct("{");
				List<WitItem> body = new ArrayList<>();
				while (!peek().isPunct("}")) {
					body.add(parseResourceItem());
				}
				expectPunct("}");
				return new WitItem.ResourceDef(meta, name, List.copyOf(body));
			}
			default:
				throw error("Unexpected type definition", keyword);
		}
	}

	private List<WitItem.Case> parseCases(boolean payloadAllowed) {
		expectPunct("{");
		List<WitItem.Case> cases = new ArrayList<>();
		while (!peek().isPunct("}")) {
			WitMeta caseMeta = parseMeta();
			String caseName = expectWordText();
			WitType payload = null;
			if (payloadAllowed && peek().isPunct("(")) {
				next();
				payload = parseType();
				expectPunct(")");
			}
			cases.add(new WitItem.Case(caseMeta, caseName, payload));
			if (!consumeIfPunct(",")) {
				break;
			}
		}
		expectPunct("}");
		return List.copyOf(cases);
	}

	private WitItem parseResourceItem() {
		WitMeta meta = parseMeta();
		if (peek().isWord("constructor")) {
			next();
			expectPunct("(");
			List<WitFunc.Param> params = parseParams();
			expectPunct(")");
			expectPunct(";");
			return new WitItem.FuncDef(meta, "constructor", WitItem.FuncKind.CONSTRUCTOR,
					new WitFunc(false, params, null));
		}
		String name = expectWordText();
		expectPunct(":");
		WitItem.FuncKind kind = WitItem.FuncKind.PLAIN;
		if (peek().isWord("static")) {
			next();
			kind = WitItem.FuncKind.STATIC;
		}
		WitFunc func = parseFuncType();
		expectPunct(";");
		return new WitItem.FuncDef(meta, name, kind, func);
	}

	private WitItem.Use parseUse(WitMeta meta) {
		expectWord("use");
		WitRef path = parseRef(expectWordText());
		expectPunct(".");
		expectPunct("{");
		List<WitItem.UseName> names = new ArrayList<>();
		while (!peek().isPunct("}")) {
			String name = expectWordText();
			String alias = null;
			if (peek().isWord("as")) {
				next();
				alias = expectWordText();
			}
			names.add(new WitItem.UseName(name, alias));
			if (!consumeIfPunct(",")) {
				break;
			}
		}
		expectPunct("}");
		expectPunct(";");
		return new WitItem.Use(meta, path, List.copyOf(names));
	}

	private WitFunc parseFuncType() {
		boolean async = false;
		if (peek().isWord("async")) {
			next();
			async = true;
		}
		expectWord("func");
		expectPunct("(");
		List<WitFunc.Param> params = parseParams();
		expectPunct(")");
		WitType result = null;
		if (peek().isPunct("->")) {
			next();
			result = parseType();
		}
		return new WitFunc(async, params, result);
	}

	private List<WitFunc.Param> parseParams() {
		List<WitFunc.Param> params = new ArrayList<>();
		while (!peek().isPunct(")")) {
			String name = expectWordText();
			expectPunct(":");
			WitType type = parseType();
			params.add(new WitFunc.Param(name, type));
			if (!consumeIfPunct(",")) {
				break;
			}
		}
		return List.copyOf(params);
	}

	private WitType parseType() {
		WitToken token = next();
		if (token.kind() != WitToken.Kind.WORD) {
			throw error("Expected a type", token);
		}
		String word = token.text();
		if (PRIMITIVES.contains(word)) {
			return new WitType.Prim(word);
		}
		switch (word) {
			case "list": {
				expectPunct("<");
				WitType element = parseType();
				if (peek().isPunct(",")) {
					throw error("Fixed-size list types are not supported", peek());
				}
				expectPunct(">");
				return new WitType.ListOf(element);
			}
			case "option": {
				expectPunct("<");
				WitType element = parseType();
				expectPunct(">");
				return new WitType.OptionOf(element);
			}
			case "tuple": {
				expectPunct("<");
				List<WitType> elements = new ArrayList<>();
				elements.add(parseType());
				while (consumeIfPunct(",")) {
					elements.add(parseType());
				}
				expectPunct(">");
				return new WitType.TupleOf(List.copyOf(elements));
			}
			case "result": {
				if (!peek().isPunct("<")) {
					return new WitType.ResultOf(null, null);
				}
				next();
				WitType ok = null;
				if (peek().isWord("_")) {
					next();
				}
				else {
					ok = parseType();
				}
				WitType err = null;
				if (consumeIfPunct(",")) {
					err = parseType();
				}
				expectPunct(">");
				return new WitType.ResultOf(ok, err);
			}
			case "stream": {
				if (!peek().isPunct("<")) {
					return new WitType.StreamOf(null);
				}
				next();
				WitType element = parseType();
				expectPunct(">");
				return new WitType.StreamOf(element);
			}
			case "future": {
				if (!peek().isPunct("<")) {
					return new WitType.FutureOf(null);
				}
				next();
				WitType element = parseType();
				expectPunct(">");
				return new WitType.FutureOf(element);
			}
			case "borrow": {
				expectPunct("<");
				String resource = expectWordText();
				expectPunct(">");
				return new WitType.BorrowOf(resource);
			}
			case "own": {
				expectPunct("<");
				String resource = expectWordText();
				expectPunct(">");
				return new WitType.OwnOf(resource);
			}
			default:
				return new WitType.Named(word);
		}
	}

	// Docs are the trailing block of /// lines in the next token's trivia; gates are
	// the @name(key = value) attribute lines that follow them.
	private WitMeta parseMeta() {
		List<String> docs = docsFromTrivia(peek().trivia());
		List<WitMeta.Gate> gates = new ArrayList<>();
		while (peek().isPunct("@")) {
			next();
			String name = expectWordText();
			expectPunct("(");
			String key = expectWordText();
			expectPunct("=");
			WitToken valueToken = next();
			if (valueToken.kind() != WitToken.Kind.WORD && valueToken.kind() != WitToken.Kind.VERSION) {
				throw error("Expected a gate attribute value", valueToken);
			}
			expectPunct(")");
			gates.add(new WitMeta.Gate(name, key, valueToken.text()));
		}
		if (docs.isEmpty() && gates.isEmpty()) {
			return WitMeta.none();
		}
		return new WitMeta(List.copyOf(docs), List.copyOf(gates));
	}

	private static List<String> docsFromTrivia(String trivia) {
		String[] lines = trivia.split("\n", -1);
		// The last split element is the indentation before the token, never a comment.
		int end = lines.length - 1;
		int start = end;
		while (start > 0 && lines[start - 1].trim().startsWith("///")) {
			start--;
		}
		if (start == end) {
			return List.of();
		}
		List<String> docs = new ArrayList<>(end - start);
		for (int i = start; i < end; i++) {
			String line = lines[i].trim();
			docs.add(line.substring("///".length()));
		}
		return docs;
	}

	private WitToken peek() {
		return this.tokens.get(this.pos);
	}

	private WitToken peekAt(int lookahead) {
		int index = Math.min(this.pos + lookahead, this.tokens.size() - 1);
		return this.tokens.get(index);
	}

	private WitToken next() {
		WitToken token = this.tokens.get(this.pos);
		if (token.kind() != WitToken.Kind.EOF) {
			this.pos++;
		}
		return token;
	}

	private boolean consumeIfPunct(String punct) {
		if (peek().isPunct(punct)) {
			next();
			return true;
		}
		return false;
	}

	private void expectPunct(String punct) {
		WitToken token = next();
		if (!token.isPunct(punct)) {
			throw error("Expected '" + punct + "'", token);
		}
	}

	private void expectWord(String word) {
		WitToken token = next();
		if (!token.isWord(word)) {
			throw error("Expected '" + word + "'", token);
		}
	}

	private String expectWordText() {
		WitToken token = next();
		if (token.kind() != WitToken.Kind.WORD) {
			throw error("Expected an identifier", token);
		}
		return token.text();
	}

	private String expectVersionText() {
		WitToken token = next();
		if (token.kind() != WitToken.Kind.VERSION) {
			throw error("Expected a version", token);
		}
		return token.text();
	}

	private WitParseException error(String message, WitToken token) {
		String got = token.kind() == WitToken.Kind.EOF ? "end of input" : "'" + token.text() + "'";
		return new WitParseException(message + " but got " + got, this.source, token.offset());
	}

}

package am.ik.wit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

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

	// One duplicate-name scope: an interface's members, a world's type names, and
	// (inline) the top-level items of a package, the methods of a resource body and
	// the parameters of one function each check through here with their own words.
	private interface NameScope {

		void check(String name, int offset);

	}

	// The duplicate-name state of one world. Imports and exports live in separate
	// namespaces (a world may `import a;` and `export a;`), while `use`d names and
	// type definitions share one type namespace that every named import is checked
	// against. Bare `import a;` / `export a;` references only collide with
	// themselves.
	private static final class WorldScope {

		final Set<String> typeNames = new HashSet<>();

		final Map<String, String> importKinds = new HashMap<>();

		final Map<String, Integer> importOffsets = new HashMap<>();

		final Map<String, String> exportKinds = new HashMap<>();

		final Map<String, Integer> exportOffsets = new HashMap<>();

		final Set<WitRef> importRefs = new HashSet<>();

		final Set<WitRef> exportRefs = new HashSet<>();

	}

	// The duplicate-name state of one resource body: at most one constructor, and
	// each method name once.
	private static final class ResourceScope {

		final Set<String> methods = new HashSet<>();

		boolean hasConstructor;

	}

	// The start offset of every item parsed, by identity -- handed out as WitLocations so
	// a consumer can report an error against the WIT line that caused it. Kept beside the
	// model because the WitItem records are values (structural equality is pinned by the
	// round-trip tests), so they cannot carry a position.
	private final java.util.IdentityHashMap<WitItem, Integer> offsets = new java.util.IdentityHashMap<>();

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

	/**
	 * Parses a WIT source text into a document model together with the source position of
	 * every item, for a consumer that reports errors against the originating WIT line
	 * (e.g. {@code rontolisp:wit-export}'s contract check).
	 * @param source the WIT text
	 * @return the parsed document and its item positions
	 * @throws WitParseException on a syntax error or an unsupported construct
	 */
	public static WitParseResult parseLocated(String source) {
		WitParser parser = new WitParser(source);
		WitDocument document = parser.parseDocument();
		return new WitParseResult(document, new WitLocations(source, parser.offsets));
	}

	// Records where an item started, and returns it unchanged.
	private <T extends WitItem> T locate(int offset, T item) {
		this.offsets.put(item, offset);
		return item;
	}

	private WitDocument parseDocument() {
		List<WitItem> items = new ArrayList<>();
		// The top-level items that follow a package header share the header's package;
		// each package block gets its own scope, and a name in one package never
		// collides with the same name in another.
		Map<String, Integer> topScope = new HashMap<>();
		while (peek().kind() != WitToken.Kind.EOF) {
			int start = peek().offset();
			WitMeta meta = parseMeta();
			WitToken keyword = peek();
			if (keyword.isWord("package")) {
				items.add(locate(start, parsePackage(meta)));
			}
			else if (keyword.isWord("world")) {
				items.add(locate(start, parseWorld(meta, topScope)));
			}
			else if (keyword.isWord("interface")) {
				items.add(locate(start, parseInterface(meta, topScope)));
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
		Map<String, Integer> blockScope = new HashMap<>();
		while (!peek().isPunct("}")) {
			int start = peek().offset();
			WitMeta itemMeta = parseMeta();
			WitToken keyword = peek();
			if (keyword.isWord("world")) {
				items.add(locate(start, parseWorld(itemMeta, blockScope)));
			}
			else if (keyword.isWord("interface")) {
				items.add(locate(start, parseInterface(itemMeta, blockScope)));
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

	private WitItem.World parseWorld(WitMeta meta, Map<String, Integer> topScope) {
		expectWord("world");
		int nameOffset = peek().offset();
		String name = expectWordText();
		checkTopLevelName(topScope, name, nameOffset);
		expectPunct("{");
		List<WitItem> items = new ArrayList<>();
		WorldScope scope = new WorldScope();
		while (!peek().isPunct("}")) {
			items.add(parseWorldItem(scope));
		}
		expectPunct("}");
		return new WitItem.World(meta, name, List.copyOf(items));
	}

	private WitItem parseWorldItem(WorldScope scope) {
		int start = peek().offset();
		return locate(start, parseWorldItemBody(scope));
	}

	private WitItem parseWorldItemBody(WorldScope scope) {
		WitMeta meta = parseMeta();
		WitToken keyword = peek();
		if (keyword.isWord("import") || keyword.isWord("export")) {
			boolean isImport = keyword.isWord("import");
			next();
			int nameOffset = peek().offset();
			String first = expectWordText();
			// `name: func(...)` / `name: interface {...}` versus a path -- a path's
			// ':' is followed by the package name and then '/', an extern's ':' by
			// 'func' / 'async' / 'static'? no: by the extern keyword.
			if (peek().isPunct(":") && isExternKeyword(peekAt(1))) {
				next();
				WitItem.Extern extern = parseExtern();
				String kind = (extern instanceof WitItem.Extern.ExternFunc) ? "func" : "interface";
				if (isImport) {
					checkWorldImport(scope, first, kind, nameOffset);
					return new WitItem.ImportNamed(meta, first, extern);
				}
				checkWorldExport(scope, first, kind, nameOffset);
				return new WitItem.ExportNamed(meta, first, extern);
			}
			WitRef target = parseRef(first);
			expectPunct(";");
			if (isImport) {
				checkWorldImportRef(scope, target, nameOffset);
				return new WitItem.ImportRef(meta, target);
			}
			checkWorldExportRef(scope, target, nameOffset);
			return new WitItem.ExportRef(meta, target);
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
			return parseUse(meta, (name, offset) -> checkWorldTypeName(scope, name, offset));
		}
		if (isTypeDefKeyword(keyword)) {
			return parseTypeDef(meta, (name, offset) -> checkWorldTypeName(scope, name, offset));
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
			Set<String> seen = new HashSet<>();
			NameScope scope = (name, offset) -> checkInterfaceName(seen, name, offset);
			while (!peek().isPunct("}")) {
				items.add(parseInterfaceItem(scope));
			}
			expectPunct("}");
			return new WitItem.Extern.ExternInterface(List.copyOf(items));
		}
		WitFunc func = parseFuncType();
		expectPunct(";");
		return new WitItem.Extern.ExternFunc(func);
	}

	// Every duplicate-name scope in one place, each in `wasm-tools component wit`
	// 1.254.0's words (all measured 2026-10-13). An interface -- named or inline
	// in a world -- defines each member name once: a type and a function may not
	// share a name, nor may two types, two functions, or a `use`d name and a local
	// one ("name `x` is defined more than once"). A world's `use`d names and type
	// definitions share one type namespace with the same words, while a named
	// import that collides says which namespace loses ("import `x` conflicts with
	// prior func/interface/type of same name", the export side mirroring it); a
	// bare `import a;` / `export a;` repeated says the interface cannot be
	// imported/exported more than once. Two top-level items in one package may not
	// share a name whatever their kind ("duplicate item named `x`"). A resource
	// body holds one constructor and each method name once ("duplicate
	// constructors" / "duplicate function name `m`"), and one function's
	// parameters each name once ("param `x` is defined more than once").
	// Deliberately unchecked, because wasm-tools accepts them: duplicate `record`
	// fields, duplicate `enum`/`flags`/`variant` cases, one name in two interfaces
	// of a package, a world importing and exporting one name, an export beside a
	// type of the same name, and a bare reference beside a named item of the same
	// name. Every `wit-import`/`wit-export` path parses here, so refusing here
	// covers them all. A `use`d name counts under its alias when renamed
	// (`use types.{quote as q}` frees `quote`); names in different interfaces
	// never collide.
	private void checkInterfaceName(Set<String> scope, String name, int offset) {
		if (!scope.add(name)) {
			throw duplicate("name `" + name + "` is defined more than once", offset);
		}
	}

	private WitParseException duplicate(String message, int offset) {
		return new WitParseException(message, this.source, offset);
	}

	private void checkTopLevelName(Map<String, Integer> scope, String name, int offset) {
		if (scope.putIfAbsent(name, offset) != null) {
			throw duplicate("duplicate item named `" + name + "`", offset);
		}
	}

	private void checkWorldTypeName(WorldScope scope, String name, int offset) {
		checkInterfaceName(scope.typeNames, name, offset);
		// wasm-tools resolves a world's types however they are ordered against its
		// imports, so a named import always loses to a type: the error blames the
		// import even when the import comes first in the source.
		Integer importOffset = scope.importOffsets.get(name);
		if (importOffset != null) {
			throw duplicate("import `" + name + "` conflicts with prior type of same name", importOffset);
		}
	}

	private void checkWorldImport(WorldScope scope, String name, String kind, int offset) {
		String prior = scope.importKinds.get(name);
		if (prior != null) {
			throw duplicate("import `" + name + "` conflicts with prior " + prior + " of same name", offset);
		}
		if (scope.typeNames.contains(name)) {
			throw duplicate("import `" + name + "` conflicts with prior type of same name", offset);
		}
		scope.importKinds.put(name, kind);
		scope.importOffsets.put(name, offset);
	}

	private void checkWorldExport(WorldScope scope, String name, String kind, int offset) {
		String prior = scope.exportKinds.get(name);
		if (prior != null) {
			throw duplicate("export `" + name + "` conflicts with prior " + prior + " of same name", offset);
		}
		scope.exportKinds.put(name, kind);
		scope.exportOffsets.put(name, offset);
	}

	private void checkWorldImportRef(WorldScope scope, WitRef target, int offset) {
		if (!scope.importRefs.add(target)) {
			throw duplicate("interface cannot be imported more than once", offset);
		}
	}

	private void checkWorldExportRef(WorldScope scope, WitRef target, int offset) {
		if (!scope.exportRefs.add(target)) {
			throw duplicate("interface cannot be exported more than once", offset);
		}
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

	private WitItem.InterfaceDef parseInterface(WitMeta meta, Map<String, Integer> topScope) {
		expectWord("interface");
		int nameOffset = peek().offset();
		String name = expectWordText();
		checkTopLevelName(topScope, name, nameOffset);
		expectPunct("{");
		List<WitItem> items = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		NameScope scope = (member, offset) -> checkInterfaceName(seen, member, offset);
		while (!peek().isPunct("}")) {
			items.add(parseInterfaceItem(scope));
		}
		expectPunct("}");
		return new WitItem.InterfaceDef(meta, name, List.copyOf(items));
	}

	private WitItem parseInterfaceItem(NameScope scope) {
		int start = peek().offset();
		return locate(start, parseInterfaceItemBody(scope));
	}

	private WitItem parseInterfaceItemBody(NameScope scope) {
		WitMeta meta = parseMeta();
		WitToken keyword = peek();
		if (keyword.isWord("use")) {
			return parseUse(meta, scope);
		}
		if (isTypeDefKeyword(keyword)) {
			return parseTypeDef(meta, scope);
		}
		// A named function: `name: [static] [async] func(...) [-> type];`
		int nameOffset = peek().offset();
		String name = expectWordText();
		expectPunct(":");
		WitItem.FuncKind kind = WitItem.FuncKind.PLAIN;
		if (peek().isWord("static")) {
			next();
			kind = WitItem.FuncKind.STATIC;
		}
		WitFunc func = parseFuncType();
		expectPunct(";");
		scope.check(name, nameOffset);
		return new WitItem.FuncDef(meta, name, kind, func);
	}

	private boolean isTypeDefKeyword(WitToken token) {
		return token.isWord("type") || token.isWord("record") || token.isWord("variant") || token.isWord("enum")
				|| token.isWord("flags") || token.isWord("resource");
	}

	private WitItem parseTypeDef(WitMeta meta, @Nullable NameScope scope) {
		WitToken keyword = next();
		int nameOffset = peek().offset();
		String name = expectWordText();
		if (scope != null) {
			scope.check(name, nameOffset);
		}
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
				ResourceScope resourceScope = new ResourceScope();
				while (!peek().isPunct("}")) {
					body.add(parseResourceItem(resourceScope));
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

	private WitItem parseResourceItem(ResourceScope scope) {
		int start = peek().offset();
		return locate(start, parseResourceItemBody(scope));
	}

	private WitItem parseResourceItemBody(ResourceScope scope) {
		WitMeta meta = parseMeta();
		if (peek().isWord("constructor")) {
			int ctorOffset = peek().offset();
			next();
			expectPunct("(");
			List<WitFunc.Param> params = parseParams();
			expectPunct(")");
			expectPunct(";");
			if (scope.hasConstructor) {
				throw duplicate("duplicate constructors", ctorOffset);
			}
			scope.hasConstructor = true;
			return new WitItem.FuncDef(meta, "constructor", WitItem.FuncKind.CONSTRUCTOR,
					new WitFunc(false, params, null));
		}
		int nameOffset = peek().offset();
		String name = expectWordText();
		expectPunct(":");
		WitItem.FuncKind kind = WitItem.FuncKind.PLAIN;
		if (peek().isWord("static")) {
			next();
			kind = WitItem.FuncKind.STATIC;
		}
		WitFunc func = parseFuncType();
		expectPunct(";");
		if (!scope.methods.add(name)) {
			throw duplicate("duplicate function name `" + name + "`", nameOffset);
		}
		return new WitItem.FuncDef(meta, name, kind, func);
	}

	private WitItem.Use parseUse(WitMeta meta, @Nullable NameScope scope) {
		expectWord("use");
		WitRef path = parseRef(expectWordText());
		expectPunct(".");
		expectPunct("{");
		List<WitItem.UseName> names = new ArrayList<>();
		while (!peek().isPunct("}")) {
			int nameOffset = peek().offset();
			String name = expectWordText();
			String alias = null;
			int aliasOffset = nameOffset;
			if (peek().isWord("as")) {
				next();
				aliasOffset = peek().offset();
				alias = expectWordText();
			}
			// A renamed import introduces only the alias (`use types.{quote as q}`
			// frees `quote`); an unrenamed one introduces the name itself.
			if (scope != null) {
				scope.check(alias != null ? alias : name, aliasOffset);
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
		Set<String> names = new HashSet<>();
		while (!peek().isPunct(")")) {
			int nameOffset = peek().offset();
			String name = expectWordText();
			if (!names.add(name)) {
				throw duplicate("param `" + name + "` is defined more than once", nameOffset);
			}
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
		// An escaped word is an identifier by construction -- `%list` names a user type
		// called `list`, it is never the built-in list type.
		if (word.startsWith("%")) {
			return new WitType.Named(WitIdentifiers.unescape(word));
		}
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

	// Every identifier position. The %-escaping a keyword-colliding name needs in the
	// source text is not part of the name (WitIdentifiers), so the model holds the bare
	// word and WitPrinter re-escapes on the way out.
	private String expectWordText() {
		WitToken token = next();
		if (token.kind() != WitToken.Kind.WORD) {
			throw error("Expected an identifier", token);
		}
		return WitIdentifiers.unescape(token.text());
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

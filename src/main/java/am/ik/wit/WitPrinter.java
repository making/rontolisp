package am.ik.wit;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * Prints WIT.
 *
 * <p>
 * Two modes: {@link #printVerbatim(List)} reproduces a lexed source byte-for-byte from
 * its trivia-carrying tokens (the round-trip guarantee), and {@link #print(WitDocument)}
 * renders a document model in the canonical style of {@code wasm-tools component wit}
 * (two-space indentation, one blank line between interface members and between a world's
 * import and export blocks, no blank between the world and the first package block, two
 * blank lines between package blocks, trailing commas on all members) — pinned
 * byte-for-byte against that tool's real output by the template fixtures in the test
 * suite.
 */
public final class WitPrinter {

	private WitPrinter() {
	}

	/**
	 * Reassembles the exact source text a token list was lexed from.
	 * @param tokens the tokens produced by {@link WitLexer#lex(String)}
	 * @return the original text, byte-for-byte
	 */
	public static String printVerbatim(List<WitToken> tokens) {
		StringBuilder sb = new StringBuilder();
		for (WitToken token : tokens) {
			sb.append(token.trivia()).append(token.text());
		}
		return sb.toString();
	}

	/**
	 * Renders a document in the canonical {@code wasm-tools component wit} style.
	 * @param document the document model
	 * @return the WIT text (ends with a newline when the document is non-empty)
	 */
	public static String print(WitDocument document) {
		StringBuilder sb = new StringBuilder();
		WitItem previous = null;
		for (WitItem item : document.items()) {
			sb.append(topSeparator(previous, item));
			printTopItem(sb, item);
			previous = item;
		}
		return sb.toString();
	}

	// wasm-tools prints no blank line between the world and the first package block,
	// two blank lines between package blocks, and one everywhere else.
	private static String topSeparator(@Nullable WitItem previous, WitItem current) {
		if (previous == null) {
			return "";
		}
		if (current instanceof WitItem.PackageBlock) {
			return (previous instanceof WitItem.PackageBlock) ? "\n\n"
					: (previous instanceof WitItem.World) ? "" : "\n";
		}
		return "\n";
	}

	private static void printTopItem(StringBuilder sb, WitItem item) {
		switch (item) {
			case WitItem.PackageHeader header -> {
				printMeta(sb, header.meta(), 0);
				sb.append("package ").append(header.name()).append(";\n");
			}
			case WitItem.PackageBlock block -> {
				printMeta(sb, block.meta(), 0);
				sb.append("package ").append(block.name()).append(" {\n");
				for (WitItem member : block.items()) {
					printItem(sb, member, 1);
				}
				sb.append("}\n");
			}
			default -> printItem(sb, item, 0);
		}
	}

	private static void printItem(StringBuilder sb, WitItem item, int depth) {
		String ind = indent(depth);
		printMeta(sb, item.meta(), depth);
		switch (item) {
			case WitItem.World world -> {
				sb.append(ind).append("world ").append(world.name()).append(" {\n");
				WitItem previous = null;
				for (WitItem member : world.items()) {
					if (previous != null && worldGroup(previous) != worldGroup(member)) {
						sb.append('\n');
					}
					printItem(sb, member, depth + 1);
					previous = member;
				}
				sb.append(ind).append("}\n");
			}
			case WitItem.InterfaceDef iface -> {
				sb.append(ind).append("interface ").append(iface.name()).append(" {\n");
				printInterfaceMembers(sb, iface.items(), depth);
				sb.append(ind).append("}\n");
			}
			case WitItem.Use use -> {
				sb.append(ind).append("use ").append(use.path()).append(".{");
				for (int i = 0; i < use.names().size(); i++) {
					WitItem.UseName name = use.names().get(i);
					if (i > 0) {
						sb.append(", ");
					}
					sb.append(name.name());
					if (name.alias() != null) {
						sb.append(" as ").append(name.alias());
					}
				}
				sb.append("};\n");
			}
			case WitItem.TypeAlias alias -> sb.append(ind)
				.append("type ")
				.append(alias.name())
				.append(" = ")
				.append(type(alias.target()))
				.append(";\n");
			case WitItem.RecordDef record -> {
				sb.append(ind).append("record ").append(record.name()).append(" {\n");
				for (WitItem.Field field : record.fields()) {
					printMeta(sb, field.meta(), depth + 1);
					sb.append(indent(depth + 1))
						.append(field.name())
						.append(": ")
						.append(type(field.type()))
						.append(",\n");
				}
				sb.append(ind).append("}\n");
			}
			case WitItem.VariantDef variant -> printCases(sb, "variant", variant.name(), variant.cases(), depth);
			case WitItem.EnumDef enumDef -> printCases(sb, "enum", enumDef.name(), enumDef.cases(), depth);
			case WitItem.FlagsDef flags -> printCases(sb, "flags", flags.name(), flags.cases(), depth);
			case WitItem.ResourceDef resource -> {
				sb.append(ind).append("resource ").append(resource.name());
				List<WitItem> body = resource.body();
				if (body == null) {
					sb.append(";\n");
				}
				else {
					sb.append(" {\n");
					for (WitItem member : body) {
						printItem(sb, member, depth + 1);
					}
					sb.append(ind).append("}\n");
				}
			}
			case WitItem.FuncDef func -> {
				sb.append(ind);
				if (func.kind() == WitItem.FuncKind.CONSTRUCTOR) {
					sb.append("constructor(");
					printParams(sb, func.func().params());
					sb.append(");\n");
				}
				else {
					sb.append(func.name()).append(": ");
					if (func.kind() == WitItem.FuncKind.STATIC) {
						sb.append("static ");
					}
					printFuncType(sb, func.func());
					sb.append(";\n");
				}
			}
			case WitItem.ImportRef importRef ->
				sb.append(ind).append("import ").append(importRef.target()).append(";\n");
			case WitItem.ExportRef exportRef ->
				sb.append(ind).append("export ").append(exportRef.target()).append(";\n");
			case WitItem.ImportNamed importNamed ->
				printNamedExtern(sb, "import", importNamed.name(), importNamed.extern(), depth);
			case WitItem.ExportNamed exportNamed ->
				printNamedExtern(sb, "export", exportNamed.name(), exportNamed.extern(), depth);
			case WitItem.Include include -> sb.append(ind).append("include ").append(include.target()).append(";\n");
			case WitItem.PackageHeader header -> sb.append(ind).append("package ").append(header.name()).append(";\n");
			case WitItem.PackageBlock block ->
				throw new IllegalArgumentException("package block cannot nest inside " + block.name());
		}
	}

	private static void printNamedExtern(StringBuilder sb, String keyword, String name, WitItem.Extern extern,
			int depth) {
		String ind = indent(depth);
		sb.append(ind).append(keyword).append(' ').append(name).append(": ");
		switch (extern) {
			case WitItem.Extern.ExternFunc externFunc -> {
				printFuncType(sb, externFunc.func());
				sb.append(";\n");
			}
			case WitItem.Extern.ExternInterface externInterface -> {
				sb.append("interface {\n");
				printInterfaceMembers(sb, externInterface.items(), depth);
				sb.append(ind).append("}\n");
			}
		}
	}

	// Interface members are blank-line separated, except that consecutive use clauses
	// group together (the way wasm-tools prints an interface's use block).
	private static void printInterfaceMembers(StringBuilder sb, List<WitItem> members, int depth) {
		WitItem previous = null;
		for (WitItem member : members) {
			if (previous != null && !(previous instanceof WitItem.Use && member instanceof WitItem.Use)) {
				sb.append('\n');
			}
			printItem(sb, member, depth + 1);
			previous = member;
		}
	}

	private static void printCases(StringBuilder sb, String keyword, String name, List<WitItem.Case> cases, int depth) {
		String ind = indent(depth);
		sb.append(ind).append(keyword).append(' ').append(name).append(" {\n");
		for (WitItem.Case c : cases) {
			printMeta(sb, c.meta(), depth + 1);
			sb.append(indent(depth + 1)).append(c.name());
			if (c.payload() != null) {
				sb.append('(').append(type(c.payload())).append(')');
			}
			sb.append(",\n");
		}
		sb.append(ind).append("}\n");
	}

	private static void printFuncType(StringBuilder sb, WitFunc func) {
		if (func.async()) {
			sb.append("async ");
		}
		sb.append("func(");
		printParams(sb, func.params());
		sb.append(')');
		if (func.result() != null) {
			sb.append(" -> ").append(type(func.result()));
		}
	}

	private static void printParams(StringBuilder sb, List<WitFunc.Param> params) {
		for (int i = 0; i < params.size(); i++) {
			if (i > 0) {
				sb.append(", ");
			}
			sb.append(params.get(i).name()).append(": ").append(type(params.get(i).type()));
		}
	}

	private static void printMeta(StringBuilder sb, WitMeta meta, int depth) {
		String ind = indent(depth);
		for (String doc : meta.docs()) {
			sb.append(ind).append("///").append(doc).append('\n');
		}
		for (WitMeta.Gate gate : meta.gates()) {
			sb.append(ind)
				.append('@')
				.append(gate.name())
				.append('(')
				.append(gate.key())
				.append(" = ")
				.append(gate.value())
				.append(")\n");
		}
	}

	/**
	 * Renders a type use, e.g. {@code result<_, error-code>}.
	 * @param type the type
	 * @return its WIT text
	 */
	public static String type(WitType type) {
		return switch (type) {
			case WitType.Prim prim -> prim.name();
			case WitType.Named named -> named.name();
			case WitType.ListOf list -> "list<" + type(list.element()) + ">";
			case WitType.OptionOf option -> "option<" + type(option.element()) + ">";
			case WitType.ResultOf result -> {
				WitType ok = result.ok();
				WitType err = result.err();
				if (err != null) {
					yield "result<" + (ok == null ? "_" : type(ok)) + ", " + type(err) + ">";
				}
				yield (ok == null) ? "result" : "result<" + type(ok) + ">";
			}
			case WitType.TupleOf tuple -> {
				StringBuilder sb = new StringBuilder("tuple<");
				for (int i = 0; i < tuple.elements().size(); i++) {
					if (i > 0) {
						sb.append(", ");
					}
					sb.append(type(tuple.elements().get(i)));
				}
				yield sb.append('>').toString();
			}
			case WitType.StreamOf stream ->
				stream.element() == null ? "stream" : "stream<" + type(stream.element()) + ">";
			case WitType.FutureOf future ->
				future.element() == null ? "future" : "future<" + type(future.element()) + ">";
			case WitType.BorrowOf borrow -> "borrow<" + borrow.resource() + ">";
			case WitType.OwnOf own -> "own<" + own.resource() + ">";
		};
	}

	// Which world members group together without a separating blank line: consecutive
	// imports, consecutive exports; everything else is its own group.
	private static int worldGroup(WitItem item) {
		return switch (item) {
			case WitItem.ImportRef ignored -> 1;
			case WitItem.ImportNamed ignored -> 1;
			case WitItem.ExportRef ignored -> 2;
			case WitItem.ExportNamed ignored -> 2;
			case WitItem.Include ignored -> 3;
			case WitItem.Use ignored -> 4;
			default -> 5;
		};
	}

	private static String indent(int depth) {
		return "  ".repeat(depth);
	}

}

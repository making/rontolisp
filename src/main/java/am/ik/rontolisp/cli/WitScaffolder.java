package am.ik.rontolisp.cli;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import am.ik.rontolisp.eval.JavaHostBoundary;
import am.ik.rontolisp.reader.LispReadException;

import am.ik.wit.WitDocument;
import am.ik.wit.WitFunc;
import am.ik.wit.WitItem;
import am.ik.wit.WitMeta;
import am.ik.wit.WitParser;
import am.ik.wit.WitResolver;
import am.ik.wit.WitType;

import org.jspecify.annotations.Nullable;

/**
 * Turns a {@code .wit} world into a runnable rontolisp skeleton
 * ({@code rontolisp --scaffold-wit world.wit -o impl.lisp}): a
 * {@code rontolisp:wit-export} directive plus one {@code defun} stub per export, with the
 * WIT parameter names and {@code ///} doc comments carried over -- or, for
 * {@code -o Greeter.java}, the {@code @WitExport} class of the Java subset
 * ({@link #scaffoldJava}). The answer to "someone handed me a {@code .wit}, now what".
 *
 * <p>
 * The result compiles unchanged -- the stubs signal at run time, not at compile time, so
 * the contract check passes and the program can be filled in one export at a time.
 */
final class WitScaffolder {

	private WitScaffolder() {
	}

	/**
	 * Renders the Lisp skeleton implementing a WIT world.
	 * @param witSource the WIT text
	 * @param witPath the WIT path to write into the {@code wit-export} directive (as the
	 * generated source should spell it: relative to the generated file)
	 * @param world the world to implement, or {@code null} to use the file's only world
	 * @return the Lisp source text (ends with a newline)
	 */
	static String scaffold(String witSource, String witPath, @Nullable String world) {
		WitDocument document = WitParser.parse(witSource);
		WitItem.World target = selectWorld(document, world, witPath);
		StringBuilder out = new StringBuilder();
		out.append(";;;; Implementation of the WIT world '")
			.append(target.name())
			.append("' (")
			.append(witPath)
			.append(").\n");
		out.append(";;;;\n");
		out.append(";;;; The world is the contract: the compiler checks every defun below against\n");
		out.append(";;;; it, so a renamed export, a changed arity or a changed type is a compile\n");
		out.append(";;;; error rather than a runtime surprise. Fill in the bodies; each one signals\n");
		out.append(";;;; until you do.\n");
		WitResolver resolver = new WitResolver(document);
		for (WitItem item : target.items()) {
			switch (item) {
				case WitItem.ExportNamed export -> {
					switch (export.extern()) {
						case WitItem.Extern.ExternFunc extern -> {
							out.append('\n');
							appendStub(out, export.name(), export.meta(), extern.func());
						}
						// An inline interface export: stub each of its functions.
						case WitItem.Extern.ExternInterface inline -> appendInterfaceStubs(out, inline.items());
					}
				}
				// `export add;` -- an interface defined in this file: stub the functions
				// of
				// the interface it resolves to. A reference to an interface the file does
				// not define (the fixed wasi:cli/run entry point, or an external
				// interface)
				// resolves to nothing and has no functions the user implements.
				case WitItem.ExportRef ref -> {
					WitItem.InterfaceDef iface = resolver.findInterface(ref.target().toString());
					if (iface != null) {
						appendInterfaceStubs(out, iface.items());
					}
				}
				default -> {
				}
			}
		}
		out.append("\n(rontolisp:wit-export \"")
			.append(witPath)
			.append("\" :world ")
			.append(target.name())
			.append(")\n");
		return out.toString();
	}

	/**
	 * Renders the Java skeleton implementing a WIT world: a {@code @WitExport} class, a
	 * {@code @WitImport} member interface for each imported interface, and the types they
	 * reach ({@code JavaHostBoundary.scaffold}).
	 * @param witSource the WIT text
	 * @param witFile the WIT file, as the command line names it
	 * @param witPath the WIT path the annotations name (relative to the generated file)
	 * @param entry the generated file, or {@code null} when it is printed
	 * @param world the world to implement, or {@code null} to use the file's only world
	 * @param className the class's name
	 * @param skipped told the refusal of each item left out
	 * @return the Java source text (ends with a newline)
	 */
	static String scaffoldJava(String witSource, String witFile, String witPath, @Nullable String entry,
			@Nullable String world, String className, Consumer<String> skipped) {
		WitDocument document = WitParser.parse(witSource);
		WitItem.World target = selectWorld(document, world, witPath);
		Map<String, List<String>> docs = new HashMap<>();
		WitResolver resolver = new WitResolver(document);
		collectDocs(document.items(), resolver, docs);
		docs.put("#", target.meta().docs());
		for (WitItem item : target.items()) {
			switch (item) {
				case WitItem.ExportNamed export -> {
					switch (export.extern()) {
						case WitItem.Extern.ExternFunc extern -> docs.put("#" + export.name(), export.meta().docs());
						case WitItem.Extern.ExternInterface inline -> {
							docs.put(export.name() + "#", export.meta().docs());
							scopeDocs(export.name(), inline.items(), docs);
						}
					}
				}
				default -> scopeDocs("", List.of(item), docs);
			}
		}
		try {
			return JavaHostBoundary.scaffold(witFile, witPath, entry, target.name(), className, docs, skipped);
		}
		catch (LispReadException ex) {
			throw new UnsupportedOperationException("--scaffold-wit: " + ex.getMessage(), ex);
		}
	}

	/**
	 * The name of the world a skeleton implements.
	 * @param witSource the WIT text
	 * @param world the world named, or {@code null} for the file's only world
	 * @param witPath the WIT path, for a message
	 * @return the world's name
	 */
	static String worldName(String witSource, @Nullable String world, String witPath) {
		return selectWorld(WitParser.parse(witSource), world, witPath).name();
	}

	// The docs of every interface the file defines, by its canonical id.
	private static void collectDocs(List<WitItem> items, WitResolver resolver, Map<String, List<String>> docs) {
		for (WitItem item : items) {
			switch (item) {
				case WitItem.InterfaceDef iface -> {
					String id = resolver.canonicalId(iface);
					if (id != null) {
						docs.put(id + "#", iface.meta().docs());
						scopeDocs(id, iface.items(), docs);
					}
				}
				case WitItem.PackageBlock block -> collectDocs(block.items(), resolver, docs);
				default -> {
				}
			}
		}
	}

	private static void scopeDocs(String scope, List<WitItem> items, Map<String, List<String>> docs) {
		for (WitItem item : items) {
			String name = switch (item) {
				case WitItem.FuncDef func -> func.name();
				case WitItem.RecordDef record -> record.name();
				case WitItem.VariantDef variant -> variant.name();
				case WitItem.EnumDef enumDef -> enumDef.name();
				case WitItem.FlagsDef flags -> flags.name();
				case WitItem.TypeAlias alias -> alias.name();
				case WitItem.ResourceDef resource -> {
					for (WitItem inner : resource.body() == null ? List.<WitItem>of() : resource.body()) {
						if (inner instanceof WitItem.FuncDef func) {
							docs.put(scope + "#" + resource.name() + "." + func.name(), func.meta().docs());
						}
					}
					yield resource.name();
				}
				default -> null;
			};
			if (name != null) {
				docs.put(scope + "#" + name, item.meta().docs());
			}
		}
	}

	private static void appendInterfaceStubs(StringBuilder out, List<WitItem> members) {
		for (WitItem member : members) {
			if (member instanceof WitItem.FuncDef func && func.kind() == WitItem.FuncKind.PLAIN) {
				out.append('\n');
				appendStub(out, func.name(), func.meta(), func.func());
			}
		}
	}

	private static void appendStub(StringBuilder out, String name, WitMeta meta, WitFunc func) {
		for (String doc : meta.docs()) {
			out.append(";;;").append(doc).append('\n');
		}
		out.append(";;; WIT: ").append(name).append(": ").append(signature(func)).append('\n');
		out.append("(defun ").append(name).append(" (");
		List<String> names = new ArrayList<>();
		for (WitFunc.Param param : func.params()) {
			names.add(param.name());
		}
		out.append(String.join(" ", names)).append(")\n");
		out.append("  (error \"").append(name).append(" is not implemented yet\"))\n");
	}

	// The WIT signature, rendered back into WIT so the stub carries the contract it must
	// satisfy (types included -- the scaffolded Lisp itself is untyped).
	static String signature(WitFunc func) {
		List<String> params = new ArrayList<>();
		for (WitFunc.Param param : func.params()) {
			params.add(param.name() + ": " + typeName(param.type()));
		}
		String rendered = (func.async() ? "async func(" : "func(") + String.join(", ", params) + ")";
		return func.result() == null ? rendered : rendered + " -> " + typeName(func.result());
	}

	private static String typeName(WitType type) {
		return switch (type) {
			case WitType.Prim prim -> prim.name();
			case WitType.Named named -> named.name();
			case WitType.ListOf list -> "list<" + typeName(list.element()) + ">";
			case WitType.OptionOf option -> "option<" + typeName(option.element()) + ">";
			case WitType.TupleOf tuple ->
				"tuple<" + String.join(", ", tuple.elements().stream().map(WitScaffolder::typeName).toList()) + ">";
			case WitType.ResultOf result -> resultName(result);
			case WitType.BorrowOf borrow -> "borrow<" + borrow.resource() + ">";
			case WitType.OwnOf own -> "own<" + own.resource() + ">";
			case WitType.StreamOf stream ->
				stream.element() == null ? "stream" : "stream<" + typeName(stream.element()) + ">";
			case WitType.FutureOf future ->
				future.element() == null ? "future" : "future<" + typeName(future.element()) + ">";
		};
	}

	private static String resultName(WitType.ResultOf result) {
		if (result.ok() == null && result.err() == null) {
			return "result";
		}
		String ok = result.ok() == null ? "_" : typeName(result.ok());
		return result.err() == null ? "result<" + ok + ">" : "result<" + ok + ", " + typeName(result.err()) + ">";
	}

	private static WitItem.World selectWorld(WitDocument document, @Nullable String requested, String witPath) {
		List<WitItem.World> worlds = new ArrayList<>();
		collectWorlds(document.items(), worlds);
		if (requested != null) {
			for (WitItem.World world : worlds) {
				if (requested.equals(world.name())) {
					return world;
				}
			}
			throw new UnsupportedOperationException("--scaffold-wit: " + witPath + " has no world named '" + requested
					+ "' (found: " + names(worlds) + ")");
		}
		if (worlds.size() == 1) {
			return worlds.get(0);
		}
		if (worlds.isEmpty()) {
			throw new UnsupportedOperationException("--scaffold-wit: " + witPath + " declares no world");
		}
		throw new UnsupportedOperationException("--scaffold-wit: " + witPath + " declares " + worlds.size()
				+ " worlds (" + names(worlds) + "); name one with --world");
	}

	private static void collectWorlds(List<WitItem> items, List<WitItem.World> worlds) {
		for (WitItem item : items) {
			switch (item) {
				case WitItem.World world -> worlds.add(world);
				case WitItem.PackageBlock block -> collectWorlds(block.items(), worlds);
				default -> {
				}
			}
		}
	}

	private static String names(List<WitItem.World> worlds) {
		return String.join(", ", worlds.stream().map(WitItem.World::name).toList());
	}

}

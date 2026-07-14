package am.ik.rontolisp.cli;

import java.util.ArrayList;
import java.util.List;

import am.ik.wit.WitDocument;
import am.ik.wit.WitFunc;
import am.ik.wit.WitItem;
import am.ik.wit.WitParser;
import am.ik.wit.WitType;

import org.jspecify.annotations.Nullable;

/**
 * Turns a {@code .wit} world into a runnable rontolisp skeleton
 * ({@code rontolisp --scaffold-wit world.wit -o impl.lisp}): a
 * {@code rontolisp:wit-export} directive plus one {@code defun} stub per export, with the
 * WIT parameter names and {@code ///} doc comments carried over. The answer to "someone
 * handed me a {@code .wit}, now what".
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
		for (WitItem item : target.items()) {
			if (item instanceof WitItem.ExportNamed export
					&& export.extern() instanceof WitItem.Extern.ExternFunc extern) {
				out.append('\n');
				appendStub(out, export, extern.func());
			}
		}
		out.append("\n(rontolisp:wit-export \"")
			.append(witPath)
			.append("\" :world ")
			.append(target.name())
			.append(")\n");
		return out.toString();
	}

	private static void appendStub(StringBuilder out, WitItem.ExportNamed export, WitFunc func) {
		for (String doc : export.meta().docs()) {
			out.append(";;;").append(doc).append('\n');
		}
		out.append(";;; WIT: ").append(export.name()).append(": ").append(signature(func)).append('\n');
		out.append("(defun ").append(export.name()).append(" (");
		List<String> names = new ArrayList<>();
		for (WitFunc.Param param : func.params()) {
			names.add(param.name());
		}
		out.append(String.join(" ", names)).append(")\n");
		out.append("  (error \"").append(export.name()).append(" is not implemented yet\"))\n");
	}

	// The WIT signature, rendered back into WIT so the stub carries the contract it must
	// satisfy (types included -- the scaffolded Lisp itself is untyped).
	private static String signature(WitFunc func) {
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

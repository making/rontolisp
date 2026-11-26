package am.ik.wit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Assembles a WIT package published as a directory -- its {@code *.wit} files, and each
 * entry of its {@code deps/} -- into the one {@link WitDocument} a single file with
 * nested {@code package ... { ... }} blocks would parse to, the layout
 * {@code wasm-tools}, {@code wit-bindgen} and {@code wkg wit fetch} read and write.
 *
 * <p>
 * The package's files share one package: each may declare it ({@code package
 * wasi:keyvalue@0.2.0-draft2;}), one declaring it is enough, and two declaring different
 * ones is an error. Their interfaces and worlds become the document's top-level items, so
 * an unqualified {@code use store.{bucket};} in one file reaches {@code store} in
 * another, which is the WIT rule. A dependency (a directory of files or one file) becomes
 * a {@code package} block of the package it declares; the blocks a dependency file nests
 * stay blocks of their own. The parser has no top-level {@code use}, the one item WIT
 * scopes to its file, so nothing one file declares can reach another file's names except
 * through what WIT itself shares.
 *
 * <p>
 * Every file is parsed on its own, and the positions stay per file
 * ({@link WitLocations#fileOf}): an error names the file and line an item was written on,
 * never a line of a merged text.
 */
public final class WitPackageAssembler {

	private WitPackageAssembler() {
	}

	/**
	 * Assembles a package and its dependencies.
	 * @param files the package's {@code .wit} files, in the order their items should keep
	 * (a directory's are sorted by name)
	 * @param deps the dependencies, each one package's files
	 * @return the document and the positions of its items, each in its own file
	 * @throws WitParseException when a file does not parse, two files disagree about
	 * their package, a dependency declares none, or one package defines a name twice --
	 * the message naming the file (and line)
	 */
	public static WitParseResult assemble(List<WitSourceFile> files, List<List<WitSourceFile>> deps) {
		List<WitLocations> parts = new ArrayList<>();
		Map<String, Map<String, String>> declared = new HashMap<>();
		List<WitItem> items = new ArrayList<>();
		Package root = parsePackage(files, parts);
		if (root.header != null) {
			items.add(root.header);
		}
		for (Parsed file : root.files) {
			for (WitItem item : file.result.document().items()) {
				if (item instanceof WitItem.PackageHeader) {
					continue;
				}
				items.add(item);
				declare(declared, root.name(), item, file);
			}
		}
		for (List<WitSourceFile> dep : deps) {
			Package parsed = parsePackage(dep, parts);
			List<WitItem> own = new ArrayList<>();
			for (Parsed file : parsed.files) {
				for (WitItem item : file.result.document().items()) {
					switch (item) {
						case WitItem.PackageHeader ignored -> {
						}
						case WitItem.PackageBlock block -> {
							items.add(block);
							declare(declared, block.name().toString(), block, file);
						}
						default -> {
							if (parsed.header == null) {
								throw new WitParseException(
										file.source.name() + ":" + file.result.locations().lineOf(item)
												+ ": a dependency declares no package (`package namespace:name;`)");
							}
							own.add(item);
							declare(declared, parsed.name(), item, file);
						}
					}
				}
			}
			if (parsed.header != null) {
				items.add(new WitItem.PackageBlock(parsed.header.meta(), parsed.header.name(), List.copyOf(own)));
			}
		}
		return new WitParseResult(new WitDocument(List.copyOf(items)), WitLocations.combine(parts));
	}

	private record Parsed(WitSourceFile source, WitParseResult result) {
	}

	private record Package(WitItem.@Nullable PackageHeader header, List<Parsed> files) {

		String name() {
			return this.header == null ? "" : this.header.name().toString();
		}

	}

	// Parses one package's files, each on its own, and settles the package they share.
	private static Package parsePackage(List<WitSourceFile> files, List<WitLocations> parts) {
		List<Parsed> parsed = new ArrayList<>();
		WitItem.PackageHeader header = null;
		String declaring = "";
		for (WitSourceFile file : files) {
			WitParseResult result;
			try {
				result = WitParser.parseLocated(file.text());
			}
			catch (WitParseException ex) {
				throw new WitParseException(file.name() + ": " + ex.getMessage());
			}
			Parsed one = new Parsed(file, result);
			parsed.add(one);
			parts.add(result.locations().inFile(file.name()));
			for (WitItem item : result.document().items()) {
				if (!(item instanceof WitItem.PackageHeader found)) {
					continue;
				}
				if (header == null) {
					header = found;
					declaring = file.name();
				}
				else if (!header.name().equals(found.name())) {
					throw new WitParseException(
							file.name() + ":" + result.locations().lineOf(found) + ": package " + found.name()
									+ " is not the package " + header.name() + " that " + declaring + " declares");
				}
			}
		}
		return new Package(header, parsed);
	}

	// One package's interfaces and worlds share one name space across its files (and
	// across the blocks naming it); a block is checked item by item.
	private static void declare(Map<String, Map<String, String>> declared, String pkg, WitItem item, Parsed file) {
		if (item instanceof WitItem.PackageBlock block) {
			for (WitItem member : block.items()) {
				declare(declared, block.name().toString(), member, file);
			}
			return;
		}
		String name = switch (item) {
			case WitItem.InterfaceDef iface -> iface.name();
			case WitItem.World world -> world.name();
			default -> null;
		};
		if (name == null) {
			return;
		}
		String at = file.source.name() + ":" + file.result.locations().lineOf(item);
		String first = declared.computeIfAbsent(pkg, key -> new HashMap<>()).putIfAbsent(name, at);
		if (first != null) {
			throw new WitParseException(at + ": duplicate item named `" + name + "`"
					+ (pkg.isEmpty() ? "" : " in package " + pkg) + " (first at " + first + ")");
		}
	}

}

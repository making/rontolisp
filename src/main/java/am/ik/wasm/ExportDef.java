package am.ik.wasm;

import java.util.HashSet;
import java.util.Set;

/**
 * Definition for WASM export section entries.
 */
public class ExportDef extends CountingDef<ExportDef> {

	/** The names exported so far: a module exports a name once. */
	private final Set<String> names = new HashSet<>();

	/** Creates a new empty export definition. */
	public ExportDef() {
	}

	/**
	 * Add an export entry.
	 * @param exportName the export name
	 * @param externalKind the kind of export
	 * @param signatureIndex the index of the exported item
	 * @return this instance for chaining
	 * @throws IllegalArgumentException when the name is exported already, which makes the
	 * module invalid
	 */
	public ExportDef addExport(String exportName, ExternalKind externalKind, int signatureIndex) {
		if (!this.names.add(exportName)) {
			throw new IllegalArgumentException("the module exports the name '" + exportName + "' twice");
		}
		// The name length and the exported index are WASM u32 LEB128 fields; encode them
		// as
		// such so indices >= 128 (e.g. a function index past the first 127) are valid.
		return this.add(export -> export.writeUnsignedLeb128(exportName.length())
			.write(exportName, externalKind)
			.writeUnsignedLeb128(signatureIndex));
	}

}

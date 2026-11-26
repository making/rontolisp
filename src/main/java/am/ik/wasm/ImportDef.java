package am.ik.wasm;

/**
 * Definition for WASM import section entries.
 */
public class ImportDef extends CountingDef<ImportDef> {

	/** Creates a new empty import definition. */
	public ImportDef() {
	}

	/**
	 * Add an import entry.
	 * @param moduleName the module name
	 * @param fieldName the field name
	 * @param externalKind the kind of import
	 * @param signatureIndex the index of the imported item's type
	 * @return this instance for chaining
	 */
	public ImportDef addImport(String moduleName, String fieldName, ExternalKind externalKind, int signatureIndex) {
		// The two name lengths and the imported index are WASM u32 LEB128 fields --
		// same reasoning as ExportDef.addExport and FunctionDef.addFunction. Every
		// value stays below 128 in today's output, so the encoding is byte-identical
		// until one of them genuinely needs the second byte.
		return this.add(imprt -> imprt.writeUnsignedLeb128(moduleName.length())
			.write(moduleName)
			.writeUnsignedLeb128(fieldName.length())
			.write(fieldName)
			.write(externalKind)
			.writeUnsignedLeb128(signatureIndex));
	}

}

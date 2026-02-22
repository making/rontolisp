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
		return this.add(imprt -> imprt.write(moduleName.length(), moduleName, //
				fieldName.length(), fieldName, //
				externalKind, signatureIndex));
	}

}

package am.ik.wasm;

/**
 * Definition for WASM code section entries.
 */
public class CodeDef extends CountingDef<CodeDef> {

	/** Creates a new empty code definition. */
	public CodeDef() {
	}

	/**
	 * Add a function body to the code section.
	 * @param body the function body bytes
	 * @return this instance for chaining
	 */
	public CodeDef addFunction(byte[] body) {
		return this.add(function -> function.writeSignedLeb128(body.length).write(body));
	}

}

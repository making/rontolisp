package am.ik.wasm;

/**
 * Definition for WASM function section entries.
 */
public class FunctionDef extends CountingDef<FunctionDef> {

	/** Creates a new empty function definition. */
	public FunctionDef() {
	}

	/**
	 * Add a function entry referencing a type signature.
	 * @param signatureIndex the index of the function type signature
	 * @return this instance for chaining
	 */
	public FunctionDef addFunction(int signatureIndex) {
		return this.add(function -> function.write(signatureIndex));
	}

}

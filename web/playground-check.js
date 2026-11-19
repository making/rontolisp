/*
 * Checks the built playground image from node: `node web/playground-check.js [web/dist]`.
 *
 * The JVM test suite runs what the page's interpreter shows (PlaygroundReplTest), but
 * not the image: the Web Image build has one thread and one stack, and only there does
 * a Java program that starts threads take the compiled route (the module the wasm
 * backend makes of it, run by the engine the image itself runs on). This runs the
 * image's exports the way a page's worker does and compares what they answer. The
 * checks run in a child process under a time limit, so a program that never ends is a
 * failure and not a hung build. Needs a node whose WebAssembly has GC and exception
 * handling (22 or later).
 */
"use strict";
const fs = require("fs");
const path = require("path");
const vm = require("vm");
const { spawnSync } = require("child_process");

const dist = path.resolve(process.argv[2] || path.join(__dirname, "dist"));
const LIMIT_MILLIS = 300000;

const POOL = `void main() {
    Map<String, Integer> lengths = new ConcurrentHashMap<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
        for (String word : List.of("one", "three", "seven")) {
            pool.execute(() -> lengths.put(word, word.length()));
        }
    }
    CompletableFuture<Integer> total = CompletableFuture.supplyAsync(() -> lengths.get("three"))
        .thenApply(n -> n + lengths.size());
    IO.println(lengths + " " + total.join());
}
`;

// [what is checked, language, export, arguments, what it answers (a string or a test)]
const CHECKS = [
	["a Java program runs on the interpreter", "java", "rontoRunProgram",
		["void main() { IO.println(\"hello \" + (1 + 2)); }", ""], "hello 3\n"],
	["a Java program that starts threads runs compiled, its threads taking turns", "java", "rontoRunProgram",
		[POOL, ""], "{one=3, seven=5, three=5} 8\n"],
	["a thread that sleeps and one that waits for it", "java", "rontoRunProgram",
		[`void main() throws InterruptedException {
    Thread late = new Thread(() -> {
        try { Thread.sleep(20); } catch (InterruptedException e) { }
        IO.println("late");
    });
    late.start();
    IO.println("started");
    late.join();
    IO.println("joined " + IO.readln());
}
`, "in\n"], "started\nlate\njoined in\n"],
	["what a Java program leaves uncaught is shown, not sent to the console", "java", "rontoRunProgram",
		["void main() { throw new IllegalStateException(\"no\"); }", ""],
		(answer) => answer.includes("Exception in thread \"main\" java.lang.IllegalStateException: no")],
	["a buffer of the Java session starts no thread, and says so", "java", "rontoEval",
		["new Thread(() -> IO.println(\"task\")).start()"],
		(answer) => answer.includes("a thread is started only by a whole program in the browser")],
	["a wait nothing can end is refused in words, not waited for", "lisp", "rontoEval",
		["(let ((m (rontolisp:make-mutex)) (cv (rontolisp::%condition-variable-new)))"
			+ " (rontolisp:mutex-acquire m) (rontolisp::%condition-wait cv m nil))"],
		(answer) => answer.startsWith("ERROR:") && answer.includes("this wait would never end")],
	["the compile buttons run the command line's pipeline: a macro is expanded", "lisp", "rontoCompileWasm",
		["(defmacro twice (x) `(* 2 ,x)) (print (twice 21))"], (answer) => printed(answer).includes("42")],
];

// What a module the compile button answered prints: it writes through fd_write alone.
function printed(base64) {
	if (base64.startsWith("ERROR:")) return base64;
	let text = "";
	let memory = null;
	const wasi = new Proxy({
		fd_write(fd, iovs, count, written) {
			const view = new DataView(memory.buffer);
			let total = 0;
			for (let i = 0; i < count; i++) {
				const at = view.getUint32(iovs + 8 * i, true);
				const length = view.getUint32(iovs + 8 * i + 4, true);
				text += Buffer.from(memory.buffer, at, length).toString("utf8");
				total += length;
			}
			view.setUint32(written, total, true);
			return 0;
		},
	}, { get: (target, name) => target[name] || (() => 0) });
	try {
		const instance = new WebAssembly.Instance(new WebAssembly.Module(Buffer.from(base64, "base64")),
			{ wasi_snapshot_preview1: wasi });
		memory = instance.exports.memory;
		instance.exports._start();
	} catch (e) {
		text += "\n(the module did not run: " + e.message + ")";
	}
	return text;
}

if (process.argv[3] !== "--child") {
	const child = spawnSync(process.execPath, ["--stack-size=4000", __filename, dist, "--child"],
		{ stdio: "inherit", timeout: LIMIT_MILLIS });
	if (child.error) {
		console.error("FAILED: the checks did not end in " + LIMIT_MILLIS + " ms (" + child.error.message + ")");
		process.exit(1);
	}
	process.exit(child.status === null ? 1 : child.status);
}

const script = path.join(dist, "rontoplayground.js");
const wasm = path.join(dist, "rontoplayground.js.wasm");
globalThis.fetch = async () => new Response(fs.readFileSync(wasm), { headers: { "content-type": "application/wasm" } });
vm.runInThisContext(fs.readFileSync(script, "utf8"), { filename: script });
const config = new GraalVM.Config();
config.wasm_path = wasm;
GraalVM.run([], config).then(() => {
	let failed = 0;
	for (const [what, language, fn, args, expected] of CHECKS) {
		globalThis.rontoSetLanguage(language);
		const answer = String(globalThis[fn](...args));
		const ok = typeof expected === "function" ? expected(answer) : answer === expected;
		console.log((ok ? "ok      " : "FAILED  ") + what);
		if (!ok) {
			failed++;
			console.log("  answered: " + JSON.stringify(answer.slice(0, 600)));
			if (typeof expected === "string") console.log("  expected: " + JSON.stringify(expected));
		}
	}
	process.exit(failed === 0 ? 0 : 1);
}).catch((e) => {
	console.error("FAILED: the runtime did not start: " + e);
	process.exit(1);
});

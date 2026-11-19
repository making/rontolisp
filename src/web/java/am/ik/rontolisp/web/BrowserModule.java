package am.ik.rontolisp.web;

import java.util.Base64;

import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSString;

/**
 * Runs a compiled Preview 1 module in the engine the page itself runs on: how the
 * playground runs a Java program that starts threads, whose threads take turns on the
 * module's one stack ({@code .kb/java-frontend.md}, "In the browser"). The host half is
 * here, in the image's own JavaScript, so every host of the image has it -- the
 * playground's worker, the documentation site's, a deck's, node.
 *
 * <p>
 * The imports are {@code wasi_snapshot_preview1}'s, over what a page has: standard output
 * and error are collected in the order they were written, standard input is the text
 * handed over, there is no file, and a sleep ({@code poll_oneoff} of a clock) blocks the
 * calling thread -- by {@code Atomics.wait} where the page may, by watching the clock
 * otherwise. A module importing anything else is refused by the import's name.
 */
final class BrowserModule {

	/** What ended a run: the status of an exit, or the text of a trap. */
	record Run(String output, int status, String trap) {
	}

	private BrowserModule() {
	}

	/**
	 * Instantiates the module and runs its {@code _start}.
	 * @param module the module's bytes
	 * @param stdin the whole standard input
	 * @return what it wrote and how it ended
	 * @throws IllegalStateException when the page cannot run the module: the text says
	 * why
	 */
	static Run run(byte[] module, String stdin) {
		String answer = run(JSString.of(Base64.getEncoder().encodeToString(module)), JSString.of(stdin)).asString();
		int end = answer.indexOf('\n');
		String head = answer.substring(0, end);
		String output = answer.substring(end + 1);
		if (head.startsWith("refused ")) {
			throw new IllegalStateException(head.substring("refused ".length()));
		}
		if (head.startsWith("trap ")) {
			return new Run(output, 1, head.substring("trap ".length()));
		}
		return new Run(output, Integer.parseInt(head.substring("exit ".length())), "");
	}

	// Answers "exit N", "trap TEXT" or "refused TEXT", a newline, then the output.
	@JS(args = { "module", "stdin" },
			value = """
					var chunks = [];
					var size = 0;
					function output() {
					  var all = new Uint8Array(size);
					  var at = 0;
					  for (var i = 0; i < chunks.length; i++) { all.set(chunks[i], at); at += chunks[i].length; }
					  return new TextDecoder().decode(all);
					}
					try {
					  var text = atob(module);
					  var bytes = new Uint8Array(text.length);
					  for (var i = 0; i < text.length; i++) { bytes[i] = text.charCodeAt(i); }
					  var input = new TextEncoder().encode(stdin);
					  var read = 0;
					  var memory = null;
					  var view = function () { return new DataView(memory.buffer); };
					  var exit = { status: 0 };
					  var sleep = function (millis) {
					    if (millis <= 0) { return; }
					    try {
					      Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, millis);
					    } catch (e) {
					      var until = performance.now() + millis;
					      while (performance.now() < until) { }
					    }
					  };
					  var sizes = function (count, bytes) {
					    view().setUint32(count, 0, true);
					    view().setUint32(bytes, 0, true);
					    return 0;
					  };
					  var wasi = {
					    fd_write: function (fd, iovs, count, written) {
					      var total = 0;
					      for (var i = 0; i < count; i++) {
					        var at = view().getUint32(iovs + 8 * i, true);
					        var length = view().getUint32(iovs + 8 * i + 4, true);
					        if (fd === 1 || fd === 2) {
					          chunks.push(new Uint8Array(memory.buffer, at, length).slice());
					          size += length;
					        }
					        total += length;
					      }
					      view().setUint32(written, total, true);
					      return fd === 1 || fd === 2 ? 0 : 8;
					    },
					    fd_read: function (fd, iovs, count, got) {
					      if (fd !== 0) { return 8; }
					      var total = 0;
					      for (var i = 0; i < count; i++) {
					        var at = view().getUint32(iovs + 8 * i, true);
					        var length = Math.min(view().getUint32(iovs + 8 * i + 4, true), input.length - read);
					        new Uint8Array(memory.buffer, at, length).set(input.subarray(read, read + length));
					        read += length;
					        total += length;
					      }
					      view().setUint32(got, total, true);
					      return 0;
					    },
					    clock_time_get: function (id, precision, result) {
					      var nanos = id === 0 ? BigInt(Date.now()) * 1000000n
					        : BigInt(Math.round(performance.now() * 1000)) * 1000n;
					      view().setBigUint64(result, nanos, true);
					      return 0;
					    },
					    poll_oneoff: function (subscriptions, events, count, got) {
					      // One clock subscription is all a compiled module asks: its sleep.
					      var nanos = view().getBigUint64(subscriptions + 24, true);
					      var absolute = (view().getUint16(subscriptions + 40, true) & 1) !== 0;
					      var millis = Number(nanos) / 1000000;
					      sleep(absolute ? millis - performance.now() : millis);
					      view().setBigUint64(events, view().getBigUint64(subscriptions, true), true);
					      view().setUint16(events + 8, 0, true);
					      view().setUint8(events + 10, 0);
					      view().setUint32(got, 1, true);
					      return 0;
					    },
					    random_get: function (at, length) {
					      crypto.getRandomValues(new Uint8Array(memory.buffer, at, length));
					      return 0;
					    },
					    environ_sizes_get: sizes,
					    environ_get: function () { return 0; },
					    args_sizes_get: sizes,
					    args_get: function () { return 0; },
					    path_open: function () { return 44; },
					    fd_close: function () { return 0; },
					    fd_readdir: function () { return 52; },
					    proc_exit: function (status) { exit.status = status; throw exit; }
					  };
					  var compiled = new WebAssembly.Module(bytes);
					  var needed = WebAssembly.Module.imports(compiled);
					  for (var i = 0; i < needed.length; i++) {
					    if (needed[i].module !== 'wasi_snapshot_preview1' || !(needed[i].name in wasi)) {
					      return 'refused the program needs ' + needed[i].module + ' ' + needed[i].name
					        + ', which a page does not have\\n';
					    }
					  }
					  var instance = new WebAssembly.Instance(compiled, { wasi_snapshot_preview1: wasi });
					  memory = instance.exports.memory;
					  try {
					    instance.exports._start();
					  } catch (e) {
					    if (e !== exit) { return 'trap ' + String(e && e.message ? e.message : e).split('\\n')[0] + '\\n' + output(); }
					  }
					  return 'exit ' + exit.status + '\\n' + output();
					} catch (e) {
					  return 'refused ' + String(e && e.message ? e.message : e).split('\\n')[0] + '\\n';
					}
					""")
	private static native JSString run(JSString module, JSString stdin);

}

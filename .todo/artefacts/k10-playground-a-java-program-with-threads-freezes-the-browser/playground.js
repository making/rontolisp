// Scenario: the playground page: pick Java, put a program in the source pane, Run source, read the REPL log.
const fs = require("fs");
module.exports = async (page) => {
  await page.goto("/playground.html");
  await page.waitFor(`!document.getElementById("btn-run-source").disabled`, 60000);
  const log = [];
  const runSource = async (lang, src, limit) => {
    await page.eval(`(() => { const sel = document.querySelector("select#language") || [...document.querySelectorAll("select")].find((s) => [...s.options].some((o) => o.value === "java")); sel.value = ${JSON.stringify(lang)}; sel.dispatchEvent(new Event("change")); return 1; })()`);
    await new Promise((r) => setTimeout(r, 500));
    const before = await page.eval(`document.querySelectorAll("#repl-log > .entry").length`);
    const t = Date.now();
    await page.eval(`(() => { document.getElementById("source").value = ${JSON.stringify(src)}; document.getElementById("btn-run-source").click(); return 1; })()`);
    await page.waitFor(`document.querySelectorAll("#repl-log > .entry").length > ${before} && !document.getElementById("repl-input").disabled`, limit);
    return (Date.now() - t) + " ms: " + JSON.stringify(await page.eval(`(document.querySelector("#repl-log > .entry:last-child .out") || {textContent: "(no output)"}).textContent`)).slice(0, 500);
  };
  log.push("java pool: " + await runSource("java", fs.readFileSync(__dirname + "/Pool.java", "utf8"), 60000));
  log.push("java hello: " + await runSource("java", fs.readFileSync(__dirname + "/Hello.java", "utf8"), 60000));
  log.push("lisp: " + await runSource("lisp", "(defun sq (x) (* x x)) (sq 7)", 60000));
  log.push("isolated: " + await page.eval("self.crossOriginIsolated"));
  return log;
};

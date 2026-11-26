// The Worker. The module has no environment, so the address of its database is the
// request's: the field the program reads, set here from the Hyperdrive binding. It is
// set on the Request this Worker hands its own module, and goes nowhere else.

import module from "./worker.wasm";
import { worker } from "./worker.js";

const served = worker(module);

export default {
  fetch(request, env, ctx) {
    const asked = new Request(request);
    asked.headers.set("x-database-url", env.HYPERDRIVE.connectionString);
    return served.fetch(asked, env, ctx);
  },
};

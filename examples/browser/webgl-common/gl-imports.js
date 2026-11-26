// GENERATED from gl.wit -- do not edit.
//
// The host side of the WebGL2 boundary the examples/browser/webgl-* demos are
// written against: one entry per function of gl.wit, which is the same file
// gl.lisp binds with rontolisp:wit-import. Both halves of every name, and the
// handle/string plumbing around it, are derived from that one declaration, so
// the page can no longer provide a field the module does not import (or spell
// one differently) without the WIT saying so.
//
// Each factory below takes the page's own host plumbing and returns a plain
// object of plain functions, so a page spreads it into its import object and
// adds its own demo-specific staging entries beside it:
//
//   gl: { ...glImports({ gl, handles, addHandle, str, retStr }),
//         setVertex: (i, x, y) => { ... } },
//
// A later property wins, so a page that needs a different implementation of a
// generated entry can simply restate it after the spread.
//
// Regenerate with:
//   ./mvnw -Drontolisp.gl.fix=true -Dtest=GlImportObjectTest#fixGlImports test

/** The `gl` import module, one entry per gl.wit function. */
export function glImports({ gl, addHandle, handles, str, retStr }) {
  return {
    createShader: (kind) => addHandle(gl.createShader(kind)),
    shaderSource: (shader, source, sourceLen) =>
      gl.shaderSource(handles[shader], str(source, sourceLen)),
    compileShader: (shader) => gl.compileShader(handles[shader]),
    getShaderParameter: (shader, pname) => gl.getShaderParameter(handles[shader], pname),
    getShaderInfoLog: (shader) => retStr(gl.getShaderInfoLog(handles[shader]) ?? ""),
    createProgram: () => addHandle(gl.createProgram()),
    attachShader: (program, shader) => gl.attachShader(handles[program], handles[shader]),
    linkProgram: (program) => gl.linkProgram(handles[program]),
    getProgramParameter: (program, pname) => gl.getProgramParameter(handles[program], pname),
    getProgramInfoLog: (program) => retStr(gl.getProgramInfoLog(handles[program]) ?? ""),
    useProgram: (program) => gl.useProgram(handles[program]),
    getUniformLocation: (program, name, nameLen) =>
      addHandle(gl.getUniformLocation(handles[program], str(name, nameLen))),
    uniform1f: (location, x) => gl.uniform1f(handles[location], x),
    uniform3f: (location, x, y, z) => gl.uniform3f(handles[location], x, y, z),
    enable: (cap) => gl.enable(cap),
    disable: (cap) => gl.disable(cap),
    depthMask: (flag) => gl.depthMask(!!flag),
    blendFunc: (src, dst) => gl.blendFunc(src, dst),
    createBuffer: () => addHandle(gl.createBuffer()),
    bindBuffer: (target, buffer) => gl.bindBuffer(target, handles[buffer]),
    bufferData: (target, size, usage) => gl.bufferData(target, size, usage),
    createVertexArray: () => addHandle(gl.createVertexArray()),
    bindVertexArray: (array) => gl.bindVertexArray(handles[array]),
    enableVertexAttribArray: (index) => gl.enableVertexAttribArray(index),
    vertexAttribPointer: (index, size, kind, normalized, stride, offset) =>
      gl.vertexAttribPointer(index, size, kind, !!normalized, stride, offset),
    viewport: (x, y, width, height) => gl.viewport(x, y, width, height),
    clearColor: (red, green, blue, alpha) => gl.clearColor(red, green, blue, alpha),
    clear: (mask) => gl.clear(mask),
    drawArrays: (mode, first, count) => gl.drawArrays(mode, first, count),
  };
}

/** The `ui` import module, one entry per gl.wit function. */
export function uiImports({ ui, str }) {
  return {
    fail: (message, messageLen) => ui.fail(str(message, messageLen)),
  };
}

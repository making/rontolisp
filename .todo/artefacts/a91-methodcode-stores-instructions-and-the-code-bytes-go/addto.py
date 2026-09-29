"""addto.py [--list] FILE...: `code.addTo(definition, access, name, desc);` (a MethodCode) becomes
`definition.addMethod(access, name, desc, code);` -- the class builder takes the body, so the
layer names no class definition. A compile context's own addTo (the receivers in CTX) stays."""
import re
import sys

CTX = {"mainCtx", "topRunnerCtxFinal", "funcCtxs.get(i)", "lambdaCtxs.get(i)", "topChunks.get(i)", "ctx",
       "outlined.ctx()", "this.ctx", "fusedCtxs.get(i)"}

STMT = re.compile(r"(?P<indent>^[ \t]*)(?P<recv>[^;{}]*?)\s*\.addTo\((?P<def>(?:this\.)?definition),", re.M | re.S)


def call_end(text, open_idx):
    depth = 0
    i = open_idx
    in_str = False
    while i < len(text):
        ch = text[i]
        if in_str:
            if ch == "\\":
                i += 2
                continue
            if ch == '"':
                in_str = False
        elif ch == '"':
            in_str = True
        elif ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    raise ValueError("unbalanced")


def args_of(s):
    out, depth, cur = [], 0, []
    i = 0
    while i < len(s):
        ch = s[i]
        if ch == '"':
            j = i + 1
            while s[j] != '"':
                j += 2 if s[j] == "\\" else 1
            cur.append(s[i:j + 1])
            i = j + 1
            continue
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        if ch == "," and depth == 0:
            out.append("".join(cur).strip())
            cur = []
        else:
            cur.append(ch)
        i += 1
    out.append("".join(cur).strip())
    return out


listing = sys.argv[1] == "--list"
files = sys.argv[2:] if listing else sys.argv[1:]
for path in files:
    text = open(path, encoding="utf-8").read()
    out = []
    pos = 0
    for m in STMT.finditer(text):
        if m.start() < pos:
            continue
        raw = m.group("recv")
        start = m.start("recv")
        # leading comment lines belong to the statement before, not the receiver
        while raw.lstrip().startswith("//"):
            nl = raw.index("\n")
            start += nl + 1
            raw = raw[nl + 1:]
        indent = re.match(r"[ \t]*", raw).group(0)
        start += len(indent)
        raw = raw[len(indent):]
        recv = " ".join(raw.split())
        open_idx = m.end() - len(m.group("def")) - 2
        assert text[open_idx] == "(", text[open_idx - 10:open_idx + 10]
        end = call_end(text, open_idx)
        if listing:
            print("%s: %s" % (path.rsplit("/", 1)[-1], recv))
            continue
        if recv in CTX:
            continue
        args = args_of(text[open_idx + 1:end - 1])
        assert len(args) == 4, args
        out.append(text[pos:start])
        out.append("%s.addMethod(%s, %s, %s, %s)" % (args[0], args[1], args[2], args[3], recv))
        pos = end
    out.append(text[pos:])
    if not listing:
        open(path, "w", encoding="utf-8").write("".join(out))

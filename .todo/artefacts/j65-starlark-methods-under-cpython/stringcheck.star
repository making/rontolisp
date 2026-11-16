def chk():
    line = "the quick brown fox jumps over the lazy dog"
    u = "héllo wörld — ünïcode ö ö"
    out = []
    out.append(line.count("o")); out.append(line.count("the")); out.append(line.count("o", 13)); out.append(line.count("o", 13, 27)); out.append(line.count("")); out.append(line.count("zz"))
    out.append(u.count("ö")); out.append(u.count("ö", 8)); out.append(u.count("ö", 8, 23)); out.append("aaaa".count("aa")); out.append("aaaaa".count("aa", 1))
    out.append(line.find("fox")); out.append(line.find("fox", 17)); out.append(line.find("o", 13, 27)); out.append(u.find("ö")); out.append(u.find("ö", 8)); out.append(u.find("—")); out.append(u.rfind("ö")); out.append(line.find("")); out.append(line.find("dog", 0, 42)); out.append(line.find("dog", 0, 43))
    out.append(line.startswith("the")); out.append(line.startswith(("a", "the"))); out.append(line.endswith("dog")); out.append(line.endswith(("cat", "og"), 0, 43)); out.append(u.startswith("hé")); out.append(u.endswith("ö ö")); out.append(line.startswith("")); out.append(line.startswith("quick", 4)); out.append(line.endswith("lazy", 0, 39))
    out.append("  \t pad \n".strip() + "|"); out.append("  pad  ".lstrip() + "|"); out.append("  pad  ".rstrip() + "|"); out.append("xxpadxyx".strip("xy") + "|"); out.append("éépadéé".strip("é") + "|"); out.append("".strip() + "|"); out.append("   ".strip() + "|"); out.append("abc".strip("") + "|")
    out.append("Hello World 123".upper()); out.append("Hello World 123".lower()); out.append("Héllo Wörld ß".upper()); out.append("HÉLLO WÖRLD".lower()); out.append("".upper() + "|"); out.append(len("abc".upper())); out.append("abc".upper()[1]); out.append("a,b".upper().split(","))
    d = {}
    d["a"] = 1; d[2] = "two"; d[(1, 2)] = 3; d[2.0] = "TWO"
    out.append(d.get("a")); out.append(d.get(2)); out.append(d.get((1, 2))); out.append(d.get("zz", "dflt")); out.append(d.get(2.0)); out.append(len(d))
    return out
def main():
  for x in chk():
    print(x)
main()

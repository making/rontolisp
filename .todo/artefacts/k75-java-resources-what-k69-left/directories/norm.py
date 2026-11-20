import re,sys
def f(m):
    items=[x for x in m.group(1).split('|') if x]
    return '['+'|'.join(sorted(items))+('|' if items else '')+']'
for line in open(sys.argv[1]):
    print(re.sub(r'\[([^\]]*)\]', f, line.rstrip('\n')))

import sys, re
txt = open(sys.argv[1]).read()
n = sys.argv[2]
m = re.search(r'^ \(func \$%s\b.*?(?=^ \(func \$|^ \(export|^ \(data|^\)$)' % re.escape(n), txt, re.M | re.S)
print(m.group(0) if m else 'not found')

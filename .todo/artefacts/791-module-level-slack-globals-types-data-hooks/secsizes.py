import sys
def leb_u(b,p):
    r=0;s=0
    while True:
        x=b[p];p+=1;r|=(x&0x7f)<<s;s+=7
        if not(x&0x80):return r,p
names={0:'custom',1:'type',2:'import',3:'function',4:'table',5:'memory',6:'global',7:'export',8:'start',9:'element',10:'code',11:'data',12:'datacount',13:'tag'}
for f in sys.argv[1:]:
    b=open(f,'rb').read();p=8;out=[]
    while p<len(b):
        sid=b[p];p+=1;n,p=leb_u(b,p);out.append(f"{names.get(sid,sid)}={n}");p+=n
    print(f, len(b), ' '.join(out))

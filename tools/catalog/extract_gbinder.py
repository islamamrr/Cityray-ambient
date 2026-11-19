import os,re,sys,json
src=sys.argv[1]
# 1. constants from stubs
consts={}
for dp,_,fs in os.walk(os.path.join(src,'com/ecarx')):
    for f in fs:
        if not f.endswith('.java'): continue
        txt=open(os.path.join(dp,f),errors='ignore').read()
        # track nested interface names roughly: use outer class name
        outer=f[:-5]
        for m in re.finditer(r'public static final (?:int|float|long) (\w+) = (-?[\w.]+?)[fL]?;',txt):
            try: consts[(outer,m.group(1))]=int(m.group(2),0)
            except ValueError:
                try: consts[(outer,m.group(1))]=float(m.group(2))
                except ValueError: pass
def resolve(expr):
    expr=expr.strip()
    m=re.fullmatch(r'Integer\.valueOf\((.*)\)',expr)
    if m: expr=m.group(1).strip()
    m=re.fullmatch(r'Float\.valueOf\((.*)\)',expr)
    if m: expr=m.group(1).strip()
    if re.fullmatch(r'-?[\d.xXa-fA-F]+[fFL]?',expr) and not expr.lower().startswith('0x'): expr=expr.rstrip('fFL')
    try: return int(expr,0)
    except ValueError: pass
    try: return float(expr)
    except ValueError: pass
    parts=expr.split('.')
    if len(parts)>=2 and (parts[-2],parts[-1]) in consts: return consts[(parts[-2],parts[-1])]
    if len(parts)>=2 and (parts[0],parts[-1]) in consts: return consts[(parts[0],parts[-1])]
    return expr
def split_args(s):
    out=[];depth=0;cur='';i=0;instr=False
    while i<len(s):
        c=s[i]
        if instr:
            cur+=c
            if c=='\\': cur+=s[i+1]; i+=1
            elif c=='"': instr=False
        elif c=='"': instr=True; cur+=c
        elif c in '([{': depth+=1; cur+=c
        elif c in ')]}': depth-=1; cur+=c
        elif c==',' and depth==0: out.append(cur.strip()); cur=''
        else: cur+=c
        i+=1
    if cur.strip(): out.append(cur.strip())
    return out
def extract_call(txt,start):
    i=start;depth=0;instr=False
    while True:
        c=txt[i]
        if instr:
            if c=='\\': i+=1
            elif c=='"': instr=False
        elif c=='"': instr=True
        elif c=='(': depth+=1
        elif c==')':
            depth-=1
            if depth==0: return txt[start:i+1]
        i+=1
def unq(s):
    if s=='null': return None
    return json.loads(s) if s.startswith('"') else s
def vint(a,pos):
    try: return int(a)
    except ValueError:
        ms=list(re.finditer(r'int '+re.escape(a)+r' = (\d+);',txt[:pos]))
        return int(ms[-1].group(1))
out=[]
txt=open(os.path.join(src,'e7/a.java'),errors='ignore').read()
for m in re.finditer(r'new C2750a\(',txt):
    call=extract_call(txt,m.end()-1)
    args=split_args(call[1:-1])
    alias,typ,key,idx=unq(args[0]),vint(args[1],m.start()),unq(args[2]),resolve(args[3])
    desc=unq(args[4]) if len(args)==6 else None
    mp=args[-1]
    vals={}
    for pm in re.finditer(r'\.V\("([^"]+)",\s*',mp):
        inner=extract_call(mp,pm.start()+2)
        a=split_args(inner[1:-1])
        vals[unq(a[0])]=resolve(a[1])
    out.append(dict(alias=alias,type={1:'info',2:'function',3:'sensor'}.get(typ,typ),key=key,id=idx,desc_ru=desc,values=vals))
json.dump(out,open(sys.argv[2],'w'),ensure_ascii=False,indent=1)
print(len(out), sum(1 for o in out if not isinstance(o['id'],int)), sum(1 for o in out for v in o['values'].values() if not isinstance(v,(int,float))))

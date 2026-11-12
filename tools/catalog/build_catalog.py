import os,re,sys,json,collections
src=sys.argv[1]; cat=json.load(open(sys.argv[2])); out=sys.argv[3]
A=os.path.join(src,'com/ecarx/xui/adaptapi')
def consts(path):
    d={}
    for m in re.finditer(r'public static final int (\w+) = (-?\d+);',open(path).read()): d[m.group(1)]=int(m.group(2))
    return d
S=consts(f'{A}/car/sensor/ISensor.java'); G=consts(f'{A}/car/sensor/ISensorGroup.java')
I=consts(f'{A}/car/base/ICarInfo.java'); V=consts(f'{A}/car/vehicle/IVehicle.java'); K=consts(f'{A}/input/KeyCode.java')
E={}  # (kind,id) -> entry
def add(kind,id,name,iface,values=None,src='adaptapi'):
    k=(kind,id)
    if k in E:
        e=E[k]
        if name!=e['name'] and name not in e.get('aka',[]): e.setdefault('aka',[]).append(name)
        if values: e.setdefault('values',{}).update({a:b for a,b in values.items() if a not in e.get('values',{})})
        return
    e={'kind':kind,'id':id,'name':name,'iface':iface}
    if values: e['values']=values
    E[k]=e
for n,v in S.items():
    if n.startswith('SENSOR_TYPE_'): add('sensor',v,n,'ISensor')
for n,v in G.items(): add('group',v,n,'ISensorGroup')
for n,v in I.items():
    if re.match(r'(INT|FLT|INTS|STRING|MAP)_INFO_',n) and v>0xff: add('info',v,n,'ICarInfo')
    elif n.startswith('CONFIG_INFO_') and not n.startswith('CONFIG_INFO_VALUE_'): add('config',v,n,'ICarInfo')
gb=collections.Counter()
for o in cat:
    iface,name=o['alias'].split('.',1)
    vals={k:v for k,v in o['values'].items() if isinstance(v,int)}
    if name=='SENSOR_TYPE_GEAR': vals={}  # gbinder lists charging-gear levels here; wrong for this sensor
    kind={'sensor':'sensor','function':'function','info':'info'}[o['type']]
    add(kind,o['id'],name,iface,vals,'gbinder'); gb[kind]+=1
for n,v in V.items():
    if '_FUNC_' in n and v>=0x10000000 and v&0xff==0 and ('function',v) not in E: add('function',v,n,'IVehicle')
keys=[{'code':v,'name':n} for n,v in sorted(K.items(),key=lambda x:x[1]) if n.startswith('KEYCODE_')]
ents=sorted(E.values(),key=lambda e:(e['kind'],e['id']))
for e in ents:
    if 'values' in e: e['values']=dict(sorted(e['values'].items(),key=lambda x:x[1]))
modules={hex(v):n[len('CAR_MODULE_'):] for n,v in consts(f'{A}/car/base/ICarFunction.java').items() if n.startswith('CAR_MODULE_')}
doc={'source':'ECARX AdaptAPI 1.1.18.12 interface constants (names and ids only)','modules':modules,'entries':ents,'keys':keys}
json.dump(doc,open(out,'w'),separators=(',',':'),ensure_ascii=True)
print(collections.Counter(e['kind'] for e in ents), len(keys), os.path.getsize(out))

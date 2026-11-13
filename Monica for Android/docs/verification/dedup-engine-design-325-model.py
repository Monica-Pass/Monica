"""Synthetic design model only. Not production Kotlin or provider validation."""
from collections import Counter
from dataclasses import dataclass, replace
from itertools import combinations, permutations, product
from pathlib import Path
import json

@dataclass(frozen=True)
class Project:
    id: str
    atoms: tuple
    context: tuple = ('local-vault', 'same-account-identity', 'same-title', 'same-folder')
    complete: bool = True
    unknown: bool = False
    references: bool = False
    fresh: bool = True
    protected_policy: str = 'normal'
    order: tuple = ()

def subsequence(needle, haystack):
    it=iter(haystack)
    return all(any(a == b for b in it) for a in needle)

def covers(keeper, candidate):
    # Atoms have already been decoded with typed roles and bindings. This model
    # deliberately does not implement those decoders or graph reconstruction.
    if not candidate.atoms or not keeper.atoms:
        return False
    if not all(p.complete and p.fresh and not p.unknown and not p.references for p in (keeper,candidate)):
        return False
    if keeper.context != candidate.context or keeper.protected_policy != candidate.protected_policy:
        return False
    return Counter(candidate.atoms) <= Counter(keeper.atoms) and subsequence(candidate.order,keeper.order)

def plan(projects):
    # Equivalence keeps a stable representative. Strict containment then points
    # straight to a survivor, never to another candidate in the removal set.
    selected=[]
    for p in sorted(projects,key=lambda p:p.id):
        if not any(covers(k,p) and covers(p,k) for k in selected): selected.append(p)
    survivors=[p for p in selected if not any(covers(k,p) and not covers(p,k) for k in selected)]
    certificates={}
    for p in projects:
        if p in survivors: continue
        choices=[k for k in survivors if covers(k,p)]
        if choices:certificates[p.id]=min(choices,key=lambda k:k.id).id
        else:survivors.append(p)
    return tuple(sorted(p.id for p in survivors)),certificates

cases=[]
def check(name, condition):
    assert condition,name
    cases.append(name)

A=Project('A',('1','2','3')); B=Project('B',('1','2')); C=Project('C',('2','3','4'))
check('ABC preserves A/C and maps B directly to A',plan([A,B,C]) == (('A','C'),{'B':'A'}))
check('ABC input-order independent',all(plan(ps)==plan([A,B,C]) for ps in permutations([A,B,C])))
check('partial overlap alone never removes',plan([A,C])[1]=={})
check('union of separate survivors is not coverage',plan([Project('X',('1',)),Project('Y',('2',)),B])[1].get('B') is None)
check('equal copies keep exactly one stable survivor',plan([A,replace(A,id='D'),replace(A,id='E')]) == (('A',),{'D':'A','E':'A'}))
check('multiplicity matters',not covers(Project('A',('x',)),Project('B',('x','x'))))
check('field names belong to content',not covers(Project('A',(('field','billing','42'),)),Project('B',(('field','shipping','42'),))))
check('username and password bindings cannot be swapped',not covers(Project('A',(('credential','u1','p1'),('credential','u2','p2'))),Project('B',(('credential','u1','p2'),))))
check('empty project does not qualify by vacuous subset',not covers(A,Project('E',())))
for attr,value in [('complete',False),('unknown',True),('references',True),('fresh',False)]:
    check(attr+' candidate blocks',not covers(A,replace(B,**{attr:value})))
    check(attr+' keeper blocks',not covers(replace(A,**{attr:value}),B))
check('different folders require review',not covers(A,replace(B,context=B.context[:-1]+('other-folder',))))
check('different visible title requires review',not covers(A,replace(B,context=('local-vault','same-account-identity','other-title','same-folder'))))
check('cross vault cannot enter single-vault removal',not covers(A,replace(B,context=('other-vault',)+B.context[1:])))
check('security policy difference blocks',not covers(A,replace(B,protected_policy='reprompt')))
for a,b,name in [('p','p ','password whitespace'),('X','x','case sensitive values'),(False,'false','boolean type'),('uri|exact','uri|domain','URI matching behavior'),('name|hash1','name|hash2','attachment bytes'),('history1','history2','history variant'),('otp:SHA1:30','otp:SHA1:60','OTP parameters')]:
    check(name,not covers(Project('A',(('value',a),)),Project('B',(('value',b),))))
check('ordered content rejects reordering',not covers(replace(A,order=('1','2','3')),replace(B,order=('2','1'))))
check('ordered content permits an order-preserving subset',covers(replace(A,order=('1','2','3')),replace(B,order=('1','2'))))

# Exhaustive small-set coverage: 15 nonempty subsets, every ordered triple.
sets=[s for n in range(1,5) for s in combinations('1234',n)]
exhaustive=0
for triple in product(sets,repeat=3):
    ps=[Project(str(i),a) for i,a in enumerate(triple)]
    kept,removed=plan(ps)
    byid={p.id:p for p in ps}
    assert set(kept)|set(removed)==set(byid)
    assert not set(kept)&set(removed)
    assert all(k in kept and covers(byid[k],byid[c]) for c,k in removed.items())
    before=Counter(a for p in ps for a in set(p.atoms))
    after=Counter(a for k in kept for a in set(byid[k].atoms))
    assert set(before)==set(after)
    assert plan(list(reversed(ps)))==(kept,removed)
    exhaustive+=1

result=dict(scope='Synthetic design model, not production tests',namedCases=len(cases),cases=cases,exhaustiveTriples=exhaustive,passed=True)
Path(__file__).with_name('dedup-engine-design-325-model-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({k:v for k,v in result.items() if k!='cases'}))

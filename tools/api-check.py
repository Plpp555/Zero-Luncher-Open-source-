#!/usr/bin/env python3
"""Lists framework methods/fields referenced by classes.dex that do not exist at minSdk.

A poor man's "NewApi" lint for builds without Android Lint:

    dexdump -d classes.dex > dex.txt
    tools/api-check.py dex.txt android-all-5.0.2_r3-robolectric-r0.jar

Every reported reference must sit behind a Build.VERSION.SDK_INT check; the "in" column shows
the calling method so that can be reviewed. Constants (static final primitives/strings) are
inlined by javac and never appear here.
"""
import re
import subprocess
import sys

REF = re.compile(r"(invoke-\w+(?:/range)?|[si]get[-\w]*|[si]put[-\w]*|new-instance|const-class|check-cast|instance-of)"
                 r" [^,]*(?:, [^,]*)*, (L(?:android)/[\w/$]+;)(?:\.([\w<>$]+):(\S+))?")
CLS = re.compile(r"Class descriptor\s+:\s+'(L[^']+;)'")
NAME = re.compile(r"^\s+name\s+:\s+'([^']+)'")


def javap(jar, classes):
    """Returns {class: (super, [interfaces], set(member descriptors))}."""
    out = {}
    if not classes:
        return out
    p = subprocess.run(["javap", "-cp", jar, "-p", "-s"] + sorted(classes),
                       capture_output=True, text=True)
    cur = None
    pending = None
    for line in (p.stdout + "\n" + p.stderr).splitlines():
        m = re.match(r"^(?:[\w ]*?)(?:class|interface) ([\w.$]+)(?:<[^{]*?>)?"
                     r"(?: extends ([\w.$, <>?]+?))?(?: implements ([\w.$, <>?]+?))? \{$", line)
        if m:
            cur = m.group(1)
            sup = (m.group(2) or "").split("<")[0].strip()
            ifs = [x.split("<")[0].strip() for x in (m.group(3) or "").split(",") if x.strip()]
            if "interface" in line.split(cur)[0]:
                ifs += [x.split("<")[0].strip() for x in (m.group(2) or "").split(",") if x.strip()]
                sup = ""
            out[cur] = (sup, ifs, set())
            continue
        if cur is None:
            continue
        s = line.strip()
        if s.startswith("descriptor:") and pending is not None:
            out[cur][2].add(pending + ":" + s.split(":", 1)[1].strip())
            pending = None
        elif s.endswith(";") and "(" in s:
            name = s.split("(")[0].split()[-1]
            pending = "<init>" if name == cur else name
        elif s.endswith(";"):
            pending = s[:-1].split()[-1]
    return out


def main():
    dex, jar = sys.argv[1], sys.argv[2]
    refs = {}
    cls = None
    meth = None
    for line in open(dex, encoding="utf-8", errors="replace"):
        m = CLS.search(line)
        if m:
            cls = m.group(1)
            continue
        m = NAME.match(line)
        if m:
            meth = m.group(1)
            continue
        m = REF.search(line)
        if m:
            owner, name, sig = m.group(2), m.group(3), m.group(4)
            key = (owner, name, sig)
            refs.setdefault(key, set()).add(f"{cls[1:-1].split('/')[-1]}.{meth}")

    known = {}
    todo = {k[0][1:-1].replace("/", ".") for k in refs}
    while todo:
        info = javap(jar, todo)
        known.update(info)
        nxt = set()
        for name in todo:
            if name not in info:
                known[name] = None
                continue
            sup, ifs, _ = info[name]
            for s in [sup] + ifs:
                if s and s not in known and s.startswith("android."):
                    nxt.add(s)
        todo = nxt

    def has(c, member, seen=None):
        if c is None or c not in known or known[c] is None:
            return False
        sup, ifs, members = known[c]
        if member in members:
            return True
        return any(has(s, member) for s in [sup] + ifs if s and s.startswith("android."))

    missing = 0
    for (owner, name, sig), callers in sorted(refs.items(), key=lambda kv: (kv[0][0], kv[0][1] or "", kv[0][2] or "")):
        c = owner[1:-1].replace("/", ".")
        if known.get(c) is None:
            print(f"MISSING CLASS  {c}  in {', '.join(sorted(callers))}")
            missing += 1
            continue
        if name is None:
            continue
        desc = name + ":" + sig
        if has(c, desc):
            continue
        # Members inherited from java.* (e.g. Object.equals) are not modelled; skip those.
        if name in ("equals", "hashCode", "toString", "getClass", "clone"):
            continue
        print(f"MISSING MEMBER {c}.{desc}  in {', '.join(sorted(callers))}")
        missing += 1
    print(f"{len(refs)} android.* references checked, {missing} not present at the given API level")


if __name__ == "__main__":
    main()

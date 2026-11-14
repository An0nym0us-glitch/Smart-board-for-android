"""Reads a uiautomator dump. Usage:
  ui_bounds.py dump.xml center "Content description"   -> prints "x y" of that view's centre
  ui_bounds.py dump.xml fits                           -> checks every ClassBoard button lies on screen
"""
import re
import sys
import xml.etree.ElementTree as ET

def box(node):
    m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
    return tuple(int(v) for v in m.groups()) if m else None

root = ET.parse(sys.argv[1]).getroot()
nodes = [n for n in root.iter("node") if n.get("package") == "com.classboard.app"]
if sys.argv[2] == "center":
    for n in nodes:
        if n.get("content-desc") == sys.argv[3]:
            l, t, r, b = box(n)
            print((l + r) // 2, (t + b) // 2)
            sys.exit(0)
    sys.exit("not found: " + sys.argv[3])

screen = max((box(n) for n in nodes if box(n)), key=lambda b: (b[2] - b[0]) * (b[3] - b[1]))
sw, sh = screen[2], screen[3]
print(f"window {sw}x{sh} ({'portrait' if sh > sw else 'landscape'})")
bad = []
for n in nodes:
    d = n.get("content-desc")
    if not d:
        continue
    l, t, r, b = box(n)
    if r <= l or b <= t:
        continue
    if l < 0 or t < 0 or r > sw or b > sh:
        bad.append(f"{d} {n.get('bounds')}")
boxes = [(n.get("content-desc"), box(n)) for n in nodes if n.get("content-desc") and box(n)]
boxes = [(d, b) for d, b in boxes if b[2] > b[0] and b[3] > b[1]]
for i in range(len(boxes)):
    for j in range(i + 1, len(boxes)):
        (da, a), (db, b) = boxes[i], boxes[j]
        if min(a[2], b[2]) - max(a[0], b[0]) > 2 and min(a[3], b[3]) - max(a[1], b[1]) > 2:
            bad.append(f"{da} overlaps {db}")
for x in bad:
    print("problem:", x)
labels = sorted({n.get("content-desc") for n in nodes if n.get("content-desc")})
print("buttons:", ", ".join(labels))
if len(sys.argv) > 3 and sys.argv[3] == "portrait" and not sh > sw:
    sys.exit("expected portrait")
sys.exit(1 if bad else 0)

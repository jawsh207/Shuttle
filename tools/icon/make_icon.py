# The Shuttle launcher icon: a space shuttle in outline carrying a spool of thread in its
# cargo bay, teal at the nose to blue at the tail, with teal exhaust. Writes the Android
# vector (foreground + monochrome) and an SVG preview from the same shapes.
TOP, BOTTOM = "#45F0C6", "#3C7CFF"
OUTLINE = ("M54,25 C56.6,25 59.4,31.5 60.4,38 L60.6,44 C61,52 73,59.5 75.6,66 "
           "C76.6,68.4 76.5,71 75.4,71.3 L61,71.3 C61,73.6 60.4,75 59.4,75.2 L48.6,75.2 "
           "C47.6,75 47,73.6 47,71.3 L32.6,71.3 C31.5,71 31.4,68.4 32.4,66 C35,59.5 47,52 47.4,44 "
           "L47.6,38 C48.6,31.5 51.4,25 54,25 Z")
DETAILS = [
    "M51,37.6 C51.6,35.2 56.4,35.2 57,37.6",                                     # cockpit window
    "M49.8,40 L58.2,40 C59.4,40 60.4,41 60.4,42.2 L60.4,63.2 C60.4,64.4 59.4,65.4 58.2,65.4 "
    "L49.8,65.4 C48.6,65.4 47.6,64.4 47.6,63.2 L47.6,42.2 C47.6,41 48.6,40 49.8,40 Z",  # cargo bay
    "M47.4,65.4 L47.2,71.3 M60.6,65.4 L60.8,71.3",                               # fuselage below the bay
    "M32.8,69.4 L47.2,69.4 M60.8,69.4 L75.2,69.4",                               # wing trailing edge
    "M38.6,69.4 L38.6,71.3 M41.9,69.4 L41.9,71.3 M45.1,69.4 L45.1,71.3 "
    "M62.9,69.4 L62.9,71.3 M66.1,69.4 L66.1,71.3 M69.4,69.4 L69.4,71.3",         # elevons
    "M48.2,75.2 L48.2,76.8 C48.2,77.6 48.8,78.1 49.6,78.1 L58.4,78.1 C59.2,78.1 59.8,77.6 59.8,76.8 L59.8,75.2",  # engines
    "M54,65.6 a1.25,7.6 0 1,0 0.01,0 Z",                                         # tail fin
]
SPOOL = "M49.8,47.8 a4.2,4.2 0 0 1 8.4,0 L58.2,57.6 a4.2,4.2 0 0 1 -8.4,0 Z"
PEGS = "M53.2,41.8 L54.8,41.8 L54.8,43.6 L53.2,43.6 Z M53.2,61.8 L54.8,61.8 L54.8,63.6 L53.2,63.6 Z"
YARN = (" ".join(f"M48,{y:.1f} Q54,{y-1.4:.1f} 60,{y+3.6:.1f}" for y in [41 + 2.4 * i for i in range(10)]) + " " +
        " ".join(f"M48,{y:.1f} Q53.5,{y+1.2:.1f} 60,{y-3.4:.1f}" for y in [49 + 3.4 * i for i in range(5)]))
LOOSE = "M58.1,56.8 C60.6,57 61.8,59 60.1,60.6"
EXHAUST = [(38.6, 71.6, 81), (41.9, 71.6, 84), (45.1, 71.6, 85.5), (49.8, 78.4, 88), (58.2, 78.4, 88),
           (62.9, 71.6, 85.5), (66.1, 71.6, 84), (69.4, 71.6, 81)]

def grad_xml(x1, y1, x2, y2, a, b, alpha_end=None):
    end = b
    return (f'        <aapt:attr name="android:strokeColor">\n            <gradient android:type="linear" '
            f'android:startX="{x1}" android:startY="{y1}" android:endX="{x2}" android:endY="{y2}" '
            f'android:startColor="{a}" android:endColor="{end}" />\n        </aapt:attr>')

def apath(d, w, grad=None, color=None, alpha=None, cap="round"):
    attrs = [f'android:fillColor="#00000000"', f'android:strokeWidth="{w}"', f'android:strokeLineCap="{cap}"',
             'android:strokeLineJoin="round"', f'android:pathData="{d}"']
    if alpha: attrs.append(f'android:strokeAlpha="{alpha}"')
    if color: attrs.append(f'android:strokeColor="{color}"')
    a = "\n        ".join(attrs)
    if grad:
        return f'    <path\n        {a}>\n{grad}\n    </path>'
    return f'    <path\n        {a} />'

body_grad = lambda: grad_xml(54, 25, 54, 78, TOP, BOTTOM)
ex = "\n".join(
    apath(f"M{x},{y1} L{x},{y2}", 3, grad_xml(x, y1, x, y2, "#4045F0C6", "#0045F0C6"), alpha="0.9") + "\n" +
    apath(f"M{x},{y1} L{x},{y2}", 1.1, grad_xml(x, y1, x, y2, "#FF5CF5D2", "#0045F0C6"))
    for x, y1, y2 in EXHAUST)
android = f'''<?xml version="1.0" encoding="utf-8"?>
<!--
  Launcher icon: a space shuttle in outline carrying a spool of thread in its cargo bay (a
  shuttle that weaves), teal at the nose shading to blue at the tail, with glowing teal
  exhaust. Generated from one set of shapes along with the one-colour version
  (ic_launcher_monochrome.xml); the wings stay inside the 66dp safe zone.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <!-- Exhaust, under the craft. -->
{ex}
    <!-- The craft. -->
{apath(OUTLINE, 1.5, body_grad())}
{chr(10).join(apath(d, 1.3, body_grad()) for d in DETAILS)}
    <!-- The spool: thread wound round it, one loose end. -->
    <group>
        <clip-path android:pathData="{SPOOL}" />
{apath(YARN, 0.75, body_grad())}
    </group>
{apath(SPOOL, 1.3, body_grad())}
{apath(PEGS, 1.0, body_grad())}
{apath(LOOSE, 1.0, body_grad())}
</vector>
'''
open('app/src/main/res/drawable/ic_launcher_foreground.xml', 'w').write(android)

mono_paths = [OUTLINE, DETAILS[1], DETAILS[0], DETAILS[3], DETAILS[5], SPOOL, LOOSE] + [f"M{x},{y1} L{x},{y2 - 3}" for x, y1, y2 in EXHAUST]
mono = '''<?xml version="1.0" encoding="utf-8"?>
<!--
  One-colour version of the icon (the shuttle, its cargo bay, the spool and the exhaust) for
  themed launcher icons and notifications; the system tints it.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
''' + "\n".join(apath(d, 2.2, color="#FFFFFFFF") for d in mono_paths) + "\n</vector>\n"
open('app/src/main/res/drawable/ic_launcher_monochrome.xml', 'w').write(mono)

# Preview
def s(d, w, stroke="url(#g)", extra=""):
    return f'<path d="{d}" fill="none" stroke="{stroke}" stroke-width="{w}" stroke-linecap="round" stroke-linejoin="round" {extra}/>'
exs = "".join(
    f'<linearGradient id="e{i}" x1="{x}" y1="{y1}" x2="{x}" y2="{y2}" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#5CF5D2"/><stop offset="1" stop-color="#45F0C6" stop-opacity="0"/></linearGradient>'
    for i, (x, y1, y2) in enumerate(EXHAUST))
exp = "".join(s(f"M{x},{y1} L{x},{y2}", 3, f"url(#e{i})", 'stroke-opacity="0.3"') + s(f"M{x},{y1} L{x},{y2}", 1.1, f"url(#e{i})") for i, (x, y1, y2) in enumerate(EXHAUST))
svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="432" height="432" viewBox="18 18 72 72"><defs>
<radialGradient id="bg" cx="54" cy="50" r="76" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="#1C2A6E"/><stop offset="1" stop-color="#0A1030"/></radialGradient>
<linearGradient id="g" x1="54" y1="25" x2="54" y2="78" gradientUnits="userSpaceOnUse"><stop offset="0" stop-color="{TOP}"/><stop offset="1" stop-color="{BOTTOM}"/></linearGradient>{exs}
<clipPath id="m"><rect x="18" y="18" width="72" height="72" rx="20"/></clipPath><clipPath id="sp"><path d="{SPOOL}"/></clipPath></defs>
<g clip-path="url(#m)"><rect width="108" height="108" fill="url(#bg)"/>{exp}{s(OUTLINE,1.5)}{"".join(s(d,1.3) for d in DETAILS)}
<g clip-path="url(#sp)">{s(YARN,0.75)}</g>{s(SPOOL,1.3)}{s(PEGS,1.0)}{s(LOOSE,1.0)}</g></svg>'''
open('tools/icon/preview.svg', 'w').write(svg)

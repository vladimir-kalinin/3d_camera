# Генератор векторной иконки приложения (adaptive icon).
# Запуск из корня репозитория: python3 tools/gen_icon.py

import math
R = "app/src/main/res/drawable/"

def circle(cx, cy, r):
    return f"M{cx-r:g},{cy:g}a{r:g},{r:g} 0,1 0,{2*r:g},0a{r:g},{r:g} 0,1 0,{-2*r:g},0Z"

def ellipse(cx, cy, rx, ry):
    return f"M{cx-rx:g},{cy:g}a{rx:g},{ry:g} 0,1 0,{2*rx:g},0a{rx:g},{ry:g} 0,1 0,{-2*rx:g},0Z"

def rrect(x1, y1, x2, y2, r):
    return (f"M{x1+r:g},{y1:g}H{x2-r:g}A{r:g},{r:g} 0,0 1,{x2:g},{y1+r:g}V{y2-r:g}"
            f"A{r:g},{r:g} 0,0 1,{x2-r:g},{y2:g}H{x1+r:g}A{r:g},{r:g} 0,0 1,{x1:g},{y2-r:g}"
            f"V{y1+r:g}A{r:g},{r:g} 0,0 1,{x1+r:g},{y1:g}Z")

def ticks(cx, cy, r1, r2, n, w):
    # Радиальные насечки на оправе объектива
    out = []
    for i in range(n):
        a = 2 * math.pi * i / n
        da = w / r2 / 2
        pts = [(r1, a - da), (r2, a - da), (r2, a + da), (r1, a + da)]
        p = [(cx + r * math.cos(t), cy + r * math.sin(t)) for r, t in pts]
        out.append("M" + "L".join(f"{x:.2f},{y:.2f}" for x, y in p) + "Z")
    return "".join(out)

def vticks(x1, x2, y1, y2, step, w):
    out, x = [], x1 + step / 2
    while x < x2:
        out.append(f"M{x-w/2:.2f},{y1:g}h{w:g}v{y2-y1:g}h{-w:g}Z")
        x += step
    return "".join(out)

def lin(x1, y1, x2, y2, *stops):
    items = "".join(f'<item android:offset="{o}" android:color="{c}"/>' for o, c in stops)
    return (f'<aapt:attr name="android:fillColor"><gradient android:type="linear" '
            f'android:startX="{x1}" android:startY="{y1}" android:endX="{x2}" android:endY="{y2}">'
            f'{items}</gradient></aapt:attr>')

def rad(cx, cy, r, *stops):
    items = "".join(f'<item android:offset="{o}" android:color="{c}"/>' for o, c in stops)
    return (f'<aapt:attr name="android:fillColor"><gradient android:type="radial" '
            f'android:centerX="{cx}" android:centerY="{cy}" android:gradientRadius="{r}">'
            f'{items}</gradient></aapt:attr>')

def path(d, fill=None, grad=None, comment=None, evenodd=False):
    s = f"    <!-- {comment} -->\n" if comment else ""
    fo = ' android:fillType="evenOdd"' if evenodd else ""
    if grad:
        return s + f'    <path android:pathData="{d}"{fo}>\n        {grad}\n    </path>\n'
    return s + f'    <path android:fillColor="{fill}" android:pathData="{d}"{fo} />\n'

SCALE = 0.84  # уменьшение рисунка, чтобы камера целиком помещалась в круглую маску лаунчера

def group(body):
    return (f'    <group android:pivotX="54" android:pivotY="54" '
            f'android:scaleX="{SCALE}" android:scaleY="{SCALE}">\n' + body + '    </group>\n')

def vector(body, comment):
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            f'<!-- {comment} -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:aapt="http://schemas.android.com/aapt"\n'
            '    android:width="108dp"\n    android:height="108dp"\n'
            '    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
            + body + '</vector>\n')

CHROME = lambda y1, y2: lin(0, y1, 0, y2, (0, "#FFF5F5F2"), (0.45, "#FFC9C9C4"), (0.55, "#FFA9A9A3"), (1, "#FFE4E4DF"))

# Геометрия
L, Rx, LY = 40, 68, 60          # центры объективов
KNOBS = [(31.5, 0), (76.5, 0)]  # рифлёные головки сверху

fg = ""
fg += path(ellipse(54, 75, 31, 2.6), "#26000000", comment="Тень под камерой")
for kx, _ in KNOBS:
    fg += path(rrect(kx - 7, 36, kx + 7, 43, 1.2), grad=lin(kx - 7, 0, kx + 7, 0,
               (0, "#FF9C9C96"), (0.35, "#FFF2F2EE"), (1, "#FF8A8A84")), comment="Головка перемотки")
    fg += path(vticks(kx - 7, kx + 7, 37.2, 42.4, 1.4, 0.45), "#55000000")
    fg += path(ellipse(kx, 36, 7, 1.8), grad=rad(kx - 2, 35.5, 7, (0, "#FFFFFFFF"), (1, "#FFC4C4BE")))
    fg += path(ellipse(kx, 36, 2.2, 0.6), "#33000000")
fg += path(rrect(26, 40.5, 82, 45.5, 1.5), grad=CHROME(40.5, 45.5), comment="Верхняя хромированная планка")
fg += path(circle(43, 43, 1.3) + circle(65, 43, 1.3), "#FF6E6E68")
fg += path(rrect(48, 38.5, 60, 45.5, 1.5), grad=CHROME(38.5, 45.5), comment="Блок спуска")
fg += path(ellipse(54, 41.6, 2.6, 1.3), "#FFD93025", comment="Красная кнопка")
fg += path(ellipse(53.3, 41.2, 1, 0.4), "#80FFFFFF")

fg += path(rrect(22, 44, 86, 74, 5), grad=lin(0, 44, 0, 74, (0, "#FF7A4A36"), (1, "#FF4A2A1F")), comment="Корпус")
for x1 in (24, 77):
    fg += path(rrect(x1, 47, x1 + 7, 71, 2), grad=lin(0, 47, 0, 71, (0, "#FFB2A893"), (1, "#FF8C836F")),
               comment="Вставка под кожу")
    dots = "".join(circle(x1 + 1.2 + (i % 3) * 2.3 + (j % 2) * 1.15, 48.5 + j * 1.6, 0.35)
                   for j in range(14) for i in range(3) if x1 + 1.2 + (i % 3) * 2.3 + (j % 2) * 1.15 < x1 + 6.5)
    fg += path(dots, "#26000000")
fg += path(rrect(44, 46, 64, 49.5, 0.8), "#FF3A2018", comment="Табличка с названием")
fg += path(rrect(46, 47.3, 51, 48.2, 0.3) + rrect(52, 47.1, 62, 48.4, 0.3), "#FFE8D9B0")

fg += path(rrect(28.5, 50, 79.5, 71, 9), grad=lin(0, 50, 0, 71, (0, "#FFEDEBE6"), (1, "#FFB9B6AE")),
           comment="Светлая панель объективов")
fg += path(rrect(49.5, 55, 58.5, 65, 1.2), grad=CHROME(55, 65), comment="Видоискатель")
fg += path(rrect(51, 56.5, 57, 63.5, 0.6), grad=lin(51, 56.5, 57, 63.5, (0, "#FF4A5A66"), (1, "#FF101418")))
fg += path(rrect(52.5, 61, 55.5, 62.2, 0.4), "#FF6BDB6B")
fg += path(rrect(51.6, 57.1, 53.6, 57.6, 0.2), "#66FFFFFF")

for cx, tint in ((L, "#FFEA4335"), (Rx, "#FF12B5CB")):
    fg += path(circle(cx, LY, 10), grad=lin(cx - 10, LY - 10, cx + 10, LY + 10,
               (0, "#FFFFFFFF"), (0.5, "#FFC2C2BC"), (1, "#FF7C7C76")), comment="Рифлёная оправа объектива")
    fg += path(ticks(cx, LY, 8.2, 10, 40, 0.55), "#40000000")
    fg += path(circle(cx, LY, 8), grad=lin(cx + 8, LY + 8, cx - 8, LY - 8,
               (0, "#FFF4F4F0"), (1, "#FF9A9A94")))
    fg += path(circle(cx, LY, 6.6), "#FF1B1B1B", comment="Тубус")
    fg += path(circle(cx, LY, 5.4), grad=rad(cx, LY, 5.4, (0, "#FF2B3A4A"), (0.7, "#FF0E141A"), (1, "#FF050708")),
               comment="Стекло")
    fg += path(f"M{cx-4.6:g},{LY+0.5:g}A4.6,4.6 0,0 0,{cx+0.5:g},{LY+4.6:g}A3.6,3.6 0,0 1,{cx-4.6:g},{LY+0.5:g}Z",
               tint, comment="Цветной отблеск (анаглиф)")
    fg += path(ellipse(cx - 1.9, LY - 2, 1.6, 1), "#CCFFFFFF")
    fg += path(circle(cx + 2.1, LY + 1.4, 0.5), "#99FFFFFF")
for x in (22.6, 85.4):
    fg += path(rrect(x - 1, 50, x + 1, 55, 0.6), grad=CHROME(50, 55), comment="Ушко ремня")

open(R + "ic_launcher_foreground.xml", "w").write(
    vector(group(fg), "Стереокамера в духе Kodak Stereo: два объектива, левый с красным отблеском, правый с голубым"))

bg = path("M0,0h108v108h-108z", grad=lin(0, 0, 0, 108, (0, "#FFFFFFFF"), (1, "#FFF1F3F4")), comment="Белый фон")
open(R + "ic_launcher_background.xml", "w").write(vector(bg, "Фон иконки"))

mono = path(rrect(22, 44, 86, 74, 5) + rrect(26, 40.5, 82, 45.5, 1.5) + circle(L, LY, 10.8) + circle(Rx, LY, 10.8)
            + rrect(49.5, 55, 58.5, 65, 1.2), "#FF000000", evenodd=True)
mono += path("".join(rrect(kx - 7, 35, kx + 7, 41, 1.5) for kx, _ in KNOBS) + rrect(48, 38.5, 60, 42, 1.5), "#FF000000")
mono += path(circle(L, LY, 9.2) + circle(Rx, LY, 9.2), "#FF000000")
mono += path(circle(L, LY, 6) + circle(Rx, LY, 6), "#FF000000", evenodd=True)
open(R + "ic_launcher_monochrome.xml", "w").write(vector(group(mono), "Монохромная иконка для тематических иконок Android 13+"))

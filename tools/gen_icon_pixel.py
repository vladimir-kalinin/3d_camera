# Генератор варианта иконки в стиле блока камер Pixel 9 (чёрный корпус, графитовая рамка).
# Запуск из корня репозитория: python3 tools/gen_icon_pixel.py
R = "app/src/main/res/drawable/"
SCALE = 0.9  # отступ от круглой маски лаунчера


def circle(cx, cy, r):
    return f"M{cx-r:g},{cy:g}a{r:g},{r:g} 0,1 0,{2*r:g},0a{r:g},{r:g} 0,1 0,{-2*r:g},0Z"


def ellipse(cx, cy, rx, ry):
    return f"M{cx-rx:g},{cy:g}a{rx:g},{ry:g} 0,1 0,{2*rx:g},0a{rx:g},{ry:g} 0,1 0,{-2*rx:g},0Z"


def rrect(x1, y1, x2, y2, r):
    return (f"M{x1+r:g},{y1:g}H{x2-r:g}A{r:g},{r:g} 0,0 1,{x2:g},{y1+r:g}V{y2-r:g}"
            f"A{r:g},{r:g} 0,0 1,{x2-r:g},{y2:g}H{x1+r:g}A{r:g},{r:g} 0,0 1,{x1:g},{y2-r:g}"
            f"V{y1+r:g}A{r:g},{r:g} 0,0 1,{x1+r:g},{y1:g}Z")


def arc_band(cx, cy, r1, r2):
    # Серп отблеска в нижней левой четверти стекла
    return (f"M{cx-r1:g},{cy:g}A{r1:g},{r1:g} 0,0 0,{cx:g},{cy+r1:g}"
            f"A{r2:g},{r2:g} 0,0 1,{cx-r1:g},{cy:g}Z")


def grad(kind, attrs, *stops):
    items = "".join(f'<item android:offset="{o}" android:color="{c}"/>' for o, c in stops)
    a = " ".join(f'android:{k}="{v}"' for k, v in attrs.items())
    return f'<aapt:attr name="android:fillColor"><gradient android:type="{kind}" {a}>{items}</gradient></aapt:attr>'


def lin(x1, y1, x2, y2, *stops):
    return grad("linear", dict(startX=x1, startY=y1, endX=x2, endY=y2), *stops)


def rad(cx, cy, r, *stops):
    return grad("radial", dict(centerX=cx, centerY=cy, gradientRadius=r), *stops)


def path(d, fill=None, g=None, comment=None, stroke=None, evenodd=False):
    s = f"    <!-- {comment} -->\n" if comment else ""
    extra = ' android:fillType="evenOdd"' if evenodd else ""
    if stroke:
        extra += f' android:strokeColor="{stroke[0]}" android:strokeWidth="{stroke[1]}"'
    if g:
        return s + f'    <path android:pathData="{d}"{extra}>\n        {g}\n    </path>\n'
    return s + f'    <path android:fillColor="{fill}" android:pathData="{d}"{extra} />\n'


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


# Геометрия перенесена с фото блока камер (пилюля 860×440 px → 68×34)
GLASS = (22.3, 39.3, 85.7, 68.7)
MAIN, WIDE = (45.4, 54.5), (71.7, 54.2)

fg = ""
# Полоса рамки уходит за левый край, как на корпусе
BAND = "M-20,37H71A17,17 0,0 1,71 71H-20Z"
fg += path(BAND, g=lin(0, 37, 0, 71, (0, "#FF9A9EA3"), (0.08, "#FF6E7277"), (0.5, "#FF55595E"),
                          (0.92, "#FF7A7E83"), (1, "#FF3E4145")),
           comment="Графитовая рамка блока камер")
fg += path(rrect(*GLASS, 14.7), g=lin(0, 39.3, 0, 68.7, (0, "#FF202326"), (1, "#FF050607")),
           comment="Чёрное стекло")
fg += path(ellipse(42, 44, 15, 2.6), "#0FFFFFFF", comment="Блик на стекле")

fg += path(rrect(25.6, 52.6, 28.8, 55.8, 0.7), "#FF3A3A38", comment="Вспышка")
fg += path(rrect(26.3, 53.3, 28.1, 55.1, 0.4), "#FFD6CFB4")
fg += path(circle(60.7, 45.3, 0.75), "#FF8E9094", comment="Микрофон")
fg += path(circle(62.8, 64.6, 0.75), "#FFB07CF0", comment="Лазерный автофокус")
fg += path(circle(62.8, 64.6, 1.6), "#30B07CF0")

mx, my = MAIN
fg += path(circle(mx, my, 8.2), "#FF0B0C0E", stroke=("#FF33363B", 0.6), comment="Основная камера")
fg += path(circle(mx, my, 6.4), "#FF181A1D")
fg += path(circle(mx, my, 4.4), g=rad(mx, my, 4.4, (0, "#FF2E3640"), (0.75, "#FF14181C"), (1, "#FF3A3F45")))
fg += path(circle(mx, my, 2.4), g=rad(mx - 0.6, my - 0.6, 2.4, (0, "#FF3B4C5E"), (1, "#FF07090B")))
fg += path(arc_band(mx, my, 3.8, 3.0), "#FFEA4335", comment="Цветной отблеск (анаглиф)")
fg += path(ellipse(mx - 1.5, my - 1.7, 1.1, 0.7), "#CCFFFFFF")

wx, wy = WIDE
fg += path(circle(wx, wy, 5.8), "#FF16181B", stroke=("#FF33363B", 0.6), comment="Широкоугольная камера")
fg += path(circle(wx, wy, 4.2), "#FF0A0B0C")
fg += path(rrect(wx - 2.4, wy - 2.4, wx + 2.4, wy + 2.4, 1.1), g=rad(wx, wy, 3.2, (0, "#FF35404C"), (1, "#FF0C0F12")))
fg += path(arc_band(wx, wy, 3.4, 2.7), "#FF12B5CB", comment="Цветной отблеск (анаглиф)")
fg += path(ellipse(wx - 1.2, wy - 1.3, 0.9, 0.55), "#CCFFFFFF")

open(R + "ic_launcher_foreground.xml", "w").write(
    vector(group(fg), "Блок камер Pixel 9: левый объектив с красным отблеском, правый с голубым"))

bg = path("M0,0h108v108h-108z", g=rad(30, 20, 110, (0, "#FF34373C"), (0.55, "#FF1A1C1F"), (1, "#FF0B0C0D")),
          comment="Чёрный глянцевый корпус")
bg += path("M0,78L108,58V66L0,86Z", "#0AFFFFFF", comment="Отражение на стекле корпуса")
open(R + "ic_launcher_background.xml", "w").write(vector(bg, "Фон иконки"))

mono = path(BAND + rrect(*GLASS, 14.7), "#FF000000", evenodd=True)
mono += path(circle(mx, my, 8.2) + circle(mx, my, 6.4) + circle(wx, wy, 5.8) + circle(wx, wy, 4.2)
             + rrect(25.6, 52.6, 28.8, 55.8, 0.7), "#FF000000", evenodd=True)
mono += path(circle(mx, my, 3) + rrect(wx - 2, wy - 2, wx + 2, wy + 2, 0.9), "#FF000000")
open(R + "ic_launcher_monochrome.xml", "w").write(
    vector(group(mono), "Монохромная иконка для тематических иконок Android 13+"))

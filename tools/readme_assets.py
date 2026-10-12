# Картинки для README: иконка приложения (из векторных drawable) и скриншоты в рамке Pixel 9.
# Зависимости: pip install pillow resvg-py
# Запуск из корня репозитория:
#   python3 tools/readme_assets.py icon
#   python3 tools/readme_assets.py frame screenshot1.png screenshot2.png ...
# Результат — в docs/images/: icon.png и screenshot_N.png в порядке аргументов.
import base64
import io
import sys
import xml.etree.ElementTree as ET

import resvg_py
from PIL import Image

DRAWABLE = "app/src/main/res/drawable/"
OUT = "docs/images/"
A = "{http://schemas.android.com/apk/res/android}"
AAPT = "{http://schemas.android.com/aapt}"

BACKGROUND = "#3F4A46"  # серо-зелёный фон, как на промо-картинках Google
OUT_WIDTH = 720


def render(svg: str) -> Image.Image:
    png = bytes(resvg_py.svg_to_bytes(svg_string=svg))
    return Image.open(io.BytesIO(png)).convert("RGBA")


def color(value: str) -> tuple[str, float]:
    # #AARRGGBB / #RRGGBB → цвет SVG и прозрачность
    v = value.lstrip("#")
    if len(v) == 8:
        return "#" + v[2:], int(v[:2], 16) / 255
    return "#" + v, 1.0


# --- Иконка: VectorDrawable → SVG ---

def vector_to_svg(path: str, defs: list, counter: list) -> str:
    root = ET.parse(path).getroot()
    return "".join(convert(child, defs, counter) for child in root)


def convert(node, defs, counter) -> str:
    if node.tag == "group":
        px, py = float(node.get(A + "pivotX", 0)), float(node.get(A + "pivotY", 0))
        sx, sy = float(node.get(A + "scaleX", 1)), float(node.get(A + "scaleY", 1))
        inner = "".join(convert(c, defs, counter) for c in node)
        return f'<g transform="translate({px},{py}) scale({sx},{sy}) translate({-px},{-py})">{inner}</g>'
    if node.tag != "path":
        return ""
    attrs = [f'd="{node.get(A + "pathData")}"']
    fill = node.get(A + "fillColor")
    gradient = node.find(f"{AAPT}attr/gradient")
    if gradient is not None:
        counter[0] += 1
        gid = f"g{counter[0]}"
        stops = ""
        for item in gradient:
            c, o = color(item.get(A + "color"))
            stops += f'<stop offset="{item.get(A + "offset")}" stop-color="{c}" stop-opacity="{o:.3f}"/>'
        g = lambda k: gradient.get(A + k)
        if g("type") == "radial":
            defs.append(f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" cx="{g("centerX")}" '
                        f'cy="{g("centerY")}" r="{g("gradientRadius")}">{stops}</radialGradient>')
        else:
            defs.append(f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" x1="{g("startX")}" '
                        f'y1="{g("startY")}" x2="{g("endX")}" y2="{g("endY")}">{stops}</linearGradient>')
        attrs.append(f'fill="url(#{gid})"')
    elif fill:
        c, o = color(fill)
        attrs.append(f'fill="{c}" fill-opacity="{o:.3f}"')
    else:
        attrs.append('fill="none"')
    stroke = node.get(A + "strokeColor")
    if stroke:
        c, o = color(stroke)
        attrs.append(f'stroke="{c}" stroke-opacity="{o:.3f}" stroke-width="{node.get(A + "strokeWidth", 1)}"')
    return f'<path {" ".join(attrs)}/>'


def make_icon(size=512):
    defs, counter = [], [0]
    bg = vector_to_svg(DRAWABLE + "ic_launcher_background.xml", defs, counter)
    fg = vector_to_svg(DRAWABLE + "ic_launcher_foreground.xml", defs, counter)
    # Видимая часть адаптивной иконки — центральные 72×72 из 108, маска круглая, как у Pixel
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="18 18 72 72">'
           f'<defs>{"".join(defs)}<clipPath id="mask"><circle cx="54" cy="54" r="36"/></clipPath></defs>'
           f'<g clip-path="url(#mask)">{bg}{fg}</g></svg>')
    render(svg).save(OUT + "icon.png", optimize=True)


# --- Скриншоты в рамке Pixel 9 ---

def make_frame(src: str, dst: str):
    shot = Image.open(src).convert("RGB")
    w, h = shot.size
    buf = io.BytesIO()
    shot.save(buf, "PNG")
    data = base64.b64encode(buf.getvalue()).decode()

    s = w / 1080  # размеры ниже — для экрана шириной 1080
    radius = 110 * s   # скругление экрана
    bezel = 30 * s     # чёрная рамка вокруг экрана
    frame = 16 * s     # светлый металлический ободок
    pad_x, pad_y = 150 * s, 130 * s
    pw, ph = w + 2 * (bezel + frame), h + 2 * (bezel + frame)
    cw, ch = pw + 2 * pad_x, ph + 2 * pad_y
    x0, y0 = pad_x, pad_y
    sx, sy = x0 + frame + bezel, y0 + frame + bezel
    btn = 9 * s

    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink"
     width="{cw:.0f}" height="{ch:.0f}">
  <defs>
    <linearGradient id="metal" x1="0" y1="0" x2="1" y2="0">
      <stop offset="0" stop-color="#E8EBFC"/><stop offset="0.04" stop-color="#C9CCDB"/>
      <stop offset="0.5" stop-color="#D6D9E8"/><stop offset="0.96" stop-color="#C9CCDB"/>
      <stop offset="1" stop-color="#E8EBFC"/>
    </linearGradient>
    <clipPath id="screen"><rect x="{sx}" y="{sy}" width="{w}" height="{h}" rx="{radius}"/></clipPath>
  </defs>
  <rect width="100%" height="100%" fill="{BACKGROUND}"/>
  <!-- Кнопки питания и громкости справа -->
  <rect x="{x0 + pw - btn}" y="{y0 + ph * 0.25}" width="{2 * btn}" height="{170 * s}" rx="{btn}" fill="#C3C6D6"/>
  <rect x="{x0 + pw - btn}" y="{y0 + ph * 0.36}" width="{2 * btn}" height="{290 * s}" rx="{btn}" fill="#C3C6D6"/>
  <!-- Корпус: металлический ободок и чёрное стекло -->
  <rect x="{x0}" y="{y0}" width="{pw}" height="{ph}" rx="{radius + bezel + frame}" fill="url(#metal)"/>
  <rect x="{x0 + 1.5 * s}" y="{y0 + 1.5 * s}" width="{pw - 3 * s}" height="{ph - 3 * s}"
        rx="{radius + bezel + frame}" fill="none" stroke="#F4F5FF" stroke-width="{3 * s}" stroke-opacity="0.8"/>
  <rect x="{x0 + frame}" y="{y0 + frame}" width="{pw - 2 * frame}" height="{ph - 2 * frame}"
        rx="{radius + bezel}" fill="#050506" stroke="#3A3D46" stroke-width="{3 * s}"/>
  <image x="{sx}" y="{sy}" width="{w}" height="{h}" clip-path="url(#screen)"
         xlink:href="data:image/png;base64,{data}"/>
  <!-- Фронтальная камера -->
  <circle cx="{sx + w / 2}" cy="{sy + 58 * s}" r="{23 * s}" fill="#000"/>
  <circle cx="{sx + w / 2}" cy="{sy + 58 * s}" r="{10 * s}" fill="#14151C"/>
</svg>'''
    img = render(svg).convert("RGB")
    img = img.resize((OUT_WIDTH, round(img.height * OUT_WIDTH / img.width)), Image.LANCZOS)
    img.save(dst, optimize=True)


if __name__ == "__main__":
    if sys.argv[1] == "icon":
        make_icon()
    elif sys.argv[1] == "frame":
        for i, path in enumerate(sys.argv[2:], 1):
            make_frame(path, f"{OUT}screenshot_{i}.png")

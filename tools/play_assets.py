# Баннер для страницы в Google Play (feature graphic 1024×500) из иконки README.
# Зависимости: pip install pillow
# Запуск из корня репозитория после readme_assets.py icon: python3 tools/play_assets.py
# Результат — docs/play/feature_graphic.png.
from PIL import Image, ImageDraw, ImageFont

BACKGROUND = "#3F4A46"  # тот же фон, что у скриншотов README
FONT = "/System/Library/Fonts/HelveticaNeue.ttc"
W, H = 1024, 500

img = Image.new("RGB", (W, H), BACKGROUND)
icon = Image.open("docs/images/icon.png").convert("RGBA").resize((300, 300), Image.LANCZOS)
img.paste(icon, (90, (H - 300) // 2), icon)

draw = ImageDraw.Draw(img)
title = ImageFont.truetype(FONT, 76, index=1)  # Bold
subtitle = ImageFont.truetype(FONT, 34, index=0)
x = 440
draw.text((x, 150), "Stereo Camera", font=title, fill="white")
draw.text((x, 260), "3D side-by-side photos and video", font=subtitle, fill="#D8E4DF")
draw.text((x, 306), "from two rear cameras", font=subtitle, fill="#D8E4DF")

img.save("docs/play/feature_graphic.png")

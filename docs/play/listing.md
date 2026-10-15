# Google Play: страница приложения и анкеты

Тексты и ответы для Play Console. Картинки: иконка — `docs/images/icon.png` (512×512),
баннер — `docs/play/feature_graphic.png` (1024×500, `tools/play_assets.py`),
скриншоты — `docs/images/screenshot_*.png`.

## Store listing (Main store listing)

**App name** (≤30): `Stereo Camera`

**Short description** (≤80):

```
3D side-by-side photos and videos from the two rear cameras of your phone.
```

**Full description** (≤4000):

```
Stereo Camera shoots 3D photos and videos using the ultra-wide and the main rear cameras at the same time. The side-by-side (SBS) frame is composed on the GPU in real time, so the result is ready to watch in a VR headset, on a 3D TV or a glasses-free 3D display right away.

FEATURES
• Photo: full-resolution SBS, 6048×4032.
• Video: 4:3 at 30 fps or 16:9 at 30 / 60 fps, a single MP4 with stereo sound.
• Automatic alignment of the two cameras from lens calibration, with manual fine-tuning (scale, vertical and horizontal shift).
• Optional black frame with rounded corners around each eye, added around the image so no resolution is lost. The frame can float in front of the scene (stereo window).
• Color matching between the cameras, so both eyes look the same.
• Preview with both eyes side by side or overlaid.
• Files are saved to the "DCIM/3D Camera" album with the _SBS suffix that VR players recognize.

REQUIREMENTS
Your phone must be able to stream from the ultra-wide and the main rear cameras simultaneously. The app is developed and tested on Google Pixel 9; other phones may or may not work.

GOOD TO KNOW
• The two lenses are only about 16 mm apart, so depth is most visible on close-ups (0.5–3 m).
• Using two cameras at once heats the phone up; after 10–15 minutes of continuous use Android may lower the frame rate.

PRIVACY
No ads, no analytics, no accounts, no internet access. Photos and videos stay on your device.

Open source (MIT): github.com/vladimir-kalinin/3d_camera
```

**Category:** Photography. **Tags:** Camera, Photography.

## App content (Policy → App content)

- **Privacy policy:** https://vladimir-kalinin.github.io/3d_camera/privacy
- **Ads:** No, the app does not contain ads.
- **App access:** All functionality is available without special access.
- **Target audience:** 18+ (или 13+; не выбирать возрасты до 13 — иначе требования Families).
- **Content rating (IARC):** категория «All other app types»; на все вопросы — No
  (нет насилия, общения пользователей, обмена контентом, покупок, геолокации). Ожидаемо — Everyone / 3+.
- **Data safety:**
  - Does your app collect or share any of the required user data types? — **No.**
    (Фото, видео и звук не покидают устройство; по правилам Google обработка только на устройстве
    не считается сбором.)
  - Is all data encrypted in transit? — не применимо (нет передачи).
  - Account deletion — не применимо (нет аккаунтов).
- **Government apps / Financial / Health / News / COVID:** No.
- **Advertising ID:** No (приложение не использует рекламный идентификатор).
- **Foreground service / exact alarms / photo-video permissions:** не используются.

## Устройства

Приложение работает не на всех телефонах (нужны одновременные потоки широкоугольной и основной).
Ограничить список можно в **Release → Device catalog**: исключить устройства или,
наоборот, после отзывов тестировщиков добавлять проверенные.

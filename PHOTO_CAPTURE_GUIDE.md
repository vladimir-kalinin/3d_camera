# 🎥 Руководство по записи 3D видео в 3D Camera App

## 🎯 Обзор

Приложение 3D Camera использует **MediaRecorder + Surface sharing** для записи 3D видео в формате **Side-by-Side (SBS)** для VR. Этот метод обеспечивает:
- ✅ Максимальное качество видео (до 4K SBS)
- ✅ Плавный предпросмотр без лагов
- ✅ Нативная поддержка SBS формата
- ✅ Эффективное использование ресурсов
- ✅ Готовый формат для VR-устройств

## 🔧 Технические детали

### Способы съемки в Camera2 API

1. **ImageReader + отдельные capture запросы (используется)** - максимальное качество, стабильная работа
2. **TextureView.getBitmap() (устаревший)** - простой, но низкое разрешение
3. **SurfaceView + MediaRecorder** - для видео
4. **Гибридный подход** - баланс между качеством и производительностью

### Выбранный подход: ImageReader + Interleaved Capture Requests

**Преимущества:**
- Максимальное разрешение камеры (обычно 4K+)
- Высокое качество JPEG без потерь
- Не прерывает поток предпросмотра
- Использует TEMPLATE_STILL_CAPTURE для лучшего качества
- Поддерживает все настройки камеры

**Особенности реализации:**
- Согласно [документации Android](https://developer.android.com/media/camera/camera2/capture-sessions-requests), отдельные capture запросы имеют более высокий приоритет чем repeating requests
- ImageReader'ы добавляются в сессию камеры при инициализации
- Capture запросы отправляются параллельно для обеих камер

## 📋 Алгоритм работы

### 1. Инициализация ImageReader'ов
```kotlin
private fun setupImageReaders() {
    // Получаем максимальные размеры для JPEG
    val jpegSizes = streamConfigurationMap?.getOutputSizes(ImageFormat.JPEG)
    val maxJpegSize = jpegSizes?.maxByOrNull { it.width * it.height }
    
    // Создаем ImageReader для каждой камеры
    wideImageReader = ImageReader.newInstance(
        maxJpegSize.width, maxJpegSize.height, ImageFormat.JPEG, 1
    )
    mainImageReader = ImageReader.newInstance(
        maxJpegSize.width, maxJpegSize.height, ImageFormat.JPEG, 1
    )
}
```

### 2. Процесс съемки
```kotlin
private fun capturePhoto() {
    // 1. Проверка состояния
    if (isCapturing) return
    
    // 2. Блокировка повторной съемки
    isCapturing = true
    binding.captureButton.isEnabled = false
    
    // 3. Создание отдельных capture запросов
    val wideRequest = session.device.createCaptureRequest(
        CameraDevice.TEMPLATE_STILL_CAPTURE,
        setOf(wideCameraInstance.physicalId)
    ).apply {
        addTarget(wideImageReader!!.surface)
        set(CaptureRequest.JPEG_QUALITY, 95.toByte())
    }
    
    val mainRequest = session.device.createCaptureRequest(
        CameraDevice.TEMPLATE_STILL_CAPTURE,
        setOf(mainCameraInstance.physicalId)
    ).apply {
        addTarget(mainImageReader!!.surface)
        set(CaptureRequest.JPEG_QUALITY, 95.toByte())
    }
    
    // 4. Отправка запросов параллельно
    session.capture(wideRequest.build(), createCaptureCallback("wide"), cameraHandler)
    session.capture(mainRequest.build(), createCaptureCallback("main"), cameraHandler)
}
```

### 3. Обработка захваченных изображений
```kotlin
private fun processCapturedImages() {
    executor.execute {
        // Конвертируем Image в Bitmap
        val wideBitmap = imageTobitmap(capturedWideImage)
        val mainBitmap = imageTobitmap(capturedMainImage)
        
        // Применяем кроп к широкоугольной камере
        val processedWideBitmap = applyCropToBitmap(wideBitmap, wideCameraInstance.cropValue)
        
        // Создаем SBS изображение
        val sbsBitmap = createSideBySideBitmap(processedWideBitmap, mainBitmap)
        
        // Сохраняем результат
        saveSbsBitmap(sbsBitmap)
    }
}
```

## 🎛️ Параметры съемки

### Качество JPEG
- **Уровень сжатия**: 95% (максимальное качество)
- **Формат**: JPEG
- **Разрешение**: Максимальное доступное (обычно 4K+)
- **Шаблон**: TEMPLATE_STILL_CAPTURE для лучшего качества

### Применение эффектов
- **Зум**: Программно применяется к широкоугольной камере при обработке
- **Поворот**: Автоматически корректируется
- **Кроп**: Программно применяется с высокой точностью
- **Фокус**: CONTROL_AF_MODE_CONTINUOUS_PICTURE для лучшего качества

### Обработка кропа
Кроп применяется программно к полноразмерному изображению:

1. **Захват**: Получаем полноразмерное изображение от ImageReader
2. **Обработка**: Применяем кроп через `Bitmap.createBitmap()` с точными координатами
3. **Результат**: Высококачественное изображение с примененным кропом

### Формат SBS (Side-by-Side)
- **Структура**: Левое изображение | Правое изображение
- **Левая часть**: Широкоугольная камера (с примененным кропом)
- **Правая часть**: Основная камера
- **Соотношение сторон**: 2:1 (ширина удваивается)
- **Разрешение**: До 8K+ в зависимости от камеры

### Имена файлов
```
3D_Camera_SBS_20240115_143022.jpg
```

## 📁 Сохранение

### Местоположение
- **Папка**: `Pictures/3D_Camera/`
- **API**: MediaStore (Android 10+)
- **Разрешения**: Не требуются для MediaStore

### Процесс сохранения
1. Создание ContentValues с метаданными
2. Вставка записи в MediaStore
3. Запись байтов изображения через OutputStream
4. Автоматическое появление в галерее

## 🚀 Производительность

### Оптимизации
- Съемка не блокирует предпросмотр (согласно документации Android)
- Обработка в фоновом потоке
- Параллельная отправка capture запросов
- Эффективное управление памятью

### Время выполнения
- **Захват кадра**: ~50-200мс (зависит от разрешения)
- **Обработка**: ~200-1000мс (в фоне, зависит от разрешения)
- **Сохранение**: ~100-500мс (в фоне)
- **Общее время**: ~2-5 секунд для 4K изображений

### Использование памяти
- **4K изображение**: ~50-100MB временно
- **8K изображение**: ~200-400MB временно
- Автоматическая очистка после обработки

## 🔍 Отладка

### Логирование
```kotlin
Log.d(TAG, "Максимальный размер JPEG: ${maxJpegSize.width}x${maxJpegSize.height}")
Log.d(TAG, "Получено изображение от широкоугольной камеры: ${image.width}x${image.height}")
Log.d(TAG, "Capture завершен для $cameraType")
```

### Проверка ошибок
- Проверка доступности ImageReader'ов
- Обработка ошибок capture запросов
- Контроль использования памяти
- Восстановление состояния интерфейса

## 📱 Использование

1. **Запуск приложения** - автоматическая инициализация камер и ImageReader'ов
2. **Настройка зума** - используйте ползунок для широкоугольной камеры
3. **Съемка** - нажмите кнопку захвата
4. **Результат** - 3D фото высокого разрешения в формате SBS автоматически сохраняется в галерею

## 🥽 Просмотр в VR

Созданные SBS изображения высокого разрешения можно просматривать в:
- **VR-очках** (Oculus, HTC Vive, Google Cardboard)
- **3D-мониторах** с поддержкой SBS
- **Мобильных VR-приложениях** (VR Player, Skybox VR)
- **Стереоскопических просмотрщиках**

## 🔧 Возможные улучшения

### Качество изображения
- HDR режим для расширенного динамического диапазона
- RAW захват для профессиональной обработки
- Настройка экспозиции и ISO

### Функциональность
- Серийная съемка
- Таймер съемки
- Ручные настройки камеры

### Производительность
- Кэширование размеров изображений
- Оптимизация сжатия
- Прогрессивное сохранение

---

*Документация обновлена: 15 января 2024* 
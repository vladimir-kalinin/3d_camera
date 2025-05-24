# 📸 Руководство по съемке фото в 3D Camera App

## 🎯 Обзор

Приложение 3D Camera использует **простой подход через TextureView** для съемки стереопар в формате **Side-by-Side (SBS)** для VR. Этот метод обеспечивает:
- ✅ Плавный предпросмотр без пересоздания сессий
- ✅ Правильную ориентацию изображений
- ✅ Корректное применение зума
- ✅ Высокую производительность
- ✅ Готовый формат для VR-устройств

## 🔧 Технические детали

### Способы съемки в Camera2 API

1. **ImageReader (сложный)** - высокое качество, но требует пересоздания сессий
2. **TextureView.getBitmap() (используется)** - простой, стабильный, правильная ориентация
3. **SurfaceView + MediaRecorder** - для видео
4. **Гибридный подход** - баланс между качеством и производительностью

### Выбранный подход: TextureView.getBitmap()

**Преимущества:**
- Не требует пересоздания сессий камеры
- Автоматически применяет все трансформации (поворот, зум)
- Простая реализация
- Стабильная работа

**Недостатки:**
- Качество ограничено разрешением предпросмотра
- Меньше контроля над параметрами съемки

## 📋 Алгоритм работы

### 1. Инициализация
```kotlin
// Переменные для съемки
private var isCapturing = false
```

### 2. Процесс съемки
```kotlin
private fun capturePhoto() {
    // 1. Проверка состояния
    if (isCapturing) return
    
    // 2. Блокировка повторной съемки
    isCapturing = true
    binding.captureButton.isEnabled = false
    
    // 3. Захват кадров из TextureView
    val wideBitmap = wideCameraInstance.textureView?.getBitmap()
    val mainBitmap = mainCameraInstance.textureView?.getBitmap()
    
    // 4. Обработка и сохранение в фоновом потоке
    executor.execute {
        // Применяем кроп к широкоугольной камере
        val processedWideBitmap = applyCropToBitmap(wideBitmap, wideCameraInstance.cropValue)
        
        // Создаем SBS изображение
        val sbsBitmap = createSideBySideBitmap(processedWideBitmap, mainBitmap)
        
        if (sbsBitmap != null) {
            // Сохраняем SBS изображение
            saveSbsBitmap(sbsBitmap)
            
            // Освобождаем память
            processedWideBitmap.recycle()
            sbsBitmap.recycle()
            
            runOnUiThread {
                Toast.makeText(this, "3D фото сохранено в галерею", Toast.LENGTH_SHORT).show()
                resetCaptureState()
            }
        }
    }
}
```

### 3. Создание SBS изображения
```kotlin
private fun createSideBySideBitmap(leftBitmap: Bitmap, rightBitmap: Bitmap): Bitmap? {
    // Определяем размеры для SBS изображения
    val targetHeight = min(leftBitmap.height, rightBitmap.height)
    val targetWidth = min(leftBitmap.width, rightBitmap.width)
    
    // Создаем SBS изображение (ширина удваивается)
    val sbsWidth = targetWidth * 2
    val sbsHeight = targetHeight
    
    val sbsBitmap = Bitmap.createBitmap(sbsWidth, sbsHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(sbsBitmap)
    
    // Масштабируем и размещаем левое изображение (широкоугольная камера)
    val leftScaledBitmap = Bitmap.createScaledBitmap(leftBitmap, targetWidth, targetHeight, true)
    canvas.drawBitmap(leftScaledBitmap, 0f, 0f, null)
    
    // Масштабируем и размещаем правое изображение (основная камера)
    val rightScaledBitmap = Bitmap.createScaledBitmap(rightBitmap, targetWidth, targetHeight, true)
    canvas.drawBitmap(rightScaledBitmap, targetWidth.toFloat(), 0f, null)
    
    // Освобождаем временные bitmap
    if (leftScaledBitmap != leftBitmap) leftScaledBitmap.recycle()
    if (rightScaledBitmap != rightBitmap) rightScaledBitmap.recycle()
    
    return sbsBitmap
}

private fun applyCropToBitmap(originalBitmap: Bitmap, cropFactor: Float): Bitmap {
    val originalWidth = originalBitmap.width
    val originalHeight = originalBitmap.height
    
    // Вычисляем размеры обрезанной области
    val croppedWidth = (originalWidth / cropFactor).toInt()
    val croppedHeight = (originalHeight / cropFactor).toInt()
    
    // Центрируем обрезку
    val offsetX = (originalWidth - croppedWidth) / 2
    val offsetY = (originalHeight - croppedHeight) / 2
    
    // Создаем обрезанный bitmap
    return Bitmap.createBitmap(originalBitmap, offsetX, offsetY, croppedWidth, croppedHeight)
}
```

## 🎛️ Параметры съемки

### Качество JPEG
- **Уровень сжатия**: 95% (высокое качество)
- **Формат**: JPEG
- **Разрешение**: Соответствует разрешению предпросмотра

### Применение эффектов
- **Зум**: Автоматически применяется из предпросмотра
- **Поворот**: Автоматически корректируется
- **Кроп**: Программно применяется к широкоугольной камере

### Обработка кропа
Важная особенность: `TextureView.getBitmap()` возвращает исходное изображение без учета трансформаций матрицы. Поэтому кроп применяется программно:

1. **В предпросмотре**: Кроп применяется через `Matrix.postScale()` к TextureView
2. **При сохранении**: Кроп применяется через `Bitmap.createBitmap()` с вычисленными координатами
3. **Результат**: Сохраненное фото точно соответствует тому, что видит пользователь

### Формат SBS (Side-by-Side)
- **Структура**: Левое изображение | Правое изображение
- **Левая часть**: Широкоугольная камера (с примененным кропом)
- **Правая часть**: Основная камера
- **Соотношение сторон**: 2:1 (ширина удваивается)

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
- Съемка не блокирует предпросмотр
- Обработка в фоновом потоке
- Минимальное использование памяти
- Быстрое восстановление интерфейса

### Время выполнения
- **Захват кадра**: ~10-50мс
- **Сохранение**: ~100-500мс (в фоне)
- **Общее время**: ~1-2 секунды

## 🔍 Отладка

### Логирование
```kotlin
Log.d(TAG, "Захвачены изображения: wide=${wideBitmap.width}x${wideBitmap.height}")
Log.d(TAG, "Изображение $cameraType сохранено в галерею: $filename")
```

### Проверка ошибок
- Проверка доступности TextureView
- Обработка исключений при сохранении
- Восстановление состояния интерфейса

## 📱 Использование

1. **Запуск приложения** - автоматическая инициализация камер
2. **Настройка зума** - используйте ползунок для широкоугольной камеры
3. **Съемка** - нажмите кнопку захвата
4. **Результат** - 3D фото в формате SBS автоматически сохраняется в галерею

## 🥽 Просмотр в VR

Созданные SBS изображения можно просматривать в:
- **VR-очках** (Oculus, HTC Vive, Google Cardboard)
- **3D-мониторах** с поддержкой SBS
- **Мобильных VR-приложениях** (VR Player, Skybox VR)
- **Стереоскопических просмотрщиках**

## 🔧 Возможные улучшения

### Качество изображения
- Увеличение разрешения предпросмотра
- Применение постобработки
- HDR режим

### Функциональность
- Серийная съемка
- Таймер съемки
- Ручные настройки экспозиции

### Производительность
- Кэширование Bitmap
- Оптимизация сжатия
- Асинхронное сохранение

---

*Документация обновлена: 15 января 2024* 
package com.example.my_3d_camera.opengl

import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class SBSRenderer {
    
    companion object {
        private const val TAG = "SBSRenderer"
        
        // Vertex shader для рендеринга текстур
        private const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTextureCoord;
            uniform mat4 uMVPMatrix;
            varying vec2 vTextureCoord;
            
            void main() {
                gl_Position = uMVPMatrix * aPosition;
                vTextureCoord = aTextureCoord;
            }
        """
        
        // Fragment shader для рендеринга текстур
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            
            varying vec2 vTextureCoord;
            uniform samplerExternalOES uTexture;
            
            void main() {
                gl_FragColor = texture2D(uTexture, vTextureCoord);
            }
        """
    }
    
    private var program = 0
    private var aPositionHandle = 0
    private var aTextureCoordHandle = 0
    private var uMVPMatrixHandle = 0
    private var uTextureHandle = 0
    
    // Буферы для вершин
    private lateinit var leftVertexBuffer: FloatBuffer
    private lateinit var rightVertexBuffer: FloatBuffer
    private lateinit var textureCoordBuffer: FloatBuffer
    
    // Матрицы трансформации
    private val mvpMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    
    // Текстуры для левого и правого видео
    private var leftTextureId = 0
    private var rightTextureId = 0
    
    // SurfaceTexture для получения кадров
    var leftSurfaceTexture: SurfaceTexture? = null
        private set
    var rightSurfaceTexture: SurfaceTexture? = null
        private set
    
    fun initialize(outputWidth: Int, outputHeight: Int) {
        Log.d(TAG, "Инициализация SBS рендерера: ${outputWidth}x${outputHeight}")
        
        // Создаем шейдерную программу
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        if (program == 0) {
            throw RuntimeException("Не удалось создать шейдерную программу")
        }
        
        // Получаем хендлы атрибутов и uniform переменных
        aPositionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        aTextureCoordHandle = GLES20.glGetAttribLocation(program, "aTextureCoord")
        uMVPMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix")
        uTextureHandle = GLES20.glGetUniformLocation(program, "uTexture")
        
        checkGlError("Получение хендлов")
        
        // Создаем буферы вершин
        setupVertexBuffers()
        
        // Создаем текстуры
        setupTextures()
        
        // Настраиваем viewport и матрицы
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, -3f, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.orthoM(projectionMatrix, 0, -1f, 1f, -1f, 1f, 1f, 10f)
        Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
        
        checkGlError("Инициализация завершена")
    }
    
    private fun setupVertexBuffers() {
        // Координаты для левой половины экрана (левое видео)
        val leftVertices = floatArrayOf(
            -1.0f, -1.0f, 0.0f,  // Нижний левый
            0.0f, -1.0f, 0.0f,   // Нижний правый
            -1.0f, 1.0f, 0.0f,   // Верхний левый
            0.0f, 1.0f, 0.0f     // Верхний правый
        )
        
        // Координаты для правой половины экрана (правое видео)
        val rightVertices = floatArrayOf(
            0.0f, -1.0f, 0.0f,   // Нижний левый
            1.0f, -1.0f, 0.0f,   // Нижний правый
            0.0f, 1.0f, 0.0f,    // Верхний левый
            1.0f, 1.0f, 0.0f     // Верхний правый
        )
        
        // Координаты текстуры
        val textureCoords = floatArrayOf(
            0.0f, 1.0f,  // Нижний левый
            1.0f, 1.0f,  // Нижний правый
            0.0f, 0.0f,  // Верхний левый
            1.0f, 0.0f   // Верхний правый
        )
        
        leftVertexBuffer = ByteBuffer.allocateDirect(leftVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(leftVertices)
            .apply { position(0) }
        
        rightVertexBuffer = ByteBuffer.allocateDirect(rightVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(rightVertices)
            .apply { position(0) }
        
        textureCoordBuffer = ByteBuffer.allocateDirect(textureCoords.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(textureCoords)
            .apply { position(0) }
    }
    
    private fun setupTextures() {
        val textures = IntArray(2)
        GLES20.glGenTextures(2, textures, 0)
        
        leftTextureId = textures[0]
        rightTextureId = textures[1]
        
        // Настраиваем левую текстуру
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, leftTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        
        // Настраиваем правую текстуру
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, rightTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        
        // Создаем SurfaceTexture для каждой текстуры
        leftSurfaceTexture = SurfaceTexture(leftTextureId)
        rightSurfaceTexture = SurfaceTexture(rightTextureId)
        
        checkGlError("Настройка текстур")
    }
    
    fun renderFrame() {
        // Очищаем экран
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        
        // Используем нашу шейдерную программу
        GLES20.glUseProgram(program)
        
        // Обновляем текстуры
        leftSurfaceTexture?.updateTexImage()
        rightSurfaceTexture?.updateTexImage()
        
        // Рендерим левое видео
        renderVideo(leftVertexBuffer, leftTextureId)
        
        // Рендерим правое видео
        renderVideo(rightVertexBuffer, rightTextureId)
        
        checkGlError("Рендеринг кадра")
    }
    
    private fun renderVideo(vertexBuffer: FloatBuffer, textureId: Int) {
        // Устанавливаем атрибуты вершин
        GLES20.glEnableVertexAttribArray(aPositionHandle)
        GLES20.glVertexAttribPointer(aPositionHandle, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        
        GLES20.glEnableVertexAttribArray(aTextureCoordHandle)
        GLES20.glVertexAttribPointer(aTextureCoordHandle, 2, GLES20.GL_FLOAT, false, 0, textureCoordBuffer)
        
        // Устанавливаем uniform переменные
        GLES20.glUniformMatrix4fv(uMVPMatrixHandle, 1, false, mvpMatrix, 0)
        
        // Привязываем текстуру
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uTextureHandle, 0)
        
        // Рисуем
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        
        // Отключаем атрибуты
        GLES20.glDisableVertexAttribArray(aPositionHandle)
        GLES20.glDisableVertexAttribArray(aTextureCoordHandle)
    }
    
    fun release() {
        leftSurfaceTexture?.release()
        rightSurfaceTexture?.release()
        
        if (program != 0) {
            GLES20.glDeleteProgram(program)
            program = 0
        }
        
        val textures = intArrayOf(leftTextureId, rightTextureId)
        GLES20.glDeleteTextures(2, textures, 0)
    }
    
    private fun createProgram(vertexShaderCode: String, fragmentShaderCode: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Ошибка линковки программы: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }
        
        return program
    }
    
    private fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "Ошибка компиляции шейдера: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        
        return shader
    }
    
    private fun checkGlError(operation: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "OpenGL ошибка после $operation: $error")
            throw RuntimeException("OpenGL ошибка после $operation: $error")
        }
    }
} 
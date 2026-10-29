package com.example.conductorgame

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.conductorgame.ui.theme.ConductorGameTheme
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        DebugLogger.init(applicationContext)
        setContent {
            ConductorGameTheme {
                MainApp()
            }
        }
    }
}

// --- ЛОГГЕР ---
object DebugLogger {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs
    private var logFile: File? = null

    fun init(context: Context) {
        logFile = File(context.filesDir, "game_debug.log")
        if (logFile?.exists() == true) {
            try {
                val savedLogs = logFile?.readLines() ?: emptyList()
                _logs.value = savedLogs.takeLast(100)
            } catch (e: Exception) {
                _logs.value = listOf("Ошибка чтения логов: ${e.message}")
            }
        }
        log("--- Приложение запущено ---")
    }

    fun log(message: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val newLog = "[$time] $message"
        _logs.value = (_logs.value + newLog).takeLast(100)
        try { logFile?.appendText("$newLog\n") } catch (e: Exception) {}
    }

    fun error(message: String) { log("❌ ERROR: $message") }
}

// --- СУЩНОСТИ ---
data class Block(val x: Float, val y: Float, val width: Float, val height: Float, val isBrick: Boolean = false, val hasCoin: Boolean = false)
data class Pipe(val x: Float, val y: Float, val width: Float, val height: Float)
data class Enemy(var x: Float, var y: Float, var velocityX: Float, val width: Float, val height: Float, var isAlive: Boolean = true)
data class Coin(var x: Float, var y: Float, var collected: Boolean = false)

// --- ГЛАВНЫЙ РОУТЕР ---
@Composable
fun MainApp() {
    var currentScreen by remember { mutableStateOf("menu") }
    var useCamera by remember { mutableStateOf(false) }

    when (currentScreen) {
        "menu" -> MenuScreen(
            onStartGame = { currentScreen = "game" },
            onOpenSettings = { currentScreen = "settings" },
            useCamera = useCamera
        )
        "game" -> GameScreen(
            useCamera = useCamera,
            onExit = { currentScreen = "menu" }
        )
        "settings" -> SettingsScreen(
            useCamera = useCamera,
            onToggleCamera = {
                useCamera = it
                DebugLogger.log("Камера переключена: $it")
            },
            onBack = { currentScreen = "menu" }
        )
    }
}

// --- МЕНЮ ---
@Composable
fun MenuScreen(onStartGame: () -> Unit, onOpenSettings: () -> Unit, useCamera: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E24)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("СУПЕР МАРИО", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text("Управление: ${if (useCamera) "Камера (Жесты)" else "Кнопки"}", color = Color.Gray, fontSize = 16.sp)
        Spacer(modifier = Modifier.height(40.dp))
        Button(onClick = onStartGame, modifier = Modifier.fillMaxWidth(0.7f).height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))) {
            Text("ИГРАТЬ", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth(0.7f).height(60.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))) {
            Text("НАСТРОЙКИ И ЛОГИ", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// --- НАСТРОЙКИ ---
@Composable
fun SettingsScreen(useCamera: Boolean, onToggleCamera: (Boolean) -> Unit, onBack: () -> Unit) {
    val logs by DebugLogger.logs.collectAsState()
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            DebugLogger.log("Разрешение камеры: $granted")
            if (!granted) onToggleCamera(false)
        }
    )

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E24)).padding(16.dp)) {
        Text("НАСТРОЙКИ", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Управление жестами (Камера)", color = Color.White, fontSize = 18.sp)
            Switch(
                checked = useCamera,
                onCheckedChange = { isChecked ->
                    if (isChecked) {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                            onToggleCamera(true)
                        } else {
                            launcher.launch(Manifest.permission.CAMERA)
                        }
                    } else {
                        onToggleCamera(false)
                    }
                }
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text("ЛОГИ", color = Color.Yellow, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))
        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(8.dp)) {
            LazyColumn {
                items(logs) { log -> Text(log, color = Color.Green, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.Gray)) {
            Text("НАЗАД В МЕНЮ", fontSize = 18.sp)
        }
    }
}

// --- ИГРА ---
@Composable
fun GameScreen(useCamera: Boolean, onExit: () -> Unit) {
    var currentGesture by remember { mutableStateOf(HandGesture.NONE) }

    var playerX by remember { mutableFloatStateOf(100f) }
    var playerY by remember { mutableFloatStateOf(500f) }
    var velocityY by remember { mutableFloatStateOf(0f) }
    var velocityX by remember { mutableFloatStateOf(0f) }
    var isGrounded by remember { mutableStateOf(false) }

    val blocks = remember { mutableStateListOf<Block>() }
    val pipes = remember { mutableStateListOf<Pipe>() }
    val enemies = remember { mutableStateListOf<Enemy>() }
    val coins = remember { mutableStateListOf<Coin>() }

    var score by remember { mutableStateOf(0) }
    var coinCount by remember { mutableStateOf(0) }
    var gameOver by remember { mutableStateOf(false) }
    var gameWon by remember { mutableStateOf(false) }
    var time by remember { mutableFloatStateOf(0f) }
    var cameraX by remember { mutableFloatStateOf(0f) }

    val interactionSource1 = remember { MutableInteractionSource() }
    val isPressed1 by interactionSource1.collectIsPressedAsState()
    val interactionSource2 = remember { MutableInteractionSource() }
    val isPressed2 by interactionSource2.collectIsPressedAsState()
    var isKey1Pressed by remember { mutableStateOf(false) }
    var isKey2Pressed by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // --- ЛОГИКА УПРАВЛЕНИЯ ---
    // Прыжок: Большой палец или кнопка А
    val jumpPressed = isPressed1 || isKey1Pressed || (useCamera && currentGesture == HandGesture.THUMB)
    // Бег (макс): Указательный + средний
    val isRunningMax = useCamera && currentGesture == HandGesture.TWO_FINGERS
    // Идти: Указательный палец или кнопка L
    val isWalking = isPressed2 || isKey2Pressed || (useCamera && currentGesture == HandGesture.INDEX_FINGER)

    LaunchedEffect(Unit) {
        blocks.add(Block(0f, 700f, 1200f, 200f))
        blocks.add(Block(1350f, 700f, 800f, 200f))
        blocks.add(Block(2300f, 700f, 1400f, 200f))
        blocks.add(Block(3850f, 700f, 1000f, 200f))

        blocks.add(Block(500f, 500f, 40f, 40f, isBrick = true))
        blocks.add(Block(540f, 500f, 40f, 40f, hasCoin = true))
        blocks.add(Block(580f, 500f, 40f, 40f, isBrick = true))
        blocks.add(Block(1500f, 450f, 40f, 40f, hasCoin = true))
        blocks.add(Block(1540f, 450f, 40f, 40f, isBrick = true))
        blocks.add(Block(2600f, 500f, 40f, 40f, hasCoin = true))
        blocks.add(Block(2640f, 500f, 40f, 40f, hasCoin = true))

        pipes.add(Pipe(800f, 620f, 80f, 80f))
        pipes.add(Pipe(1800f, 560f, 80f, 140f))
        pipes.add(Pipe(3000f, 620f, 80f, 80f))

        enemies.add(Enemy(1000f, 660f, -80f, 40f, 40f))
        enemies.add(Enemy(1600f, 660f, -80f, 40f, 40f))
        enemies.add(Enemy(2500f, 660f, -80f, 40f, 40f))
        enemies.add(Enemy(3300f, 660f, -80f, 40f, 40f))

        for (i in 1..20) {
            coins.add(Coin(Random.nextFloat() * 3500f + 300f, Random.nextFloat() * 150f + 350f))
        }
    }

    fun restartGame() {
        playerX = 100f
        playerY = 500f
        velocityY = 0f
        velocityX = 0f
        gameOver = false
        gameWon = false
        score = 0
        coinCount = 0
        cameraX = 0f
        enemies.forEach { it.isAlive = true; it.x = Random.nextFloat() * 3000f + 600f }
        coins.forEach { it.collected = false }
    }

    LaunchedEffect(gameOver, gameWon, jumpPressed, isWalking, isRunningMax) {
        if (gameOver || gameWon) return@LaunchedEffect
        var lastTime = System.currentTimeMillis()

        while (!gameOver && !gameWon) {
            val currentTime = System.currentTimeMillis()
            val deltaTime = (currentTime - lastTime) / 1000f
            lastTime = currentTime
            val dt = deltaTime.coerceAtMost(0.05f)
            time += dt

            if (jumpPressed && isGrounded) {
                velocityY = -850f
                isGrounded = false
            }
            if (!jumpPressed && velocityY < -300f) {
                velocityY += 2500f * dt
            }

            // Выбор скорости на основе жестов
            val targetSpeed = when {
                isRunningMax -> 600f // Два пальца - бежим
                isWalking -> 250f    // Указательный палец - идем
                else -> 0f           // Кулак / нет руки - стоим
            }

            if (velocityX < targetSpeed) velocityX += 1500f * dt
            else if (velocityX > targetSpeed) velocityX -= 1500f * dt

            velocityY += 2800f * dt
            playerY += velocityY * dt
            playerX += velocityX * dt

            if (playerX > cameraX + 150f) cameraX = playerX - 150f

            isGrounded = false
            val pW = 40f
            val pH = 60f

            for (b in blocks) {
                if (playerX + pW > b.x && playerX < b.x + b.width &&
                    playerY + pH >= b.y && playerY + pH <= b.y + b.height + 20f && velocityY >= 0f) {
                    playerY = b.y - pH
                    velocityY = 0f
                    isGrounded = true
                }
            }

            for (p in pipes) {
                if (playerX + pW > p.x && playerX < p.x + p.width &&
                    playerY + pH >= p.y && playerY + pH <= p.y + p.height + 20f && velocityY >= 0f) {
                    playerY = p.y - pH
                    velocityY = 0f
                    isGrounded = true
                }
                if (playerX + pW > p.x && playerX < p.x + p.width &&
                    playerY + pH > p.y && playerY < p.y + p.height) {
                    playerX = p.x - pW
                }
            }

            for (e in enemies) {
                if (!e.isAlive) continue
                e.x += e.velocityX * dt
                if (e.x < 500f || e.x > 3800f) e.velocityX *= -1
                if (playerX + pW > e.x && playerX < e.x + e.width &&
                    playerY + pH > e.y && playerY < e.y + e.height) {
                    if (velocityY > 0 && playerY + pH < e.y + 20f) {
                        e.isAlive = false
                        velocityY = -500f
                        score += 100
                    } else {
                        gameOver = true
                    }
                }
            }

            for (c in coins) {
                if (!c.collected && abs(playerX - c.x) < 40f && abs(playerY - c.y) < 40f) {
                    c.collected = true
                    score += 200
                    coinCount++
                }
            }

            if (playerY > 1000f) gameOver = true
            if (playerX > 4300f) gameWon = true

            delay(16)
        }
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val skyBrush = Brush.verticalGradient(colors = listOf(Color(0xFF5C94FC), Color(0xFF87CEEB)))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(skyBrush)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    if (keyEvent.key == Key.A) isKey1Pressed = true
                    if (keyEvent.key == Key.L) isKey2Pressed = true
                    true
                } else if (keyEvent.type == KeyEventType.KeyUp) {
                    if (keyEvent.key == Key.A) isKey1Pressed = false
                    if (keyEvent.key == Key.L) isKey2Pressed = false
                    true
                } else false
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { focusRequester.requestFocus() }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 32.dp, start = 32.dp, end = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("МАРИО", color = Color.White, fontSize = 16.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text("${score.toString().padStart(6, '0')}", color = Color.White, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ЖЕСТ", color = Color.White, fontSize = 16.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text(if (useCamera) currentGesture.name else "NONE", color = Color.Yellow, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("МОНЕТЫ", color = Color.White, fontSize = 16.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text("x${coinCount.toString().padStart(2, '0')}", color = Color.White, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val worldHeight = 900f
                    val scaleFactor = size.height / worldHeight
                    val offsetY = size.height - (800f * scaleFactor)

                    scale(scaleX = scaleFactor, scaleY = scaleFactor, pivot = Offset(0f, offsetY)) {
                        translate(left = -cameraX, top = 0f) {
                            drawCircle(Color(0xFF6BB04E), radius = 150f, center = Offset(200f - cameraX * 0.2f, 700f))
                            drawCircle(Color(0xFF6BB04E), radius = 200f, center = Offset(600f - cameraX * 0.2f, 700f))

                            blocks.filter { it.width > 500f }.forEach { b ->
                                drawRect(Color(0xFFC84C0C), topLeft = Offset(b.x, b.y), size = Size(b.width, b.height))
                                drawRect(Color(0xFF3CB043), topLeft = Offset(b.x, b.y), size = Size(b.width, 20f))
                                for (i in 0..(b.width / 15).toInt()) {
                                    val rx = b.x + i * 15f
                                    drawLine(Color(0xFF228B22), start = Offset(rx, b.y), end = Offset(rx + 5f, b.y - 10f), strokeWidth = 3f)
                                }
                                for (i in 0..(b.width / 40).toInt()) {
                                    for (j in 0..(b.height / 20).toInt()) {
                                        drawRect(Color(0xFFE39B4C), topLeft = Offset(b.x + i * 40f + 2f, b.y + 20f + j * 20f + 2f), size = Size(36f, 16f))
                                    }
                                }
                            }

                            blocks.filter { it.width <= 500f }.forEach { b ->
                                drawRoundRect(Color(0xFFF8B860), topLeft = Offset(b.x, b.y), size = Size(b.width, b.height), cornerRadius = CornerRadius(6f, 6f))
                                drawRoundRect(Color(0xFFC87E28), topLeft = Offset(b.x, b.y), size = Size(b.width, b.height), cornerRadius = CornerRadius(6f, 6f), style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
                                if (b.hasCoin) {
                                    drawContext.canvas.nativeCanvas.drawText("?", b.x + 12f, b.y + 32f, android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 28f; typeface = android.graphics.Typeface.DEFAULT_BOLD })
                                }
                            }

                            pipes.forEach { p ->
                                drawRect(Color(0xFF1B5E20), topLeft = Offset(p.x + p.width - 20f, p.y), size = Size(20f, p.height))
                                drawRect(Color(0xFF4CAF50), topLeft = Offset(p.x, p.y), size = Size(p.width, p.height))
                                drawRect(Color(0xFF81C784), topLeft = Offset(p.x + 10f, p.y), size = Size(15f, p.height))
                                drawRoundRect(Color(0xFF4CAF50), topLeft = Offset(p.x - 10f, p.y), size = Size(p.width + 20f, 30f), cornerRadius = CornerRadius(4f, 4f))
                                drawRoundRect(Color(0xFF1B5E20), topLeft = Offset(p.x - 10f, p.y), size = Size(p.width + 20f, 30f), cornerRadius = CornerRadius(4f, 4f), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                            }

                            coins.forEach { c ->
                                if (!c.collected) {
                                    val scaleX = abs(sin(time * 5f))
                                    drawOval(Color(0xFFFFD700), topLeft = Offset(c.x - 10f * scaleX, c.y - 15f), size = Size(20f * scaleX, 30f))
                                }
                            }

                            enemies.forEach { e ->
                                if (e.isAlive) {
                                    drawArc(Color(0xFFC78A4E), startAngle = 180f, sweepAngle = 180f, useCenter = true, topLeft = Offset(e.x, e.y), size = Size(e.width, e.height))
                                    drawRect(Color(0xFFC78A4E), topLeft = Offset(e.x, e.y + 20f), size = Size(e.width, 15f))
                                    drawCircle(Color.White, radius = 7f, center = Offset(e.x + 12f, e.y + 15f))
                                    drawCircle(Color.White, radius = 7f, center = Offset(e.x + 28f, e.y + 15f))
                                    drawCircle(Color.Black, radius = 3f, center = Offset(e.x + 10f, e.y + 15f))
                                    drawCircle(Color.Black, radius = 3f, center = Offset(e.x + 26f, e.y + 15f))
                                    drawRect(Color(0xFF3E2723), topLeft = Offset(e.x + 5f, e.y + 35f), size = Size(10f, 5f))
                                    drawRect(Color(0xFF3E2723), topLeft = Offset(e.x + 25f, e.y + 35f), size = Size(10f, 5f))
                                }
                            }

                            val castleX = 4300f
                            val castleY = 500f
                            drawLine(Color(0xFF8B4513), start = Offset(castleX - 100f, castleY - 200f), end = Offset(castleX - 100f, 700f), strokeWidth = 6f)
                            drawRect(Color(0xFFFFD700), topLeft = Offset(castleX - 110f, castleY - 200f), size = Size(20f, 20f), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
                            val flagPath = androidx.compose.ui.graphics.Path().apply {
                                moveTo(castleX - 100f, castleY - 180f)
                                lineTo(castleX - 40f, castleY - 160f)
                                lineTo(castleX - 100f, castleY - 140f)
                                close()
                            }
                            drawPath(flagPath, Color(0xFFF44336))
                            drawCircle(Color.White, radius = 4f, center = Offset(castleX - 80f, castleY - 160f))

                            val cW = 200f
                            val cH = 200f
                            drawRect(Color(0xFF9E9E9E), topLeft = Offset(castleX, castleY), size = Size(cW, cH))
                            for (i in 0..(cW / 20).toInt()) {
                                for (j in 0..(cH / 20).toInt()) {
                                    drawRect(Color(0xFF757575), topLeft = Offset(castleX + i * 20f + 2f, castleY + j * 20f + 2f), size = Size(16f, 16f))
                                }
                            }
                            drawRect(Color(0xFF9E9E9E), topLeft = Offset(castleX, castleY - 40f), size = Size(cW, 40f))
                            for (i in 0..(cW / 20).toInt()) {
                                drawRect(Color(0xFF757575), topLeft = Offset(castleX + i * 20f + 2f, castleY - 38f), size = Size(16f, 36f))
                            }
                            for (i in 0 until 5) {
                                drawRect(Color(0xFF5C94FC), topLeft = Offset(castleX + i * 40f + 10f, castleY - 40f), size = Size(20f, 20f))
                            }
                            drawRoundRect(Color.Black, topLeft = Offset(castleX + 70f, castleY + 100f), size = Size(60f, 100f), cornerRadius = CornerRadius(30f, 30f))
                            drawRect(Color.Black, topLeft = Offset(castleX + 40f, castleY + 50f), size = Size(15f, 30f))
                            drawRect(Color.Black, topLeft = Offset(castleX + 145f, castleY + 50f), size = Size(15f, 30f))
                            drawRect(Color(0xFF9E9E9E), topLeft = Offset(castleX - 20f, castleY + 40f), size = Size(40f, 160f))
                            drawRect(Color(0xFF9E9E9E), topLeft = Offset(castleX + cW - 20f, castleY + 40f), size = Size(40f, 160f))
                            for (i in 0..1) {
                                for (j in 0..7) {
                                    drawRect(Color(0xFF757575), topLeft = Offset(castleX - 18f + i * 20f, castleY + 42f + j * 20f), size = Size(16f, 16f))
                                    drawRect(Color(0xFF757575), topLeft = Offset(castleX + cW - 18f + i * 20f, castleY + 42f + j * 20f), size = Size(16f, 16f))
                                }
                            }
                            drawRect(Color(0xFF9E9E9E), topLeft = Offset(castleX - 20f, castleY + 20f), size = Size(40f, 20f))
                            drawRect(Color(0xFF9E9E9E), topLeft = Offset(castleX + cW - 20f, castleY + 20f), size = Size(40f, 20f))
                            drawRect(Color(0xFF5C94FC), topLeft = Offset(castleX - 10f, castleY + 20f), size = Size(20f, 20f))
                            drawRect(Color(0xFF5C94FC), topLeft = Offset(castleX + cW - 10f, castleY + 20f), size = Size(20f, 20f))

                            drawBetterMario(playerX, playerY, !isGrounded, isWalking || isRunningMax, true, time)
                        }
                    }
                }

                if (useCamera) {
                    SafeCameraPreview(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 80.dp, end = 16.dp)
                            .size(120.dp, 160.dp)
                            .background(Color.Black, shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                        onGestureDetected = { gesture -> currentGesture = gesture }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick = { }, interactionSource = interactionSource1,
                    modifier = Modifier.weight(1f).height(70.dp).padding(end = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (jumpPressed) Color(0xFF4CAF50) else Color(0xFF2196F3))
                ) {
                    Text("ПРЫЖОК\n(A)", fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = { }, interactionSource = interactionSource2,
                    modifier = Modifier.weight(1f).height(70.dp).padding(start = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (isWalking || isRunningMax) Color(0xFF4CAF50) else Color(0xFFFF9800))
                ) {
                    Text("ИДТИ/БЕЖАТЬ\n(L)", fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            Button(
                onClick = onExit,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).height(40.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
            ) {
                Text("В МЕНЮ", fontSize = 14.sp)
            }

            if (gameOver) {
                Button(
                    onClick = { restartGame() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
                ) {
                    Text("GAME OVER - ПОПРОБОВАТЬ СНОВА", fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            if (gameWon) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("УРОВЕНЬ ПРОЙДЕН!", color = Color(0xFF4CAF50), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Button(
                        onClick = { restartGame() },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
                    ) {
                        Text("ИГРАТЬ СНОВА", fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// --- КОМПОНЕНТ КАМЕРЫ ---
@Composable
fun SafeCameraPreview(modifier: Modifier = Modifier, onGestureDetected: (HandGesture) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    val modelExists = remember {
        try {
            context.assets.open("hand_landmarker.task").close()
            true
        } catch (e: Exception) {
            DebugLogger.error("Файл hand_landmarker.task НЕ НАЙДЕН в assets!")
            false
        }
    }

    if (!modelExists) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("Нет модели", color = Color.Red, fontSize = 10.sp)
        }
        return
    }

    val handLandmarker = remember {
        try {
            DebugLogger.log("Загрузка MediaPipe...")
            val baseOptions = BaseOptions.builder().setModelAssetPath("hand_landmarker.task").build()
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinHandDetectionConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setNumHands(1)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result, _ ->
                    val gesture = GestureAnalyzer.analyze(result)
                    onGestureDetected(gesture)
                }
                .setErrorListener { error ->
                    DebugLogger.error("MediaPipe Error: ${error.message}")
                }
                .build()
            val landmarker = HandLandmarker.createFromOptions(context, options)
            DebugLogger.log("MediaPipe загружен!")
            landmarker
        } catch (e: Exception) {
            DebugLogger.error("Ошибка MediaPipe: ${e.message}")
            null
        }
    }

    if (handLandmarker == null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("Ошибка", color = Color.Red, fontSize = 10.sp)
        }
        return
    }

    DisposableEffect(Unit) {
        onDispose {
            try { handLandmarker.close() } catch (e: Exception) {}
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            val cameraExecutor = Executors.newSingleThreadExecutor()

            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    val imageAnalysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()

                    imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        try {
                            val bitmap = imageProxy.toBitmap()
                            if (bitmap != null) {
                                val mpImage = BitmapImageBuilder(bitmap).build()
                                handLandmarker.detectAsync(mpImage, System.currentTimeMillis())
                            }
                        } catch (e: Exception) {} finally { imageProxy.close() }
                    }

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalysis)
                } catch (e: Exception) {
                    DebugLogger.error("Ошибка CameraX: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
        modifier = modifier
    )
}

fun ImageProxy.toBitmap(): Bitmap? {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    val matrix = Matrix()
    matrix.postRotate(imageInfo.rotationDegrees.toFloat())
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}

// --- ОТРИСОВКА МАРИО ---
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBetterMario(
    x: Float, y: Float, isJumping: Boolean, isRunning: Boolean, facingRight: Boolean, time: Float
) {
    val red = Color(0xFFD32F2F)
    val blue = Color(0xFF1C3F8A)
    val skin = Color(0xFFFFB27F)
    val brown = Color(0xFF5C3A21)
    val yellow = Color(0xFFFFD700)
    val white = Color.White

    val w = 40f
    val h = 60f
    val cx = x + w / 2
    val bottomY = y + h

    val speedMultiplier = if (isRunning) 25f else 15f
    val step = if (!isJumping) sin(time * speedMultiplier) * 8f else 0f
    val armSwing = if (!isJumping) sin(time * speedMultiplier) * 5f else 0f

    if (!isJumping) {
        drawRect(blue, topLeft = Offset(cx - 14f, bottomY - 20f), size = Size(10f, 15f + step))
        drawRect(brown, topLeft = Offset(cx - 16f, bottomY - 5f + step), size = Size(14f, 5f))
        drawRect(blue, topLeft = Offset(cx + 4f, bottomY - 20f), size = Size(10f, 15f - step))
        drawRect(brown, topLeft = Offset(cx + 2f, bottomY - 5f - step), size = Size(14f, 5f))
    } else {
        drawRect(blue, topLeft = Offset(cx - 14f, bottomY - 15f), size = Size(10f, 10f))
        drawRect(brown, topLeft = Offset(cx - 16f, bottomY - 8f), size = Size(14f, 8f))
        drawRect(blue, topLeft = Offset(cx + 4f, bottomY - 15f), size = Size(10f, 10f))
        drawRect(brown, topLeft = Offset(cx + 2f, bottomY - 8f), size = Size(14f, 8f))
    }

    drawRect(blue, topLeft = Offset(cx - 12f, bottomY - 35f), size = Size(24f, 20f))
    drawRect(red, topLeft = Offset(cx - 12f, bottomY - 45f), size = Size(24f, 10f))
    drawRect(blue, topLeft = Offset(cx - 10f, bottomY - 45f), size = Size(6f, 10f))
    drawRect(blue, topLeft = Offset(cx + 4f, bottomY - 45f), size = Size(6f, 10f))
    drawCircle(yellow, radius = 3f, center = Offset(cx - 7f, bottomY - 33f))
    drawCircle(yellow, radius = 3f, center = Offset(cx + 7f, bottomY - 33f))

    if (isJumping) {
        drawRect(red, topLeft = Offset(cx - 20f, bottomY - 45f), size = Size(8f, 15f))
        drawCircle(white, radius = 5f, center = Offset(cx - 16f, bottomY - 45f))
        drawRect(red, topLeft = Offset(cx + 12f, bottomY - 45f), size = Size(8f, 15f))
        drawCircle(white, radius = 5f, center = Offset(cx + 16f, bottomY - 45f))
    } else {
        drawRect(red, topLeft = Offset(cx - 20f, bottomY - 40f - armSwing), size = Size(8f, 15f))
        drawCircle(white, radius = 5f, center = Offset(cx - 16f, bottomY - 25f - armSwing))
        drawRect(red, topLeft = Offset(cx + 12f, bottomY - 40f + armSwing), size = Size(8f, 15f))
        drawCircle(white, radius = 5f, center = Offset(cx + 16f, bottomY - 25f + armSwing))
    }

    val headY = bottomY - 65f
    drawRect(skin, topLeft = Offset(cx - 12f, headY + 10f), size = Size(24f, 15f))
    drawRect(brown, topLeft = Offset(cx - 15f, headY + 15f), size = Size(5f, 8f))
    drawRect(brown, topLeft = Offset(cx + 10f, headY + 15f), size = Size(5f, 8f))
    drawRect(white, topLeft = Offset(cx + 2f, headY + 12f), size = Size(4f, 6f))
    drawRect(Color.Black, topLeft = Offset(cx + 4f, headY + 14f), size = Size(2f, 4f))
    drawRect(skin, topLeft = Offset(cx + 12f, headY + 18f), size = Size(8f, 6f))
    drawRect(Color.Black, topLeft = Offset(cx - 2f, headY + 22f), size = Size(18f, 4f))
    drawRect(Color.Black, topLeft = Offset(cx - 6f, headY + 20f), size = Size(8f, 4f))

    drawRoundRect(red, topLeft = Offset(cx - 15f, headY), size = Size(30f, 12f), cornerRadius = CornerRadius(6f, 6f))
    drawRoundRect(red, topLeft = Offset(cx + 5f, headY + 8f), size = Size(16f, 5f), cornerRadius = CornerRadius(2f, 2f))
    drawCircle(Color.White, radius = 4f, center = Offset(cx - 2f, headY + 5f))
    drawRect(Color(0x33000000), topLeft = Offset(cx - 12f, headY + 10f), size = Size(24f, 3f))
}
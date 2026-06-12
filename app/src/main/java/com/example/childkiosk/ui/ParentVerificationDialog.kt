package com.example.childkiosk.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.childkiosk.data.SystemConfigEntity
import com.example.childkiosk.util.HashUtils

data class MathQuestion(val expression: String, val answer: Int)

fun generateMathQuestion(): MathQuestion {
    val type = (0..2).random() // 0: 加法, 1: 减法, 2: 乘法
    return when (type) {
        0 -> {
            val a = (1..89).random()
            val b = (1..(100 - a)).random()
            MathQuestion("$a + $b = ?", a + b)
        }
        1 -> {
            val a = (10..100).random()
            val b = (1..a).random()
            MathQuestion("$a - $b = ?", a - b)
        }
        else -> {
            val a = (2..9).random()
            val b = (2..9).random()
            MathQuestion("$a × $b = ?", a * b)
        }
    }
}

@Composable
fun ParentVerificationDialog(
    config: SystemConfigEntity?,
    onDismiss: () -> Unit,
    onVerified: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val isPinMode = config?.verificationMode == "PIN" && !config.pinHash.isNullOrEmpty()
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                if (isPinMode) {
                    PinVerificationView(
                        targetHash = config?.pinHash ?: "",
                        onDismiss = onDismiss,
                        onSuccess = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onVerified()
                        }
                    )
                } else {
                    MathVerificationView(
                        onDismiss = onDismiss,
                        onSuccess = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onVerified()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun QButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    colors: ButtonColors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
    content: @Composable RowScope.() -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.9f else 1.0f, label = "scale")
    val haptic = LocalHapticFeedback.current

    Button(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        interactionSource = interactionSource,
        modifier = modifier
            .scale(scale)
            .sizeIn(minWidth = 72.dp, minHeight = 72.dp), // 扩展点击区域满足 72dp+ 触控规范
        shape = RoundedCornerShape(16.dp),
        colors = colors,
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MathVerificationView(
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    val mathQuestion = remember { mutableStateOf(generateMathQuestion()) }
    var inputAnswer by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .padding(24.dp)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "家长验证",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Text(
            text = "请解开以下算术题以证明您是家长：",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Text(
            text = mathQuestion.value.expression,
            fontSize = 32.sp,
            fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        OutlinedTextField(
            value = inputAnswer,
            onValueChange = {
                inputAnswer = it
                showError = false
            },
            placeholder = { Text("输入计算答案") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            shape = RoundedCornerShape(12.dp),
            isError = showError,
            singleLine = true
        )

        if (showError) {
            Text(
                text = "答案错误，请再试一次！",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        } else {
            Spacer(modifier = Modifier.height(16.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            QButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
            ) {
                Text("取消", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }

            QButton(
                onClick = {
                    val ans = inputAnswer.toIntOrNull()
                    if (ans == mathQuestion.value.answer) {
                        onSuccess()
                    } else {
                        showError = true
                        inputAnswer = ""
                        mathQuestion.value = generateMathQuestion()
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("确认", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun PinVerificationView(
    targetHash: String,
    onDismiss: () -> Unit,
    onSuccess: () -> Unit
) {
    var enteredPin by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .padding(24.dp)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "输入家长密码",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // 显示输入的圆点
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(bottom = 24.dp)
        ) {
            repeat(4) { index ->
                val filled = index < enteredPin.length
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(
                            if (showError) MaterialTheme.colorScheme.error
                            else if (filled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                )
            }
        }

        if (showError) {
            Text(
                text = "密码错误，请重新输入",
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )
        } else {
            Spacer(modifier = Modifier.height(16.dp))
        }

        // 简易数字键盘 (0-9)
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val keys = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("清除", "0", "删除")
            )

            keys.forEach { row ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    row.forEach { key ->
                        val haptic = LocalHapticFeedback.current
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp) // 键盘网格按钮高度
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    showError = false
                                    when (key) {
                                        "清除" -> enteredPin = ""
                                        "删除" -> if (enteredPin.isNotEmpty()) enteredPin = enteredPin.dropLast(1)
                                        else -> {
                                            if (enteredPin.length < 4) {
                                                enteredPin += key
                                                if (enteredPin.length == 4) {
                                                    // 校验密码
                                                    val hash = HashUtils.sha256(enteredPin)
                                                    if (hash == targetHash) {
                                                        onSuccess()
                                                    } else {
                                                        showError = true
                                                        enteredPin = ""
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                        ) {
                            Text(
                                text = key,
                                fontSize = if (key.length > 1) 14.sp else 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        QButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
        ) {
            Text("取消", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

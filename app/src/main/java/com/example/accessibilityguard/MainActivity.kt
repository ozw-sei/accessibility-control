package com.example.accessibilityguard

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初回起動時に Watchdog と Device Owner 保護をセットアップ
        WatchdogWorker.schedule(this)
        if (DeviceOwnerHelper.isDeviceOwner(this)) {
            DeviceOwnerHelper.blockUninstall(this)
            DeviceOwnerHelper.restrictAccessibilityServices(this)
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF90CAF9),
                    surface = Color(0xFF1A1A2E),
                    background = Color(0xFF0F0F23),
                    onSurface = Color(0xFFE0E0E0),
                    onBackground = Color(0xFFE0E0E0),
                )
            ) {
                GuardApp()
            }
        }
    }
}

// ----- Compose UI -----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GuardApp() {
    val context = LocalContext.current
    val checker = remember { ConditionChecker(context) }

    // 5秒ごとに状態を更新
    val tick by flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(5000)
        }
    }.collectAsStateWithLifecycle(initialValue = 0L)

    // 現在の状態（tick に依存して再評価）
    val isAllowed = remember(tick) { checker.isAllowed() }
    val isCharging = remember(tick) { checker.isCharging() }
    val isDeviceOwner = remember(tick) { DeviceOwnerHelper.isDeviceOwner(context) }
    val isA11yEnabled = remember(tick) { isAccessibilityServiceEnabled(context) }
    val currentTime = remember(tick) {
        LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
    }

    // 設定値
    var startHour by remember { mutableIntStateOf(checker.getStartHour()) }
    var endHour by remember { mutableIntStateOf(checker.getEndHour()) }
    var requireCharging by remember { mutableStateOf(checker.getRequireCharging()) }
    var guardEnabled by remember { mutableStateOf(checker.isGuardEnabled) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Accessibility Guard") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF16213E),
                    titleContentColor = Color(0xFFE0E0E0),
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---- ステータスカード ----
            StatusCard(
                currentTime = currentTime,
                isAllowed = isAllowed,
                isCharging = isCharging,
                isDeviceOwner = isDeviceOwner,
                isA11yEnabled = isA11yEnabled,
                guardEnabled = guardEnabled,
            )

            // ---- 設定カード（許可ウィンドウ内のみ編集可） ----
            SettingsCard(
                isAllowed = isAllowed,
                startHour = startHour,
                endHour = endHour,
                requireCharging = requireCharging,
                guardEnabled = guardEnabled,
                onStartHourChange = { startHour = it },
                onEndHourChange = { endHour = it },
                onRequireChargingChange = {
                    requireCharging = it
                    checker.setRequireCharging(it)
                },
                onGuardEnabledChange = {
                    guardEnabled = it
                    checker.setGuardEnabled(it)
                },
                onSaveWindow = {
                    checker.setWindow(startHour, 0, endHour, 0)
                    Toast.makeText(context, "保存しました", Toast.LENGTH_SHORT).show()
                }
            )

            // ---- セットアップカード ----
            SetupCard(
                context = context,
                isDeviceOwner = isDeviceOwner,
                isA11yEnabled = isA11yEnabled,
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun StatusCard(
    currentTime: String,
    isAllowed: Boolean,
    isCharging: Boolean,
    isDeviceOwner: Boolean,
    isA11yEnabled: Boolean,
    guardEnabled: Boolean,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF16213E)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("現在の状態", fontSize = 18.sp, fontWeight = FontWeight.Bold)

            // ロック状態の大きな表示
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (!guardEnabled) Color(0xFF424242)
                        else if (isAllowed) Color(0xFF1B5E20)
                        else Color(0xFFB71C1C)
                    )
                    .padding(20.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (!guardEnabled) "⏸ ガード無効"
                           else if (isAllowed) "🔓 アクセス許可中"
                           else "🔒 ロック中",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }

            Text("現在時刻: $currentTime")

            StatusRow("Device Owner", isDeviceOwner)
            StatusRow("AccessibilityService", isA11yEnabled)
            StatusRow("充電中", isCharging)
        }
    }
}

@Composable
fun StatusRow(label: String, active: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (active) "✅" else "❌",
            fontSize = 16.sp,
        )
        Text(
            text = "$label: ${if (active) "有効" else "無効"}",
            fontSize = 14.sp,
        )
    }
}

@Composable
fun SettingsCard(
    isAllowed: Boolean,
    startHour: Int,
    endHour: Int,
    requireCharging: Boolean,
    guardEnabled: Boolean,
    onStartHourChange: (Int) -> Unit,
    onEndHourChange: (Int) -> Unit,
    onRequireChargingChange: (Boolean) -> Unit,
    onGuardEnabledChange: (Boolean) -> Unit,
    onSaveWindow: () -> Unit,
) {
    val editable = isAllowed // 許可ウィンドウ内のみ編集可

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF16213E)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("設定", fontSize = 18.sp, fontWeight = FontWeight.Bold)

            if (!editable) {
                Text(
                    "⚠ 設定変更は許可ウィンドウ内でのみ可能です",
                    fontSize = 12.sp,
                    color = Color(0xFFFFAB91),
                )
            }

            // ガード有効/無効
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("ガード有効", modifier = Modifier.weight(1f))
                Switch(
                    checked = guardEnabled,
                    onCheckedChange = onGuardEnabledChange,
                    enabled = editable,
                )
            }

            Divider(color = Color(0xFF2A2A4A))

            // 許可開始時刻
            Text("許可開始: ${"%02d".format(startHour)}:00", fontSize = 14.sp)
            Slider(
                value = startHour.toFloat(),
                onValueChange = { onStartHourChange(it.toInt()) },
                valueRange = 0f..23f,
                steps = 22,
                enabled = editable,
            )

            // 許可終了時刻
            Text("許可終了: ${"%02d".format(endHour)}:00", fontSize = 14.sp)
            Slider(
                value = endHour.toFloat(),
                onValueChange = { onEndHourChange(it.toInt()) },
                valueRange = 1f..24f,
                steps = 22,
                enabled = editable,
            )

            // 充電必須
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("充電中のみ許可", modifier = Modifier.weight(1f))
                Switch(
                    checked = requireCharging,
                    onCheckedChange = onRequireChargingChange,
                    enabled = editable,
                )
            }

            // 保存ボタン
            Button(
                onClick = onSaveWindow,
                enabled = editable,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("保存")
            }
        }
    }
}

@Composable
fun SetupCard(
    context: Context,
    isDeviceOwner: Boolean,
    isA11yEnabled: Boolean,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF16213E)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("セットアップ", fontSize = 18.sp, fontWeight = FontWeight.Bold)

            if (!isDeviceOwner) {
                Text(
                    "⚠ Device Owner 未設定\n" +
                        "PCから以下を実行:\n" +
                        "adb shell dpm set-device-owner com.example.accessibilityguard/.GuardAdminReceiver",
                    fontSize = 12.sp,
                    color = Color(0xFFFFAB91),
                )
            } else {
                Text("✅ Device Owner 設定済み", fontSize = 14.sp, color = Color(0xFF81C784))
            }

            if (!isA11yEnabled) {
                Button(
                    onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("ユーザー補助設定を開く")
                }
                Text(
                    "「Accessibility Guard」を有効にしてください",
                    fontSize = 12.sp,
                    color = Color(0xFFFFAB91),
                )
            } else {
                Text("✅ AccessibilityService 有効", fontSize = 14.sp, color = Color(0xFF81C784))
            }

            Divider(color = Color(0xFF2A2A4A))

            // デバッグ: Device Owner 解除
            if (isDeviceOwner) {
                var showConfirm by remember { mutableStateOf(false) }

                if (showConfirm) {
                    Text(
                        "⚠ 本当に Device Owner を解除しますか？\n保護が無効になります。",
                        fontSize = 12.sp,
                        color = Color(0xFFEF5350),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            DeviceOwnerHelper.removeDeviceOwner(context)
                            showConfirm = false
                            Toast.makeText(context, "Device Owner を解除しました", Toast.LENGTH_LONG).show()
                        }) {
                            Text("解除する", color = Color(0xFFEF5350))
                        }
                        OutlinedButton(onClick = { showConfirm = false }) {
                            Text("キャンセル")
                        }
                    }
                } else {
                    TextButton(onClick = { showConfirm = true }) {
                        Text("Device Owner を解除 (デバッグ用)", fontSize = 12.sp, color = Color(0xFF757575))
                    }
                }
            }
        }
    }
}

// ----- Utility -----

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
    val targetComponent = ComponentName(context, GuardAccessibilityService::class.java)
    return enabledServices.any {
        it.resolveInfo.serviceInfo.let { si ->
            ComponentName(si.packageName, si.name) == targetComponent
        }
    }
}

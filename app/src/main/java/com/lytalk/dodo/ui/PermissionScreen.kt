package com.lytalk.dodo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lytalk.dodo.miui.MiuiIntents
import com.lytalk.dodo.miui.SystemPermissions
import com.lytalk.dodo.schedule.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val accessibility = remember(tick) { SystemPermissions.accessibilityEnabled(context) }
    val exact = remember(tick) { SystemPermissions.exactAlarmAllowed(context) }
    val overlay = remember(tick) { SystemPermissions.overlayAllowed(context) }
    val battery = remember(tick) { SystemPermissions.ignoringBattery(context) }
    var rootGranted by remember { mutableStateOf<Boolean?>(null) }
    var rootBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun requestRoot() {
        if (rootBusy) return
        rootBusy = true
        scope.launch {
            val granted = withContext(Dispatchers.IO) { RootShell.hasRoot() }
            rootGranted = granted
            rootBusy = false
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("让定时能跑起来") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "MIUI 13 会在自启动关闭、省电限制或锁屏清理时拦住到点拉起。下面几项需要在系统里打开。本应用不能替你打开这些开关。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                PermCard(
                    title = "无障碍服务",
                    detail = "用来读取界面文字和控件 id，并执行点击、输入、返回。",
                    status = if (accessibility) "已开启" else "未开启",
                    action = "打开无障碍设置",
                    onClick = { SystemPermissions.openAccessibility(context) },
                )
            }
            item {
                PermCard(
                    title = "精确闹钟",
                    detail = "到点由系统唤醒本应用并自动执行，不用去时钟里操作。状态栏若出现闹钟图标，只表示有定时在等待。",
                    status = if (exact) "已允许" else "未允许",
                    action = "打开精确闹钟设置",
                    onClick = { SystemPermissions.openExactAlarm(context) },
                )
            }
            item {
                PermCard(
                    title = "显示在其他应用上层",
                    detail = "从后台打开你指定的应用时需要这项权限。",
                    status = if (overlay) "已允许" else "未允许",
                    action = "打开悬浮窗设置",
                    onClick = { SystemPermissions.openOverlay(context) },
                )
            }
            item {
                PermCard(
                    title = "Root 权限",
                    detail = "关闭应用会直接执行强行停止，不再打开应用详情。点申请后在 Root 管理器里允许本应用。",
                    status = when {
                        rootBusy -> "正在申请"
                        rootGranted == true -> "已获得"
                        rootGranted == false -> "未获得"
                        else -> "未申请"
                    },
                    action = if (rootBusy) "正在申请" else "申请 Root",
                    onClick = { requestRoot() },
                )
            }
            item {
                PermCard(
                    title = "自启动",
                    detail = "请到系统里确认：允许本应用自启动。MIUI 不提供查询，这里无法显示开关状态。关闭后，开机或清理内存后收不到到点执行。",
                    status = "请到系统里确认",
                    action = "打开自启动列表",
                    onClick = { MiuiIntents.openAutostart(context) },
                )
            }
            item {
                PermCard(
                    title = "后台弹出界面",
                    detail = "请到系统里确认：允许后台弹出界面。打开其他应用时还需要关联启动。这两项都在 MIUI 的权限编辑页。",
                    status = "请到系统里确认",
                    action = "打开权限编辑",
                    onClick = { MiuiIntents.openPermissionEditor(context) },
                )
            }
            item {
                PermCard(
                    title = "省电策略",
                    detail = "把本应用设为无限制。系统电池优化${if (battery) "已忽略" else "仍可能限制本应用"}。",
                    status = if (battery) "电池优化已忽略" else "请设为无限制",
                    action = "打开省电设置",
                    onClick = { MiuiIntents.openBattery(context) },
                )
            }
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("最近任务里锁定", style = MaterialTheme.typography.titleMedium)
                        Text("打开最近任务，按住本应用卡片并锁定。这样上滑清理时不会把嘟嘟划掉。")
                        Text("不要在手机管家或安全中心里对本应用使用「结束进程」或「强行停止」。那样做之后，定时和无障碍都会失效，需要再手动打开一次本应用。")
                    }
                }
            }
        }
    }
}

@Composable
private fun PermCard(
    title: String,
    detail: String,
    status: String,
    action: String,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(status, color = MaterialTheme.colorScheme.secondary)
            }
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onClick) { Text(action) }
        }
    }
}

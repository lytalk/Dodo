package com.lytalk.dodo.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.lytalk.dodo.a11y.AutoAccessibilityService
import com.lytalk.dodo.a11y.InspectRow
import com.lytalk.dodo.a11y.beginNodePick
import com.lytalk.dodo.miui.SystemPermissions
import androidx.compose.material3.Button

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InspectScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var rows by remember { mutableStateOf<List<InspectRow>>(emptyList()) }
    var message by remember { mutableStateOf("打开要查看的应用后，回到这里点刷新。") }

    fun refresh() {
        if (!SystemPermissions.accessibilityEnabled(context) || AutoAccessibilityService.instance == null) {
            rows = emptyList()
            message = "无障碍服务还没连上。请先在权限页打开，再切回这个页面。"
            return
        }
        val dumped = AutoAccessibilityService.instance?.inspect().orEmpty()
        rows = dumped
        val pkg = dumped.firstOrNull()?.packageName.orEmpty()
        message = if (dumped.isEmpty()) {
            "当前屏幕没有读到文字或控件 id。"
        } else {
            "当前窗口：$pkg。点文字或 id 可复制到剪贴板，再填进步骤。"
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("检查当前屏幕") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                },
            )
        },
    ) { padding ->
        LayerScrollColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "控件 id 是应用给图标或按钮起的内部名字，屏幕上不一定有字。点下面的按钮后，回到目标界面再点一下那个图标，就能看到它的 id。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = {
                        val message = beginNodePick(context)
                        if (message != null) Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }) { Text("点选控件") }
                }
            rows.forEach { row ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (row.text.isNotBlank()) {
                            Text(row.text, style = MaterialTheme.typography.titleMedium)
                        }
                        if (row.description.isNotBlank() && row.description != row.text) {
                            Text("描述：${row.description}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!row.viewId.isNullOrBlank()) {
                            Text(row.viewId, style = MaterialTheme.typography.bodySmall)
                        }
                        Row {
                            if (row.text.isNotBlank()) {
                                TextButton(onClick = { copy(context, row.text, "已复制文字") }) { Text("复制文字") }
                            } else if (row.description.isNotBlank()) {
                                TextButton(onClick = { copy(context, row.description, "已复制描述") }) { Text("复制描述") }
                            }
                            if (!row.viewId.isNullOrBlank()) {
                                TextButton(onClick = { copy(context, row.viewId, "已复制控件 id") }) { Text("复制 id") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun copy(context: Context, value: String, toast: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText("dodo", value))
    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
}

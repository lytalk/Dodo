package com.lytalk.dodo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lytalk.dodo.data.TaskEntity
import com.lytalk.dodo.data.nextRunLabel
import com.lytalk.dodo.data.scheduleLabel
import com.lytalk.dodo.miui.SystemPermissions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    onAdd: () -> Unit,
    onEdit: (Long) -> Unit,
    onPermissions: () -> Unit,
    onInspect: () -> Unit,
    viewModel: TaskListViewModel = viewModel(),
) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.ensureGuard() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("嘟嘟") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    IconButton(onClick = onInspect) {
                        Icon(Icons.Filled.Search, contentDescription = "检查当前屏幕")
                    }
                    IconButton(onClick = onPermissions) {
                        Icon(Icons.Filled.Settings, contentDescription = "权限")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = "新建任务")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!SystemPermissions.accessibilityEnabled(context)) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("还没打开无障碍服务", style = MaterialTheme.typography.titleMedium)
                            Text("到点执行点击和输入需要这项权限。定时本身会登记，但步骤在打开之前不会动界面。")
                            TextButton(onClick = onPermissions) { Text("去打开") }
                        }
                    }
                }
            }
            if (tasks.isEmpty()) {
                item {
                    Text(
                        "还没有任务。点右下角新建，选择应用并写下要点击的文字或控件 id，到点会自动执行。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(tasks, key = { it.id }) { task ->
                TaskCard(
                    task = task,
                    onEdit = { onEdit(task.id) },
                    onRun = { viewModel.runNow(task) },
                    onCopy = { viewModel.copy(task) },
                    onEnabled = { viewModel.setEnabled(task, it) },
                )
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: TaskEntity,
    onEdit: () -> Unit,
    onRun: () -> Unit,
    onCopy: () -> Unit,
    onEnabled: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onEdit) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(task.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(scheduleLabel(task), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(nextRunLabel(task), color = MaterialTheme.colorScheme.secondary)
                }
                Switch(checked = task.enabled, onCheckedChange = onEnabled)
            }
            task.lastResult?.takeIf { it.isNotBlank() }?.let { result ->
                Text("上次：$result", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRun) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text("立即运行")
                }
                TextButton(onClick = onCopy) { Text("复制") }
                TextButton(onClick = onEdit) { Text("编辑") }
            }
        }
    }
}

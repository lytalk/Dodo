package com.lytalk.dodo.ui

import android.app.Application
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.lytalk.dodo.a11y.PickEvents
import com.lytalk.dodo.a11y.UnlockEvents
import com.lytalk.dodo.a11y.beginNodePick
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.lytalk.dodo.data.CleanupTypes
import com.lytalk.dodo.data.ScheduleCalculator
import com.lytalk.dodo.data.StepPayloads
import com.lytalk.dodo.data.StepTypes
import com.lytalk.dodo.data.dayBit
import com.lytalk.dodo.data.formatHm
import com.lytalk.dodo.data.nextRunLabel
import com.lytalk.dodo.data.scheduleLabel
import com.lytalk.dodo.data.stepSummary
import kotlin.math.roundToInt
import java.util.Calendar
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskEditRoute(
    navController: NavController,
    taskId: Long,
) {
    val app = LocalContext.current.applicationContext as Application
    val viewModel: TaskEditViewModel = viewModel(factory = TaskEditViewModel.Factory(app, taskId))
    val handle = navController.currentBackStackEntry?.savedStateHandle
    var seenToken by remember { mutableStateOf(0L) }
    LaunchedEffect(handle) {
        handle?.getStateFlow("pickCancelled", 0L)?.collect { token ->
            if (token != 0L) viewModel.cancelEdit()
        }
    }
    LaunchedEffect(handle) {
        handle?.getStateFlow("pickToken", 0L)?.collect { token ->
            if (token != 0L && token != seenToken) {
                seenToken = token
                val packageName = handle.get<String>("pickedPackage").orEmpty()
                if (packageName.isNotEmpty()) {
                    val label = handle.get<String>("pickedLabel") ?: packageName
                    if (handle.get<String>("pickerPurpose") == "close" || handle.get<String>("pickerPurpose") == "cleanup-close") {
                        if (handle.get<String>("pickerPurpose") == "cleanup-close") viewModel.editingCleanup = true
                        viewModel.addClose(packageName, label)
                    } else {
                        if (handle.get<String>("pickerPurpose") == "cleanup-launch") viewModel.editingCleanup = true
                        viewModel.addLaunch(packageName, label)
                    }
                    handle["pickerPurpose"] = ""
                }
            }
        }
    }
    DisposableEffect(viewModel) {
        val stopPick = PickEvents.listen { picked ->
            viewModel.addPicked(picked.viewId, picked.text, picked.description, picked.xFraction, picked.yFraction)
        }
        val stopUnlock = UnlockEvents.listen { payload ->
            viewModel.addStep(StepTypes.UNLOCK, payload)
        }
        onDispose {
            stopPick()
            stopUnlock()
        }
    }
    LaunchedEffect(viewModel) {
        PickEvents.consumePending()?.let { picked ->
            viewModel.addPicked(picked.viewId, picked.text, picked.description, picked.xFraction, picked.yFraction)
        }
        UnlockEvents.consumePending()?.let { payload ->
            viewModel.addStep(StepTypes.UNLOCK, payload)
        }
    }
    TaskEditScreen(
        viewModel = viewModel,
        onBack = { navController.popBackStack() },
        onPickApp = { purpose ->
            navController.currentBackStackEntry?.savedStateHandle?.set("pickerPurpose", purpose)
            navController.navigate("picker")
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun TaskEditScreen(
    viewModel: TaskEditViewModel,
    onBack: () -> Unit,
    onPickApp: (purpose: String) -> Unit,
) {
    var dialog by remember { mutableStateOf<EditorDialog?>(null) }
    val context = LocalContext.current
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(if (viewModel.loaded && viewModel.name.isNotBlank()) viewModel.name else "编辑任务") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { viewModel.duplicate() },
                        enabled = viewModel.loaded && !viewModel.saving,
                    ) { Text("复制") }
                    IconButton(onClick = { dialog = EditorDialog.Delete }) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除任务")
                    }
                },
            )
        },
    ) { padding ->
        if (!viewModel.loaded) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }
        val schedulePreview = remember(
            viewModel.scheduleType,
            viewModel.hour,
            viewModel.minute,
            viewModel.daysMask,
            viewModel.onceAtMillis,
            viewModel.enabled,
            viewModel.nextRunAt,
        ) {
            val entity = com.lytalk.dodo.data.TaskEntity(
                name = "",
                enabled = true,
                scheduleType = viewModel.scheduleType,
                hour = viewModel.hour,
                minute = viewModel.minute,
                daysMask = viewModel.daysMask,
                onceAtMillis = viewModel.onceAtMillis,
                lastRunAt = null,
                lastResult = null,
                nextRunAt = viewModel.nextRunAt,
            )
            scheduleLabel(entity.copy(enabled = viewModel.enabled)) to nextRunLabel(entity)
        }
        val offset = remember { mutableFloatStateOf(0f) }
        var viewport by remember { mutableIntStateOf(0) }
        var contentHeight by remember { mutableIntStateOf(0) }
        val maxOffset = (contentHeight - viewport).coerceAtLeast(0).toFloat()
        val scrollState = rememberScrollableState { delta ->
            val previous = offset.floatValue
            val next = (previous + delta).coerceIn(-maxOffset, 0f)
            offset.floatValue = next
            next - previous
        }
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .onSizeChanged { viewport = it.height }
                .clipToBounds()
                .scrollable(
                    state = scrollState,
                    orientation = Orientation.Vertical,
                    overscrollEffect = null,
                    bringIntoViewSpec = NoChaseBringIntoView,
                ),
        ) {
        MovingLayer(offset) {
        Column(
            Modifier
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .onSizeChanged { contentHeight = it.height }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = viewModel.name,
                onValueChange = { viewModel.name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("任务名称") },
                singleLine = true,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("启用后到点自动执行", modifier = Modifier.weight(1f))
                Switch(checked = viewModel.enabled, onCheckedChange = { viewModel.enabled = it })
            }
            Text("时间", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = viewModel.scheduleType == ScheduleCalculator.DAILY,
                    onClick = { viewModel.chooseSchedule(ScheduleCalculator.DAILY) },
                    label = { Text("每天") },
                )
                FilterChip(
                    selected = viewModel.scheduleType == ScheduleCalculator.WEEKLY,
                    onClick = { viewModel.chooseSchedule(ScheduleCalculator.WEEKLY) },
                    label = { Text("每周") },
                )
                FilterChip(
                    selected = viewModel.scheduleType == ScheduleCalculator.ONCE,
                    onClick = { viewModel.chooseSchedule(ScheduleCalculator.ONCE) },
                    label = { Text("执行一次") },
                )
                FilterChip(
                    selected = viewModel.scheduleType == ScheduleCalculator.INTERVAL_MINUTE,
                    onClick = { viewModel.chooseSchedule(ScheduleCalculator.INTERVAL_MINUTE) },
                    label = { Text("每分钟") },
                )
                FilterChip(
                    selected = viewModel.scheduleType == ScheduleCalculator.INTERVAL_HOUR,
                    onClick = { viewModel.chooseSchedule(ScheduleCalculator.INTERVAL_HOUR) },
                    label = { Text("每小时") },
                )
            }
            if (viewModel.scheduleType == ScheduleCalculator.INTERVAL_MINUTE) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 5, 10, 15, 30).forEach { value ->
                        FilterChip(
                            selected = viewModel.minute == value,
                            onClick = {
                                viewModel.minute = value
                                viewModel.nextRunAt = null
                            },
                            label = { Text("$value 分钟") },
                        )
                    }
                }
            }
            if (viewModel.scheduleType == ScheduleCalculator.INTERVAL_HOUR) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 2, 3, 6, 12).forEach { value ->
                        FilterChip(
                            selected = viewModel.hour == value,
                            onClick = {
                                viewModel.hour = value
                                viewModel.nextRunAt = null
                            },
                            label = { Text("$value 小时") },
                        )
                    }
                }
            }
            if (viewModel.scheduleType == ScheduleCalculator.WEEKLY) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    weekDays.forEach { (day, label) ->
                        FilterChip(
                            selected = viewModel.daysMask and dayBit(day) != 0,
                            onClick = { viewModel.toggleDay(day) },
                            label = { Text(label) },
                        )
                    }
                }
            }
            val interval = viewModel.scheduleType == ScheduleCalculator.INTERVAL_MINUTE ||
                viewModel.scheduleType == ScheduleCalculator.INTERVAL_HOUR
            if (!interval) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (viewModel.scheduleType == ScheduleCalculator.ONCE) {
                        TextButton(onClick = { dialog = EditorDialog.Date }) { Text("选择日期") }
                    }
                    TextButton(onClick = { dialog = EditorDialog.Time }) {
                        Text(formatHm(viewModel.hour, viewModel.minute))
                    }
                }
            }
            Text(schedulePreview.first, color = MaterialTheme.colorScheme.secondary)
            Text(schedulePreview.second, color = MaterialTheme.colorScheme.secondary)
            Text(
                if (interval) "从现在开始按这个间隔重复。每次到点后，再登记下一次。" else "保存后由本应用向系统登记下一次执行，不需要去时钟里设闹钟。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("步骤", style = MaterialTheme.typography.titleMedium)
            Text(
                "长按步骤后它会浮起来，拖到两条步骤中间时会让出一条缝，松手后插入。置顶会把它放到第一步。点「编辑」可以改这一步。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val density = LocalDensity.current
            val spacingPx = with(density) { 12.dp.toPx() }
            var dragFrom by remember { mutableIntStateOf(-1) }
            var dragOffset by remember { mutableFloatStateOf(0f) }
            var dragHeight by remember { mutableFloatStateOf(1f) }
            val lastStep = viewModel.steps.lastIndex
            val slot = dragHeight + spacingPx
            val dropIndex = if (dragFrom < 0 || lastStep < 0) {
                -1
            } else {
                (dragFrom + (dragOffset / slot).roundToInt()).coerceIn(0, lastStep)
            }
            viewModel.steps.forEachIndexed { index, step ->
                val shift = when {
                    dragFrom < 0 || index == dragFrom -> 0f
                    dragFrom < dropIndex && index in (dragFrom + 1)..dropIndex -> -slot
                    dropIndex < dragFrom && index in dropIndex until dragFrom -> slot
                    else -> 0f
                }
                key(step.key) {
                    StepCard(
                        index = index,
                        lastIndex = lastStep,
                        title = "${index + 1}. ${stepSummary(step.type, step.payload)}",
                        dragging = dragFrom == index,
                        fingerOffset = if (dragFrom == index) dragOffset else 0f,
                        gapShift = shift,
                        onMeasure = { height -> if (dragFrom == index) dragHeight = height.toFloat() },
                        onDragStart = { height ->
                            dragFrom = index
                            dragHeight = height
                            dragOffset = 0f
                        },
                        onDrag = { dragOffset += it },
                        onDragEnd = {
                            if (dropIndex >= 0 && dropIndex != dragFrom) viewModel.moveTo(dragFrom, dropIndex)
                            dragFrom = -1
                            dragOffset = 0f
                        },
                        onDragCancel = {
                            dragFrom = -1
                            dragOffset = 0f
                        },
                        onMoveTo = viewModel::moveTo,
                        onEdit = { openStepEditor(index, viewModel, onPickApp) { dialog = it } },
                        onRemove = { viewModel.removeStep(index) },
                    )
                }
            }
            TextButton(onClick = {
                viewModel.cancelEdit()
                viewModel.editingCleanup = false
                dialog = EditorDialog.Add
            }) { Text("添加步骤") }
            Text("失败后", style = MaterialTheme.typography.titleMedium)
            Text(
                "某一步失败后，先做兜底清理，再按次数从头重试。0 表示不重试，清理后直接结束。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 1, 2, 3).forEach { count ->
                    FilterChip(
                        selected = viewModel.retryCount == count,
                        onClick = { viewModel.retryCount = count },
                        label = { Text(if (count == 0) "不重试" else "重试 $count 次") },
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = viewModel.cleanupType == CleanupTypes.NONE,
                    onClick = { viewModel.cleanupType = CleanupTypes.NONE },
                    label = { Text("不清理") },
                )
                FilterChip(
                    selected = viewModel.cleanupType == CleanupTypes.HOME,
                    onClick = { viewModel.cleanupType = CleanupTypes.HOME },
                    label = { Text("回到桌面") },
                )
                FilterChip(
                    selected = viewModel.cleanupType == CleanupTypes.CLOSE_LAUNCHED,
                    onClick = { viewModel.cleanupType = CleanupTypes.CLOSE_LAUNCHED },
                    label = { Text("关闭打开过的应用") },
                )
                FilterChip(
                    selected = viewModel.cleanupType == CleanupTypes.STEP,
                    onClick = {
                        viewModel.cleanupType = CleanupTypes.STEP
                        if (viewModel.cleanupStepType.isBlank()) {
                            viewModel.editingCleanup = true
                            dialog = EditorDialog.Add
                        }
                    },
                    label = { Text("自定义一步") },
                )
            }
            if (viewModel.cleanupType == CleanupTypes.STEP) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            if (viewModel.cleanupStepType.isBlank()) "还没有兜底步骤" else stepSummary(viewModel.cleanupStepType, viewModel.cleanupPayload),
                        )
                        Row {
                            TextButton(onClick = {
                                viewModel.editingCleanup = true
                                dialog = EditorDialog.Add
                            }) { Text(if (viewModel.cleanupStepType.isBlank()) "设置" else "更换") }
                            if (viewModel.cleanupStepType.isNotBlank()) {
                                TextButton(onClick = {
                                    viewModel.cleanupType = CleanupTypes.NONE
                                    viewModel.cleanupStepType = ""
                                    viewModel.cleanupPayload = ""
                                }) { Text("清除") }
                            }
                        }
                    }
                }
            }
            OutlinedTextField(
                value = viewModel.webhookUrl,
                onValueChange = { viewModel.webhookUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("企业微信机器人地址，可留空") },
                singleLine = true,
            )
            Text(
                "填写企业微信群机器人的地址。执行结束后，嘟嘟把任务名和结果发到群里。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            viewModel.notice?.let {
                Text(it, color = MaterialTheme.colorScheme.secondary)
            }
            viewModel.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = { viewModel.save(onBack) },
                enabled = !viewModel.saving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (viewModel.saving) "正在保存" else "保存")
            }
        }
        }
        }
    }

    when (val current = dialog) {
        EditorDialog.Time -> TimeDialog(
            hour = viewModel.hour,
            minute = viewModel.minute,
            onDismiss = { dialog = null },
            onConfirm = { hour, minute ->
                viewModel.setTime(hour, minute)
                dialog = null
            },
        )
        EditorDialog.Date -> DateDialog(
            initialUtc = localToUtcMidnight(viewModel.onceAtMillis),
            onDismiss = { dialog = null },
            onConfirm = { utc ->
                viewModel.setOnceDateFromUtc(utc)
                dialog = null
            },
        )
        EditorDialog.Add -> AddStepDialog(
            onDismiss = {
                dialog = null
                viewModel.editingCleanup = false
            },
            onPick = { next ->
                dialog = null
                val cleanup = viewModel.editingCleanup
                when (next) {
                    AddChoice.Launch -> onPickApp(if (cleanup) "cleanup-launch" else "launch")
                    AddChoice.Close -> onPickApp(if (cleanup) "cleanup-close" else "close")
                    AddChoice.ClickText -> dialog = EditorDialog.ClickText
                    AddChoice.Pick -> dialog = EditorDialog.Pick
                    AddChoice.ClickId -> dialog = EditorDialog.ClickId
                    AddChoice.Input -> dialog = EditorDialog.Input
                    AddChoice.Wait -> dialog = EditorDialog.Wait
                    AddChoice.WaitText -> dialog = EditorDialog.WaitText
                    AddChoice.Unlock -> dialog = EditorDialog.Unlock
                    AddChoice.Back -> viewModel.addStep(StepTypes.BACK, StepPayloads.back())
                }
            },
        )
        EditorDialog.Unlock -> AlertDialog(
            onDismissRequest = {
                viewModel.cancelEdit()
                dialog = null
            },
            title = { Text("录制解锁动作") },
            text = {
                Text("可以录一段滑屏，也可以录多次点击。到点后屏幕会先亮起并停在锁屏上，这一步只把录好的滑动和点击做一遍，不再盖住屏幕。开始后屏幕上方有保存条。如果要对着锁屏图案录，开始后按电源键锁屏，录制层会留在锁屏上面，做完点顶部保存。建议把这一步放在任务的第一步。只有当前是锁屏时才会滑动或点击；已经解锁就跳过这一步，继续后面的步骤。")
            },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    val service = com.lytalk.dodo.a11y.AutoAccessibilityService.instance
                    if (service == null) {
                        viewModel.cancelEdit()
                        viewModel.error = "请先在权限页打开无障碍服务，才能录制解锁动作"
                    } else {
                        viewModel.error = null
                        service.showUnlockRecorder()
                    }
                }) { Text("开始录制") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.cancelEdit()
                    dialog = null
                }) { Text("取消") }
            },
        )
        EditorDialog.ClickText -> TextFieldDialog(
            title = "点击文字",
            label = "界面上的文字",
            initial = editingText(viewModel, "text"),
            hint = "会在整个界面里找，不限于当前屏幕上看得见的部分。列表没显示出来时会先滚到顶，再往下翻。",
            onDismiss = {
                viewModel.cancelEdit()
                dialog = null
            },
            onConfirm = { text ->
                viewModel.addStep(StepTypes.CLICK_TEXT, StepPayloads.clickText(text))
                dialog = null
            },
        )
        EditorDialog.Pick -> AlertDialog(
            onDismissRequest = {
                viewModel.cancelEdit()
                dialog = null
            },
            title = { Text("点选控件") },
            text = {
                Text("控件 id 是应用内部给按钮、图标起的名字，例如 com.tencent.mm:id/icon，界面上不一定写得出来。没有文字的图标也可以点。\n\n请先打开目标应用并停在那个页面，再切换回这里点「开始点选」。开始后会打开刚才那个应用，不会回到桌面。")
            },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    startNodePick(context, viewModel)
                }) { Text("开始点选") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.cancelEdit()
                    dialog = null
                }) { Text("取消") }
            },
        )
        EditorDialog.ClickId -> TextFieldDialog(
            title = "手动填写控件 id",
            label = "例如 com.tencent.mm:id/icon",
            initial = editingText(viewModel, "viewId"),
            hint = "不知道 id 时用「点选控件」，点一下图标就能看到。这里只给已经知道 id 的时候用。",
            onDismiss = {
                viewModel.cancelEdit()
                dialog = null
            },
            onConfirm = { text ->
                viewModel.addStep(StepTypes.CLICK_ID, StepPayloads.clickId(text))
                dialog = null
            },
        )
        EditorDialog.EditTarget -> EditTargetDialog(
            viewModel = viewModel,
            onDismiss = {
                viewModel.cancelEdit()
                dialog = null
            },
            onRepick = {
                dialog = null
                startNodePick(context, viewModel)
            },
            onSave = { payload ->
                viewModel.addStep(StepTypes.CLICK_TARGET, payload)
                dialog = null
            },
        )
        EditorDialog.Input -> InputDialog(
            initialText = editingText(viewModel, "text"),
            initialTargetText = editingText(viewModel, "targetText"),
            initialTargetId = editingText(viewModel, "targetViewId"),
            onDismiss = {
                viewModel.cancelEdit()
                dialog = null
            },
            onConfirm = { text, targetText, targetId ->
                viewModel.addStep(StepTypes.INPUT_TEXT, StepPayloads.input(text, targetText, targetId))
                dialog = null
            },
        )
        EditorDialog.Wait -> NumberDialog(
            title = "等待毫秒",
            label = "1000 表示 1 秒，最长 120000",
            initial = editingNumber(viewModel, "millis", "1000"),
            confirmLabel = if (viewModel.editingStepIndex != null) "保存" else "添加",
            onDismiss = {
                viewModel.cancelEdit()
                dialog = null
            },
            onConfirm = { value ->
                viewModel.addStep(StepTypes.WAIT, StepPayloads.wait(value.coerceIn(0, 120_000)))
                dialog = null
            },
        )
        EditorDialog.WaitText -> WaitTextDialog(
            initialText = editingText(viewModel, "text"),
            initialSeconds = editingSeconds(viewModel),
            onDismiss = {
                viewModel.cancelEdit()
                dialog = null
            },
            onConfirm = { text, seconds ->
                viewModel.addStep(
                    StepTypes.WAIT_FOR_TEXT,
                    StepPayloads.waitForText(text, (seconds.coerceIn(1, 60) * 1000L)),
                )
                dialog = null
            },
        )
        EditorDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("删除这个任务？") },
            text = { Text("删除后不再到点执行。") },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    viewModel.delete(onBack)
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } },
        )
        null -> Unit
    }
}

private enum class EditorDialog {
    Time,
    Date,
    Add,
    ClickText,
    Pick,
    ClickId,
    EditTarget,
    Input,
    Wait,
    WaitText,
    Unlock,
    Delete,
}

private enum class AddChoice { Launch, Close, Unlock, ClickText, Pick, ClickId, Input, Wait, WaitText, Back }

private val weekDays = listOf(
    Calendar.MONDAY to "一",
    Calendar.TUESDAY to "二",
    Calendar.WEDNESDAY to "三",
    Calendar.THURSDAY to "四",
    Calendar.FRIDAY to "五",
    Calendar.SATURDAY to "六",
    Calendar.SUNDAY to "日",
)

private fun localToUtcMidnight(localMillis: Long): Long {
    val local = Calendar.getInstance().apply { timeInMillis = localMillis }
    return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(local.get(Calendar.YEAR), local.get(Calendar.MONTH), local.get(Calendar.DAY_OF_MONTH))
    }.timeInMillis
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(hour: Int, minute: Int, onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit) {
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        text = { TimePicker(state = state) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(initialUtc: Long, onDismiss: () -> Unit, onConfirm: (Long) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initialUtc)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let(onConfirm)
            }) { Text("确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    ) { DatePicker(state = state) }
}

@Composable
private fun AddStepDialog(onDismiss: () -> Unit, onPick: (AddChoice) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加步骤") },
        text = {
            Column {
                stepChoices.forEach { (choice, label) ->
                    TextButton(onClick = { onPick(choice) }, modifier = Modifier.fillMaxWidth()) {
                        Text(label, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private val stepChoices = listOf(
    AddChoice.Launch to "打开应用",
    AddChoice.Unlock to "解锁屏幕",
    AddChoice.Close to "关闭应用",
    AddChoice.ClickText to "点击文字",
    AddChoice.Pick to "点选控件（图标或按钮）",
    AddChoice.ClickId to "手动填写控件 id",
    AddChoice.Input to "输入文字",
    AddChoice.Wait to "等待一段时间",
    AddChoice.WaitText to "等到文字出现",
    AddChoice.Back to "按返回",
)

@Composable
private fun EditTargetDialog(
    viewModel: TaskEditViewModel,
    onDismiss: () -> Unit,
    onRepick: () -> Unit,
    onSave: (String) -> Unit,
) {
    val payload = editingPayload(viewModel)
    var viewId by remember { mutableStateOf(payload.optString("viewId")) }
    var text by remember { mutableStateOf(payload.optString("text")) }
    var description by remember { mutableStateOf(payload.optString("description")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("修改点选") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = viewId, onValueChange = { viewId = it }, label = { Text("控件 id，可留空") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("文字，可留空") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("描述，可留空") }, modifier = Modifier.fillMaxWidth())
                Text("位置还是原来点中的地方。想换一个控件，点重新点选。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    StepPayloads.clickTarget(
                        viewId.trim(),
                        text.trim(),
                        description.trim(),
                        payload.optDouble("xFraction").toFloat(),
                        payload.optDouble("yFraction").toFloat(),
                    ),
                )
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onRepick) { Text("重新点选") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

private fun startNodePick(context: android.content.Context, viewModel: TaskEditViewModel) {
    viewModel.error = beginNodePick(context)
}

private fun openStepEditor(
    index: Int,
    viewModel: TaskEditViewModel,
    onPickApp: (String) -> Unit,
    openDialog: (EditorDialog) -> Unit,
) {
    val step = viewModel.steps.getOrNull(index) ?: return
    when (step.type) {
        StepTypes.BACK -> viewModel.error = "按返回没有可改的内容"
        StepTypes.LAUNCH_APP -> {
            viewModel.beginEdit(index)
            onPickApp("launch")
        }
        StepTypes.CLOSE_APP -> {
            viewModel.beginEdit(index)
            onPickApp("close")
        }
        StepTypes.UNLOCK -> {
            viewModel.beginEdit(index)
            openDialog(EditorDialog.Unlock)
        }
        StepTypes.CLICK_TEXT -> {
            viewModel.beginEdit(index)
            openDialog(EditorDialog.ClickText)
        }
        StepTypes.CLICK_ID -> {
            viewModel.beginEdit(index)
            openDialog(EditorDialog.ClickId)
        }
        StepTypes.CLICK_TARGET -> {
            viewModel.beginEdit(index)
            openDialog(EditorDialog.EditTarget)
        }
        StepTypes.INPUT_TEXT -> {
            viewModel.beginEdit(index)
            openDialog(EditorDialog.Input)
        }
        StepTypes.WAIT -> {
            viewModel.beginEdit(index)
            openDialog(EditorDialog.Wait)
        }
        StepTypes.WAIT_FOR_TEXT -> {
            viewModel.beginEdit(index)
            openDialog(EditorDialog.WaitText)
        }
        else -> viewModel.error = "这一步暂时不能改"
    }
}

private fun editingPayload(viewModel: TaskEditViewModel): org.json.JSONObject {
    val index = viewModel.editingStepIndex ?: return org.json.JSONObject()
    val step = viewModel.steps.getOrNull(index) ?: return org.json.JSONObject()
    return StepPayloads.read(step.payload)
}

private fun editingText(viewModel: TaskEditViewModel, name: String): String =
    editingPayload(viewModel).optString(name)

private fun editingNumber(viewModel: TaskEditViewModel, name: String, fallback: String): String {
    val value = editingPayload(viewModel).optLong(name, -1L)
    return if (value >= 0L) value.toString() else fallback
}

private fun editingSeconds(viewModel: TaskEditViewModel): String {
    val millis = editingPayload(viewModel).optLong("timeoutMs", -1L)
    return if (millis > 0L) (millis / 1000).toString() else "10"
}

@OptIn(ExperimentalFoundationApi::class)
private object NoChaseBringIntoView : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

@Composable
private fun MovingLayer(
    offset: androidx.compose.runtime.FloatState,
    content: @Composable () -> Unit,
) {
    Box(Modifier.graphicsLayer(translationY = offset.floatValue)) {
        content()
    }
}

@Composable
private fun StepCard(
    index: Int,
    lastIndex: Int,
    title: String,
    dragging: Boolean,
    fingerOffset: Float,
    gapShift: Float,
    onMeasure: (Int) -> Unit,
    onDragStart: (Float) -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onMoveTo: (Int, Int) -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    val shift by animateFloatAsState(targetValue = gapShift, animationSpec = tween(180), label = "gap")
    val scale by animateFloatAsState(targetValue = if (dragging) 1.04f else 1f, animationSpec = tween(160), label = "lift")
    val startRef = rememberUpdatedState(onDragStart)
    val dragRef = rememberUpdatedState(onDrag)
    val endRef = rememberUpdatedState(onDragEnd)
    val cancelRef = rememberUpdatedState(onDragCancel)
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = if (dragging) 16.dp else 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(if (dragging) 1f else 0f)
            .onSizeChanged { onMeasure(it.height) }
            .graphicsLayer {
                translationY = if (dragging) fingerOffset else shift
                scaleX = scale
                scaleY = scale
                alpha = if (dragging) 0.96f else 1f
            }
            .pointerInput(index, lastIndex) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { startRef.value(size.height.toFloat()) },
                    onDragCancel = { cancelRef.value() },
                    onDragEnd = { endRef.value() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragRef.value(dragAmount.y)
                    },
                )
            },
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
            Text(title)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onEdit) { Text("编辑") }
                TextButton(onClick = { onMoveTo(index, 0) }, enabled = index > 0) { Text("置顶") }
                IconButton(onClick = { onMoveTo(index, index - 1) }, enabled = index > 0) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上移")
                }
                IconButton(onClick = { onMoveTo(index, index + 1) }, enabled = index < lastIndex) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下移")
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除步骤")
                }
            }
        }
    }
}

@Composable
private fun TextFieldDialog(
    title: String,
    label: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    initial: String = "",
    hint: String? = null,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { if (value.isNotBlank()) onConfirm(value.trim()) }) { Text(if (initial.isBlank()) "添加" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun InputDialog(
    onDismiss: () -> Unit,
    onConfirm: (text: String, targetText: String, targetId: String) -> Unit,
    initialText: String = "",
    initialTargetText: String = "",
    initialTargetId: String = "",
) {
    var text by remember { mutableStateOf(initialText) }
    var targetText by remember { mutableStateOf(initialTargetText) }
    var targetId by remember { mutableStateOf(initialTargetId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("输入文字") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("要输入的内容") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = targetText, onValueChange = { targetText = it }, label = { Text("目标文字，可留空") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = targetId, onValueChange = { targetId = it }, label = { Text("目标控件 id，可留空") }, modifier = Modifier.fillMaxWidth())
                Text("两个目标都留空时，写入当前焦点或页面上第一个输入框。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text, targetText.trim(), targetId.trim()) }) {
                Text(if (initialText.isBlank() && initialTargetText.isBlank() && initialTargetId.isBlank()) "添加" else "保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun NumberDialog(
    title: String,
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
    confirmLabel: String = "添加",
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = value, onValueChange = { value = it.filter(Char::isDigit) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.toLongOrNull() ?: 0L) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun WaitTextDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Long) -> Unit,
    initialText: String = "",
    initialSeconds: String = "10",
) {
    var text by remember { mutableStateOf(initialText) }
    var seconds by remember { mutableStateOf(initialSeconds) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("等到文字出现") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("界面文字") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = seconds,
                    onValueChange = { seconds = it.filter(Char::isDigit) },
                    label = { Text("超时秒数") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (text.isNotBlank()) onConfirm(text.trim(), seconds.toLongOrNull() ?: 10L)
            }) { Text(if (initialText.isBlank()) "添加" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

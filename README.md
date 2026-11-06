# Dodo

[中文](README.zh-CN.md)

Dodo (嘟嘟) lets your phone do the work on time.

You save a task: a time, and the steps to run. At that time Dodo opens the app you picked and runs the steps. After the run it can post the task name and result to a WeCom group robot.

Application id: `com.lytalk.dodo`.

## Requirements

- Android 12 (API 31). `compileSdk` 35 is only used when compiling.
- Tested on a Redmi K40 (`M2012K11AC`, `alioth`), Android 12, MIUI V13.0.7.0.SKHCNXM, arm64-v8a, with root.
- Autostart, background pop-ups, and battery settings in this document follow MIUI on that phone.
- Closing an app needs root (`su` runs `am force-stop`). The other steps do not.

## Permissions

Open the permission screen after install. While a task is enabled, the notification “嘟嘟守护中” stays up so the scheduled run can start.

1. **Accessibility.** Dodo reads text and controls in other apps, then taps, types, and presses back. Turn Dodo on in the system accessibility settings.
2. **Exact alarms.** The system wakes Dodo at the scheduled time. The alarm icon in the status bar only means a schedule is waiting. You do not create it in the Clock app.
3. **Display over other apps.** Needed to open another app from the background.
4. **Root.** Tap “申请 Root” and allow Dodo in Magisk or KernelSU. Only “关闭应用” uses this.
5. **Autostart.** Turn Dodo on in MIUI’s autostart list. Otherwise a run after reboot, or after the process is cleared, does not start.
6. **Background pop-ups.** Turn this on in MIUI’s permission editor. Associated startup, used when opening other apps, is on the same page.
7. **Battery.** Set Dodo to unrestricted.
8. **Recent apps.** Lock Dodo there so a swipe-clear does not remove it. Do not force-stop Dodo from the security app. Schedules and accessibility stop until you open Dodo again.

## Tasks

Create a task from the home screen. A task card can run now, copy, or edit. A copy is named “original 副本” and starts off. The edit screen can copy the current task the same way.

An enabled task registers its next run when you save. The edit screen shows that time as today or tomorrow.

### Schedule

- **Every day.** One clock time. If it is still ahead today, the next run is today. If it has passed, the next run is tomorrow.
- **Weekdays.** Same clock rule, and only on the days you select.
- **Once.** A date and a clock time. If you change only the clock and have not picked a date yourself, Dodo uses today when that time is still ahead. The task turns off after it runs.
- **Minutes.** Every 1, 5, 10, 15, or 30 minutes, counted from now. After each run, Dodo registers the same interval again.
- **Hours.** Every 1, 2, 3, 6, or 12 hours, counted the same way.

### Steps

Add steps with “添加步骤”. Long-press a step to drag it. Neighbouring steps open a gap, and the step drops there when you release. You can also pin a step to the top or move it up and down.

- **Open an app.** Pick an installed app.
- **Unlock.** Replays a recorded swipe or a series of taps, and only while the lock screen is showing. If the phone is already unlocked, this step is skipped. To record, use the bar at the top to save and the rest of the screen to record. To record on the lock screen, press the power button after recording starts. Put this step first.
- **Close an app.** Force-stops that app through root. If root is not granted, request it on the permission screen.
- **Tap text.** Finds the text in the whole interface and scrolls to it when it is off screen.
- **Pick a control.** Open the target app on the right page, return to Dodo, tap “开始点选”, then tap the icon or button. Dodo stores its id, text, or position.
- **Enter a view id.** Type the full id, for example `com.tencent.mm:id/icon`.
- **Type text.** Writes into the field you name. If both the target text and the id are empty, it uses the focused field, or the first field on the page.
- **Wait.** Milliseconds, up to 120 seconds.
- **Wait for text.** Waits until that text appears. You set the timeout in seconds.
- **Back.** Presses the system back key once.

### When a step fails

Under the step list you can set a retry count and a cleanup action.

Retry is off, or 1, 2, or 3 extra runs from the first step. Cleanup is none, go home, close the apps this task opened, or one step you define.

On failure Dodo runs the cleanup, then starts over if a retry is left. The original error stays in the result. If cleanup fails, that note is added after it.

## WeCom group robot

“企业微信机器人地址” takes the robot URL from the group. Leave it empty to send nothing. The URL must start with `http://` or `https://`.

After a run, Dodo posts a text message: task name, result, and time.

```json
{
  "msgtype": "text",
  "text": {
    "content": "嘟嘟「任务名」\n结果：成功\n时间：11月6日 21:30"
  }
}
```

If the send fails, the response from WeCom is appended to the task result.

## Build

JDK 17 and an Android SDK. Set `ANDROID_HOME`, or `sdk.dir` in `local.properties`.

```bash
./gradlew :app:assembleDebug
```

## License

[MIT](LICENSE)

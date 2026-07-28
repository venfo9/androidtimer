package com.workbreaktimer.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

enum class RepeatRule { ONCE, DAILY, WEEKLY, MONTHLY }

val RepeatRule.label: String
    get() = when (this) {
        RepeatRule.ONCE -> "Один раз"
        RepeatRule.DAILY -> "Каждый день"
        RepeatRule.WEEKLY -> "Каждую неделю"
        RepeatRule.MONTHLY -> "Каждый месяц"
    }

data class ReminderFolder(
    val id: String,
    val name: String
)

data class Reminder(
    val id: String,
    val folderId: String,
    val title: String,
    val triggerAtMillis: Long,
    val repeat: RepeatRule,
    val enabled: Boolean = true,
    /**
     * Stable per-reminder PendingIntent request code. Assigned once at creation rather than
     * derived from the id, so two reminders can never collide onto the same alarm slot.
     */
    val requestCode: Int
)

/**
 * Advances a trigger time past now according to the repeat rule. Returns null for one-shot
 * reminders, which have nothing left to schedule.
 *
 * The loop matters after the phone has been off: a daily reminder missed for a week must land
 * on the next future occurrence, not fire six times catching up.
 */
fun Reminder.nextOccurrence(afterMillis: Long = System.currentTimeMillis()): Long? {
    if (repeat == RepeatRule.ONCE) return null
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = triggerAtMillis
    var guard = 0
    while (calendar.timeInMillis <= afterMillis && guard < MAX_ADVANCE_STEPS) {
        when (repeat) {
            RepeatRule.DAILY -> calendar.add(Calendar.DAY_OF_YEAR, 1)
            RepeatRule.WEEKLY -> calendar.add(Calendar.WEEK_OF_YEAR, 1)
            RepeatRule.MONTHLY -> calendar.add(Calendar.MONTH, 1)
            RepeatRule.ONCE -> return null
        }
        guard++
    }
    return calendar.timeInMillis
}

private const val MAX_ADVANCE_STEPS = 5000

/** Storage for folders and reminders, behind an interface so the backing file can change. */
interface ReminderStore {
    fun readFolders(): List<ReminderFolder>
    fun readReminders(): List<Reminder>
    fun writeAll(folders: List<ReminderFolder>, reminders: List<Reminder>)
    fun nextRequestCode(): Int
}

class FileReminderStore(context: Context) : ReminderStore {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)
    private val lock = Any()

    private var cachedFolders: List<ReminderFolder>? = null
    private var cachedReminders: List<Reminder>? = null

    override fun readFolders(): List<ReminderFolder> = synchronized(lock) {
        load()
        cachedFolders ?: emptyList()
    }

    override fun readReminders(): List<Reminder> = synchronized(lock) {
        load()
        cachedReminders ?: emptyList()
    }

    override fun writeAll(folders: List<ReminderFolder>, reminders: List<Reminder>) =
        synchronized(lock) {
            cachedFolders = folders
            cachedReminders = reminders
            try {
                val foldersJson = JSONArray()
                for (folder in folders) {
                    foldersJson.put(
                        JSONObject().put(F_ID, folder.id).put(F_NAME, folder.name)
                    )
                }
                val remindersJson = JSONArray()
                for (reminder in reminders) {
                    remindersJson.put(
                        JSONObject()
                            .put(F_ID, reminder.id)
                            .put(F_FOLDER, reminder.folderId)
                            .put(F_TITLE, reminder.title)
                            .put(F_TRIGGER, reminder.triggerAtMillis)
                            .put(F_REPEAT, reminder.repeat.name)
                            .put(F_ENABLED, reminder.enabled)
                            .put(F_REQUEST_CODE, reminder.requestCode)
                    )
                }
                file.writeText(
                    JSONObject()
                        .put(F_FOLDERS, foldersJson)
                        .put(F_REMINDERS, remindersJson)
                        .toString()
                )
            } catch (e: Exception) {
                // Unwritable storage; the in-memory copy still serves this session.
            }
        }

    override fun nextRequestCode(): Int = synchronized(lock) {
        load()
        val used = cachedReminders.orEmpty().maxOfOrNull { it.requestCode } ?: BASE_REQUEST_CODE
        maxOf(used, BASE_REQUEST_CODE) + 1
    }

    private fun load() {
        if (cachedFolders != null && cachedReminders != null) return
        if (!file.exists()) {
            cachedFolders = emptyList()
            cachedReminders = emptyList()
            return
        }
        try {
            val root = JSONObject(file.readText())
            val foldersJson = root.optJSONArray(F_FOLDERS) ?: JSONArray()
            val folders = ArrayList<ReminderFolder>(foldersJson.length())
            for (i in 0 until foldersJson.length()) {
                val item = foldersJson.optJSONObject(i) ?: continue
                folders.add(ReminderFolder(item.optString(F_ID), item.optString(F_NAME)))
            }
            val remindersJson = root.optJSONArray(F_REMINDERS) ?: JSONArray()
            val reminders = ArrayList<Reminder>(remindersJson.length())
            for (i in 0 until remindersJson.length()) {
                val item = remindersJson.optJSONObject(i) ?: continue
                reminders.add(
                    Reminder(
                        id = item.optString(F_ID),
                        folderId = item.optString(F_FOLDER),
                        title = item.optString(F_TITLE),
                        triggerAtMillis = item.optLong(F_TRIGGER),
                        repeat = runCatching { RepeatRule.valueOf(item.optString(F_REPEAT)) }
                            .getOrDefault(RepeatRule.ONCE),
                        enabled = item.optBoolean(F_ENABLED, true),
                        requestCode = item.optInt(F_REQUEST_CODE, BASE_REQUEST_CODE + i + 1)
                    )
                )
            }
            cachedFolders = folders
            cachedReminders = reminders
        } catch (e: Exception) {
            // A corrupt file must not take the app down.
            cachedFolders = emptyList()
            cachedReminders = emptyList()
        }
    }

    private companion object {
        const val FILE_NAME = "reminders.json"

        /** Clear of the timer's own alarm request codes (1001, 1002, 2001). */
        const val BASE_REQUEST_CODE = 10_000

        const val F_FOLDERS = "folders"
        const val F_REMINDERS = "reminders"
        const val F_ID = "id"
        const val F_NAME = "name"
        const val F_FOLDER = "folder"
        const val F_TITLE = "title"
        const val F_TRIGGER = "trigger"
        const val F_REPEAT = "repeat"
        const val F_ENABLED = "enabled"
        const val F_REQUEST_CODE = "request_code"
    }
}

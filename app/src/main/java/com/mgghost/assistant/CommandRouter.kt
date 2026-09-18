package com.mgghost.assistant

object CommandRouter {
    fun route(raw: String): GhostAction {
        val cleaned = raw.trim()
        val s = cleaned.lowercase()
            .replace(Regex("[,!?]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val withoutWake = s.replace(Regex("^hey\\s+ghost[,:]?\\s*"), "").trim()

        return when {
            withoutWake == "camera" || withoutWake.contains("open camera") || withoutWake.contains("take a photo") || withoutWake.contains("take a picture") ->
                GhostAction("camera")
            withoutWake.contains("flashlight") || withoutWake.contains("torch") || withoutWake.matches(Regex(".*\\bflash\\b.*")) ->
                GhostAction("flash", if (withoutWake.contains("off") || withoutWake.contains("disable")) "off" else "on")
            withoutWake.matches(Regex("(open|launch|start|run) .+")) ->
                GhostAction("app_name", withoutWake.replaceFirst(Regex("^(open|launch|start|run)\\s+"), "").trim())
            withoutWake == "settings" || withoutWake.contains("open settings") -> GhostAction("settings")
            withoutWake.contains("wifi") -> GhostAction("wifi")
            withoutWake.contains("bluetooth") -> GhostAction("bluetooth")
            withoutWake.contains("battery") -> GhostAction("battery")
            withoutWake.contains("do not disturb") || withoutWake == "dnd" -> GhostAction("dnd")
            withoutWake.contains("what time") || withoutWake == "time" || withoutWake.contains("current time") -> GhostAction("time")
            withoutWake.contains("what date") || withoutWake.contains("today's date") || withoutWake == "date" -> GhostAction("date")
            withoutWake.contains("volume") || withoutWake.contains("louder") || withoutWake.contains("quieter") ->
                GhostAction("volume", if (withoutWake.contains("down") || withoutWake.contains("quieter") || withoutWake.contains("lower")) "down" else "up")
            withoutWake.startsWith("call ") || withoutWake.startsWith("dial ") || withoutWake.startsWith("phone ") ->
                GhostAction("call", withoutWake.replaceFirst(Regex("^(call|dial|phone)\\s+"), "").trim(), true)
            withoutWake.startsWith("text ") || withoutWake.startsWith("message ") || withoutWake.startsWith("send a message") || withoutWake.startsWith("send message") ->
                GhostAction("sms", cleaned, true)
            withoutWake.contains("remind") || withoutWake.contains("set reminder") -> GhostAction("reminder", cleaned)
            withoutWake.contains("set alarm") || withoutWake.startsWith("alarm") -> GhostAction("alarm", cleaned)
            withoutWake.contains("set timer") || withoutWake.startsWith("timer") -> GhostAction("timer", cleaned)
            withoutWake.contains("weather") -> GhostAction("weather", cleaned)
            withoutWake.contains("navigate") || withoutWake.contains("directions") || withoutWake.startsWith("take me to ") -> GhostAction("maps", cleaned)
            withoutWake.startsWith("search ") || withoutWake.startsWith("search for ") || withoutWake.startsWith("look up ") -> GhostAction("search", cleaned)
            withoutWake == "help" || withoutWake.contains("what can you do") -> GhostAction("help")
            else -> GhostAction("ai", cleaned)
        }
    }
}

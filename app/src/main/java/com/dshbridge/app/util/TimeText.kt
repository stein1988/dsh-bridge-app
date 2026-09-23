package com.dshbridge.app.util

import android.content.Context
import com.dshbridge.app.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 把时间戳渲染成"刚刚 / N 分钟前 / N 小时前 / N 天前"，更久则显示日期。 */
object TimeText {

    fun relative(context: Context, timestamp: Long, now: Long = System.currentTimeMillis()): String {
        if (timestamp <= 0L) return context.getString(R.string.time_never)

        val diff = now - timestamp
        if (diff < 0) return context.getString(R.string.time_just_now)

        val minutes = diff / 60_000L
        val hours = diff / 3_600_000L
        val days = diff / 86_400_000L

        return when {
            minutes < 1L -> context.getString(R.string.time_just_now)
            minutes < 60L -> context.getString(R.string.time_minutes, minutes.toInt())
            hours < 24L -> context.getString(R.string.time_hours, hours.toInt())
            days <= 30L -> context.getString(R.string.time_days, days.toInt())
            else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))
        }
    }
}

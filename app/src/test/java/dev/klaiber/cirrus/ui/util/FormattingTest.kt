package dev.klaiber.cirrus.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.concurrent.TimeUnit

class FormattingTest {

    private val now = 1_700_000_000_000L

    /** Mirrors the production logic so the test is deterministic in any timezone. */
    private fun startOfToday(): Long = Calendar.getInstance().apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `bucketFor - today`() {
        assertEquals(DateBucket.TODAY, bucketFor(startOfToday(), now))
        assertEquals(DateBucket.TODAY, bucketFor(startOfToday() + 1, now))
    }

    @Test
    fun `bucketFor - yesterday`() {
        val day = TimeUnit.DAYS.toMillis(1)
        assertEquals(DateBucket.YESTERDAY, bucketFor(startOfToday() - 1, now))
        assertEquals(DateBucket.YESTERDAY, bucketFor(startOfToday() - day, now))
    }

    @Test
    fun `bucketFor - this week`() {
        val day = TimeUnit.DAYS.toMillis(1)
        assertEquals(DateBucket.THIS_WEEK, bucketFor(startOfToday() - day - 1, now))
        assertEquals(DateBucket.THIS_WEEK, bucketFor(startOfToday() - 7 * day, now))
    }

    @Test
    fun `bucketFor - this month`() {
        val day = TimeUnit.DAYS.toMillis(1)
        assertEquals(DateBucket.THIS_MONTH, bucketFor(startOfToday() - 7 * day - 1, now))
        assertEquals(DateBucket.THIS_MONTH, bucketFor(startOfToday() - 30 * day, now))
    }

    @Test
    fun `bucketFor - older`() {
        val day = TimeUnit.DAYS.toMillis(1)
        assertEquals(DateBucket.OLDER, bucketFor(startOfToday() - 30 * day - 1, now))
    }

    @Test
    fun `formatNanos - milliseconds`() {
        assertEquals("0 ms", formatNanos(0))
        assertEquals("500 ms", formatNanos(500_000_000))
    }

    @Test
    fun `formatNanos - seconds`() {
        assertEquals("1.5 s", formatNanos(1_500_000_000))
    }

    @Test
    fun `formatNanos - minutes`() {
        assertEquals("1:30", formatNanos(90_000_000_000))
    }

    @Test
    fun `formatWhen - minutes away stays in minutes`() {
        assertEquals("in 20 min", formatWhen(now + TimeUnit.MINUTES.toMillis(20), now))
        assertEquals("any moment", formatWhen(now, now))
    }

    /**
     * The clock time is the half people check a schedule for, so anything an hour or more out
     * names it — even when "in 15 hours" would also have been true.
     */
    @Test
    fun `formatWhen - later today names the time`() {
        val target = startOfToday() + TimeUnit.HOURS.toMillis(23)
        val early = startOfToday() + TimeUnit.HOURS.toMillis(1)
        assertEquals("today at ${formatTime(target)}", formatWhen(target, early))
    }

    @Test
    fun `formatWhen - the next calendar day is tomorrow`() {
        val target = startOfToday() + TimeUnit.DAYS.toMillis(1) + TimeUnit.HOURS.toMillis(7)
        assertEquals("tomorrow at ${formatTime(target)}", formatWhen(target, now))
    }

    @Test
    fun `formatWhen - within the week names the day`() {
        val target = startOfToday() + TimeUnit.DAYS.toMillis(3) + TimeUnit.HOURS.toMillis(9)
        val label = formatWhen(target, now)
        assertTrue("expected a weekday name in \"$label\"", label.contains(" at "))
        assertFalse(label.startsWith("tomorrow"))
        assertFalse(label.startsWith("today"))
    }

    @Test
    fun `formatDuration - reads in the coarsest useful unit`() {
        assertEquals("under a second", formatDuration(200))
        assertEquals("45s", formatDuration(45_000))
        assertEquals("2m 5s", formatDuration(125_000))
        assertEquals("1h 1m", formatDuration(3_660_000))
    }

    @Test
    fun `formatBytes`() {
        assertEquals("500 B", formatBytes(500))
        assertEquals("5 KB", formatBytes(5_000))
        assertEquals("5.0 MB", formatBytes(5_000_000))
        assertEquals("5.0 GB", formatBytes(5_000_000_000))
    }
}

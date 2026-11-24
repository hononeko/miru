package app.hononeko.miru.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class StatsAccumulatorsTest :
    FunSpec({
        test("UTC epoch day, day-of-week, and hour arithmetic match java.time across leap years and pre-epoch timestamps") {
            val timestamps =
                listOf(
                    "1969-12-31T23:59:59Z",
                    "1970-01-01T00:00:00Z",
                    "1970-01-04T12:30:00Z",
                    "1970-01-05T00:00:01Z",
                    "2024-02-29T18:45:10Z",
                    "2026-04-13T20:05:00Z",
                    "2026-12-31T23:59:59Z",
                )
            for (iso in timestamps) {
                val instant = Instant.parse(iso)
                val zdt = instant.atZone(ZoneOffset.UTC)
                val ms = instant.toEpochMilli()
                val epochDay = utcEpochDay(ms)
                LocalDate.ofEpochDay(epochDay) shouldBe zdt.toLocalDate()
                utcDayOfWeekIndex(epochDay) shouldBe (zdt.dayOfWeek.value - 1)
                utcHourOfDay(ms) shouldBe zdt.hour
            }
        }

        test("computeLongestStreakFromEpochDays handles empty, single, contiguous, and gapped sequences") {
            computeLongestStreakFromEpochDays(emptySet()) shouldBe 0
            computeLongestStreakFromEpochDays(setOf(100L)) shouldBe 1
            computeLongestStreakFromEpochDays(setOf(10L, 12L, 11L, 20L, 21L)) shouldBe 3
            computeLongestStreakFromEpochDays(setOf(1L, 5L, 10L)) shouldBe 1
        }
    })

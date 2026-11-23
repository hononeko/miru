package app.hononeko.miru.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class MediaArtUrlTest :
    FunSpec({
        test("uses the rating key named in a library art path, which for episodes and seasons is the show's") {
            buildArtUrl("54321", "/library/metadata/54000/art/1700000010") shouldBe "/api/v1/media/54000/art"
            buildArtUrl("12345", "/library/metadata/12345/art") shouldBe "/api/v1/media/12345/art"
        }

        test("falls back to the item's own rating key for other art paths and returns null without art") {
            buildArtUrl("777", "/:/resources/movie-fanart.jpg") shouldBe "/api/v1/media/777/art"
            buildArtUrl("777", "  ").shouldBeNull()
            buildArtUrl("777", null).shouldBeNull()
        }
    })

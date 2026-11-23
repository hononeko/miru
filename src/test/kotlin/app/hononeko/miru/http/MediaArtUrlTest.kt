package app.hononeko.miru.http

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class MediaArtUrlTest :
    FunSpec({
        test("uses the rating key named in a library art path, including non-numeric tokens and query strings") {
            buildArtUrl("54321", "/library/metadata/54000/art/1700000010") shouldBe "/api/v1/media/54000/art"
            buildArtUrl("54321", "/library/metadata/54000/art/upload?X-Plex-Token=secret") shouldBe "/api/v1/media/54000/art"
            buildArtUrl("12345", "/library/metadata/12345/art") shouldBe "/api/v1/media/12345/art"
        }

        test("uses the rating key named in a library thumb path, e.g. a music track pointing to its album") {
            buildThumbUrl("77001", "/library/metadata/77000/thumb/1700000200") shouldBe "/api/v1/media/77000/thumb"
            buildThumbUrl("77001", "/library/metadata/77000/thumb/upload?X-Plex-Token=secret") shouldBe "/api/v1/media/77000/thumb"
            buildThumbUrl("12345", "/library/metadata/12345/thumb") shouldBe "/api/v1/media/12345/thumb"
            // A mismatched kind path falls back to the item's own rating key.
            buildThumbUrl("77001", "/library/metadata/77000/art/1700000200") shouldBe "/api/v1/media/77001/thumb"
        }

        test("falls back to the item's own rating key for other artwork paths and returns null when blank") {
            buildArtUrl("777", "/:/resources/movie-fanart.jpg") shouldBe "/api/v1/media/777/art"
            buildThumbUrl("777", "/:/resources/movie-poster.jpg") shouldBe "/api/v1/media/777/thumb"
            buildArtUrl("777", "  ").shouldBeNull()
            buildArtUrl("777", null).shouldBeNull()
            buildThumbUrl("777", "  ").shouldBeNull()
            buildThumbUrl("777", null).shouldBeNull()
        }
    })

package app.hononeko.miru.adapter.plex

import app.hononeko.miru.adapter.MediaServerAdapter
import app.hononeko.miru.domain.error.MiruError
import app.hononeko.miru.health.ReadinessCheck
import arrow.core.Either

class PlexReadinessCheck(
    private val adapter: MediaServerAdapter,
) : ReadinessCheck {
    override suspend fun check(): Either<MiruError, Unit> =
        adapter.checkHealth().map { }
}

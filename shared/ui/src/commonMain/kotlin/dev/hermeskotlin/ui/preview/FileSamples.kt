package dev.hermeskotlin.ui.preview

import dev.hermeskotlin.core.media.FileText
import dev.hermeskotlin.core.media.GatewayFile
import dev.hermeskotlin.ui.chat.FileLoad

/** Files a reply linked to, as their viewer shows them. */
internal object FileSamples {

    fun of(scene: PreviewScene): Pair<GatewayFile, FileLoad> = when (scene) {
        PreviewScene.FileCode -> GatewayFile("/srv/app/server/limits.py", line = 14) to
            FileLoad.Ready(FileText(CODE, 1_870, truncated = false, binary = false, language = "python"))
        PreviewScene.FileTable -> GatewayFile("/opt/data/out/road-trip-fuel.csv") to
            FileLoad.Ready(FileText(CSV, CSV.length.toLong(), truncated = false, binary = false))
        PreviewScene.FileMissing -> GatewayFile("/opt/data/out/old-report.md") to
            FileLoad.Failed("It isn't on the gateway, or it has moved since the reply was written.")
        else -> GatewayFile("/opt/data/out/trip-plan.md") to
            FileLoad.Ready(FileText(MARKDOWN, 2_410, truncated = false, binary = false, language = "markdown"))
    }

    private val MARKDOWN = """
        # Weekend drive: Los Angeles to Zion

        About **430 miles** each way. Leave Friday night to miss the I-15 traffic.

        ## Stops
        1. **Barstow**, fuel and dinner (2 h in)
        2. **Las Vegas**, overnight
        3. **St. George**, coffee and groceries
        4. **Springdale**, check in by 3 pm

        ## Packing
        - [x] Tire gauge and compressor
        - [x] Two days of water
        - [ ] Phone mount for the passenger side

        > The stretch past Baker has few gas stations. Fill up in Barstow even if the tank looks fine.

        | Leg | Distance | Time |
        |---|---:|---:|
        | Los Angeles to Barstow | 115 mi | 2 h |
        | Barstow to Las Vegas | 155 mi | 2 h 20 |
        | Las Vegas to Springdale | 160 mi | 2 h 40 |
    """.trimIndent()

    private val CODE = """
        import time
        from collections import deque


        class RateLimiter:
            ""${'"'}Sliding-window limiter: at most `limit` calls in any `window` seconds.""${'"'}

            def __init__(self, limit: int, window: float = 60.0):
                self.limit = limit
                self.window = window
                self.calls: deque[float] = deque()

            def allow(self) -> bool:
                now = time.monotonic()
                while self.calls and now - self.calls[0] > self.window:
                    self.calls.popleft()
                if len(self.calls) >= self.limit:
                    return False
                self.calls.append(now)
                return True

            def wait_time(self) -> float:
                if len(self.calls) < self.limit:
                    return 0.0
                return max(0.0, self.window - (time.monotonic() - self.calls[0]))


        limiter = RateLimiter(limit=20, window=60)  # 20 requests a minute per user
    """.trimIndent()

    private val CSV = buildString {
        appendLine("date,station,gallons,price_usd,miles,notes")
        val rows = listOf(
            "2026-10-01,Chevron Pasadena,12.4,63.12,352,\"full, highway\"",
            "2026-10-04,Shell Barstow,11.9,58.43,318,",
            "2026-10-06,Arco Las Vegas,13.1,55.93,370,before the desert stretch",
            "2026-10-07,Maverik St. George,12.0,47.00,331,",
            "2026-10-09,Sinclair Springdale,10.3,44.66,292,\"quiet, cheap coffee\"",
            "2026-10-11,Maverik St. George,12.7,49.98,350,",
            "2026-10-12,Arco Las Vegas,11.2,47.13,320,traffic",
            "2026-10-13,Chevron Pasadena,9.6,49.32,271,home",
        )
        rows.forEach { appendLine(it) }
    }
}

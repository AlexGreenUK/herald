package dev.hermeskotlin.ui.chat

import dev.hermeskotlin.core.network.ApiResult
import kotlin.test.Test
import kotlin.test.assertEquals

class FileErrorTest {

    @Test
    fun theGatewaysRefusalsReadAsSentences() {
        assertEquals("It isn't on the gateway, or it has moved since the reply was written.", fileError(ApiResult.Failed(404, "File not found")))
        assertEquals("The gateway keeps files like this one private.", fileError(ApiResult.Failed(403, "Access to sensitive files is not allowed")))
        assertEquals("The gateway won't share it: Path outside media roots.", fileError(ApiResult.Failed(403, "Path outside media roots")))
        assertEquals("Path points to a directory.", fileError(ApiResult.Failed(400, "Path points to a directory")))
        assertEquals("Sign in again to open files.", fileError(ApiResult.SessionExpired))
        assertEquals("Couldn't reach the gateway.", fileError(ApiResult.Unavailable("timeout")))
    }

    @Test
    fun sizesReadShort() {
        assertEquals("900 B", formatBytes(900))
        assertEquals("1.4 KB", formatBytes(1_450))
        assertEquals("512 KB", formatBytes(524_288))
        assertEquals("3.2 MB", formatBytes(3_400_000))
    }
}

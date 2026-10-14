package dev.hermeskotlin.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GatewayFileTest {

    @Test
    fun absolutePathsAndFileUrisAreGatewayFiles() {
        assertEquals(GatewayFile("/opt/data/report.md"), GatewayFile.fromLink("/opt/data/report.md"))
        assertEquals(GatewayFile("/opt/data/report.md"), GatewayFile.fromLink("file:///opt/data/report.md"))
        assertEquals(GatewayFile("/opt/data/report.md"), GatewayFile.fromLink("file://localhost/opt/data/report.md"))
        assertEquals(GatewayFile("/opt/data/report.md"), GatewayFile.fromLink("FILE:/opt/data/report.md"))
        assertEquals(GatewayFile("~/notes/today.txt"), GatewayFile.fromLink("~/notes/today.txt"))
    }

    @Test
    fun webLinksOtherSchemesAndRelativePathsAreNot() {
        listOf(
            "https://example.com/a.md", "mailto:me@example.com", "intent://scan#Intent;end", "//cdn.example.com/x.js",
            "report.md", "./out/report.md", "file://otherhost/share/x.txt", "", "/", "file:///", "javascript:alert(1)",
        ).forEach { assertNull(GatewayFile.fromLink(it), it) }
    }

    @Test
    fun escapesAreDecodedAndTheLineIsKept() {
        assertEquals(GatewayFile("/home/me/My Notes/plan ü.md"), GatewayFile.fromLink("file:///home/me/My%20Notes/plan%20%C3%BC.md"))
        assertEquals(GatewayFile("/tmp/100%real.txt"), GatewayFile.fromLink("/tmp/100%real.txt"))
        assertEquals(GatewayFile("/tmp/%-1 50%.txt"), GatewayFile.fromLink("/tmp/%-1%2050%.txt"))
        // A character outside the BMP keeps both halves of its surrogate pair beside an escape.
        assertEquals(GatewayFile("/tmp/My 😀.txt"), GatewayFile.fromLink("file:///tmp/My%20😀.txt"))
        assertEquals(GatewayFile("/src/App.kt", line = 42), GatewayFile.fromLink("/src/App.kt:42"))
        assertEquals(GatewayFile("/src/App.kt", line = 42), GatewayFile.fromLink("/src/App.kt:42:7"))
        assertEquals(GatewayFile("/src/App.kt", line = 12), GatewayFile.fromLink("file:///src/App.kt#L12"))
        assertEquals(GatewayFile("/src/App.kt", line = 12), GatewayFile.fromLink("/src/App.kt#L12-L20"))
        assertEquals(GatewayFile("/src/App.kt"), GatewayFile.fromLink("/src/App.kt#readme"))
        assertEquals(GatewayFile("C:/Users/me/a.txt"), GatewayFile.fromLink("file:///C:/Users/me/a.txt"))
    }

    @Test
    fun nameFolderAndKind() {
        val file = GatewayFile("/opt/data/out/report.MD")
        assertEquals("report.MD", file.name)
        assertEquals("/opt/data/out", file.folder)
        assertEquals(GatewayFileKind.Markdown, file.kind)
        assertEquals("", GatewayFile("/x.txt").folder)
    }

    @Test
    fun kindsByExtension() {
        assertEquals(GatewayFileKind.Json, GatewayFileKind.of("data.json"))
        assertEquals(GatewayFileKind.Table, GatewayFileKind.of("sales.csv"))
        assertEquals(GatewayFileKind.Table, GatewayFileKind.of("sales.tsv"))
        assertEquals(GatewayFileKind.Code, GatewayFileKind.of("config.yaml"))
        assertEquals(GatewayFileKind.Code, GatewayFileKind.of("Dockerfile"))
        assertEquals(GatewayFileKind.Text, GatewayFileKind.of("agent.log"))
        assertEquals(GatewayFileKind.Text, GatewayFileKind.of("events.jsonl"))
        assertEquals(GatewayFileKind.Text, GatewayFileKind.of(".bashrc"))
        assertEquals(GatewayFileKind.Text, GatewayFileKind.of("README"))
        assertEquals(GatewayFileKind.Text, GatewayFileKind.of("notes.weird"))
        assertEquals(GatewayFileKind.Image, GatewayFileKind.of("cat.PNG"))
        assertEquals(GatewayFileKind.Other, GatewayFileKind.of("report.pdf"))
        assertTrue(GatewayFileKind.Code.previewable)
        assertFalse(GatewayFileKind.Other.previewable)
        assertFalse(GatewayFileKind.Image.previewable)
    }
}

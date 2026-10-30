package tk.mallumo.localai.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileContentTypeHelperTest {

    @Test
    fun testSourceCodeFilesAllowed() {
        assertTrue(FileContentTypeHelper.isAiSupportedTextFile("src/Main.kt"))
        assertTrue(FileContentTypeHelper.isAiSupportedTextFile("build.gradle.kts"))
        assertTrue(FileContentTypeHelper.isAiSupportedTextFile("src/Service.java"))
        assertTrue(FileContentTypeHelper.isAiSupportedTextFile("web/app.js"))
        assertTrue(FileContentTypeHelper.isAiSupportedTextFile("web/index.ts"))
        assertTrue(FileContentTypeHelper.isAiSupportedTextFile("script.py"))
        assertTrue(FileContentTypeHelper.isAiSupportedTextFile("native/main.cpp"))
        assertTrue(FileContentTypeHelper.isAiSupportedTextFile("native/header.h"))
        assertTrue(FileContentTypeHelper.isAiSupportedTextFile("backend/main.go"))
        assertTrue(FileContentTypeHelper.isAiSupportedTextFile("backend/lib.rs"))
    }

    @Test
    fun testMarkdownSvgAndTextAllowed() {
        assertTrue(FileContentTypeHelper.isAiSupportedTextFile("README.md"))
        assertTrue(FileContentTypeHelper.isAiSupportedTextFile("docs/guide.markdown"))
        assertTrue(FileContentTypeHelper.isAiSupportedTextFile("icons/logo.svg"))
        assertTrue(FileContentTypeHelper.isAiSupportedTextFile("notes.txt"))
        assertTrue(FileContentTypeHelper.isAiSupportedTextFile("config/settings.json"))
        assertTrue(FileContentTypeHelper.isAiSupportedTextFile("config/application.yaml"))
        assertTrue(FileContentTypeHelper.isAiSupportedTextFile("config/application.yml"))
        assertTrue(FileContentTypeHelper.isAiSupportedTextFile("config/config.toml"))
        assertTrue(FileContentTypeHelper.isAiSupportedTextFile("src/main/resources/strings.xml"))
        assertTrue(FileContentTypeHelper.isAiSupportedTextFile("gradle.properties"))
    }

    @Test
    fun testStandardTextFilenamesWithoutExtensions() {
        assertTrue(FileContentTypeHelper.isAiSupportedTextFile("Dockerfile"))
        assertTrue(FileContentTypeHelper.isAiSupportedTextFile("Makefile"))
        assertTrue(FileContentTypeHelper.isAiSupportedTextFile(".gitignore"))
        assertTrue(FileContentTypeHelper.isAiSupportedTextFile(".editorconfig"))
        assertTrue(FileContentTypeHelper.isAiSupportedTextFile("LICENSE"))
    }

    @Test
    fun testImagesDisallowed() {
        assertFalse(FileContentTypeHelper.isAiSupportedTextFile("assets/image.png"))
        assertFalse(FileContentTypeHelper.isAiSupportedTextFile("assets/photo.jpg"))
        assertFalse(FileContentTypeHelper.isAiSupportedTextFile("assets/picture.jpeg"))
        assertFalse(FileContentTypeHelper.isAiSupportedTextFile("assets/anim.gif"))
        assertFalse(FileContentTypeHelper.isAiSupportedTextFile("assets/banner.webp"))
        assertFalse(FileContentTypeHelper.isAiSupportedTextFile("assets/favicon.ico"))
        assertFalse(FileContentTypeHelper.isAiSupportedTextFile("assets/design.psd"))
    }

    @Test
    fun testVideosDisallowed() {
        assertFalse(FileContentTypeHelper.isAiSupportedTextFile("media/demo.mp4"))
        assertFalse(FileContentTypeHelper.isAiSupportedTextFile("media/recording.mkv"))
        assertFalse(FileContentTypeHelper.isAiSupportedTextFile("media/clip.avi"))
        assertFalse(FileContentTypeHelper.isAiSupportedTextFile("media/movie.mov"))
        assertFalse(FileContentTypeHelper.isAiSupportedTextFile("media/intro.webm"))
    }

    @Test
    fun testPdfsAndBinaryDocsDisallowed() {
        assertFalse(FileContentTypeHelper.isAiSupportedTextFile("docs/manual.pdf"))
        assertFalse(FileContentTypeHelper.isAiSupportedTextFile("docs/spec.docx"))
        assertFalse(FileContentTypeHelper.isAiSupportedTextFile("sheets/data.xlsx"))
        assertFalse(FileContentTypeHelper.isAiSupportedTextFile("slides/presentation.pptx"))
    }

    @Test
    fun testArchivesAndBinariesDisallowed() {
        assertFalse(FileContentTypeHelper.isAiSupportedTextFile("dist/bundle.zip"))
        assertFalse(FileContentTypeHelper.isAiSupportedTextFile("dist/lib.jar"))
        assertFalse(FileContentTypeHelper.isAiSupportedTextFile("build/classes/Main.class"))
        assertFalse(FileContentTypeHelper.isAiSupportedTextFile("lib/native.so"))
        assertFalse(FileContentTypeHelper.isAiSupportedTextFile("bin/app.exe"))
    }

    @Test
    fun testContentTypeDescriptions() {
        assertEquals("image", FileContentTypeHelper.getContentTypeDescription("logo.png"))
        assertEquals("video", FileContentTypeHelper.getContentTypeDescription("clip.mp4"))
        assertEquals("PDF document", FileContentTypeHelper.getContentTypeDescription("manual.pdf"))
        assertEquals("SVG vector", FileContentTypeHelper.getContentTypeDescription("star.svg"))
        assertEquals("source code", FileContentTypeHelper.getContentTypeDescription("Main.kt"))
        assertEquals("text document", FileContentTypeHelper.getContentTypeDescription("notes.md"))
    }
}

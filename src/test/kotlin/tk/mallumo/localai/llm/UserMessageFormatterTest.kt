package tk.mallumo.localai.llm

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserMessageFormatterTest {

    @Test
    fun testToHtmlConversion() {
        val markdown = """
            # What's New in v1.0.0
            
            Welcome to the newest update!
            
            ### Highlights
            - **Fast Search**: Find things *faster* than before.
            - Improved reliability.
            
            Visit [Website](https://example.com) for details.
        """.trimIndent()

        val html = UserMessageFormatter.toHtml(markdown)
        assertTrue(html.contains("<h1>What's New in v1.0.0</h1>"))
        assertTrue(html.contains("<p>Welcome to the newest update!</p>"))
        assertTrue(html.contains("<h3>Highlights</h3>"))
        assertTrue(html.contains("<ul>"))
        assertTrue(html.contains("<li><strong>Fast Search</strong>: Find things <em>faster</em> than before.</li>"))
        assertTrue(html.contains("<li>Improved reliability.</li>"))
        assertTrue(html.contains("</ul>"))
        assertTrue(html.contains("""<a href="https://example.com">Website</a>"""))
    }

    @Test
    fun testToPlainTextConversion() {
        val markdown = """
            # What's New in v1.0.0
            
            Welcome to the newest update!
            
            ### Highlights
            - **Fast Search**: Find things *faster* than before.
            - Improved reliability.
        """.trimIndent()

        val text = UserMessageFormatter.toPlainText(markdown)
        assertTrue(text.contains("WHAT'S NEW IN V1.0.0"))
        assertTrue(text.contains("Welcome to the newest update!"))
        assertTrue(text.contains("Highlights:"))
        assertTrue(text.contains("• Fast Search: Find things faster than before."))
        assertTrue(text.contains("• Improved reliability."))
        assertFalse(text.contains("**"))
    }
}

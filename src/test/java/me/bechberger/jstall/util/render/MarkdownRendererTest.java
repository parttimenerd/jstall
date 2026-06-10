package me.bechberger.jstall.util.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownRendererTest {

    private String render(String markdown) {
        List<String> parts = new ArrayList<>();
        MarkdownRenderer renderer = new MarkdownRenderer(parts::add);
        renderer.accept(markdown);
        renderer.flush();
        return String.join("", parts);
    }

    private String renderStreaming(String markdown) {
        List<String> parts = new ArrayList<>();
        MarkdownRenderer renderer = new MarkdownRenderer(parts::add);
        // Feed one character at a time
        for (char c : markdown.toCharArray()) {
            renderer.accept(String.valueOf(c));
        }
        renderer.flush();
        return String.join("", parts);
    }

    private String strip(String s) {
        return AnsiCodes.strip(s);
    }

    @Test
    void stripsAnsi() {
        String result = render("# Hello\n");
        String stripped = strip(result);
        assertTrue(stripped.contains("# Hello"), "Expected '# Hello' in: " + stripped);
    }

    @Test
    void h1HasBoldAndCyan() {
        String result = render("# Title\n");
        assertTrue(result.contains(AnsiCodes.BOLD_ON), "H1 should be bold");
        assertTrue(result.contains(AnsiCodes.FG_CYAN), "H1 should be cyan");
        assertTrue(result.contains("# Title"), "H1 text should be present");
    }

    @Test
    void h2HasBoldAndYellow() {
        String result = render("## Section\n");
        assertTrue(result.contains(AnsiCodes.BOLD_ON));
        assertTrue(result.contains(AnsiCodes.FG_YELLOW));
        assertTrue(result.contains("## Section"));
    }

    @Test
    void boldText() {
        String result = MarkdownRenderer.renderInline("This is **important** text");
        assertTrue(result.contains(AnsiCodes.BOLD_ON));
        assertTrue(result.contains("important"));
        assertTrue(result.contains(AnsiCodes.RESET));
    }

    @Test
    void inlineCode() {
        String result = MarkdownRenderer.renderInline("Call `foo()` now");
        assertTrue(result.contains(AnsiCodes.FG_CYAN));
        assertTrue(result.contains("foo()"));
    }

    @Test
    void italicAsterisk() {
        String result = MarkdownRenderer.renderInline("This is *italic* text");
        assertTrue(result.contains(AnsiCodes.ITALIC_ON));
        assertTrue(result.contains("italic"));
    }

    @Test
    void link() {
        String result = MarkdownRenderer.renderInline("See [docs](https://example.com)");
        assertTrue(result.contains("docs"));
        assertTrue(result.contains("https://example.com"));
        assertFalse(result.contains("[docs]("));
    }

    @Test
    void unorderedList() {
        String result = render("- item one\n- item two\n");
        assertTrue(result.contains("•"), "Should have bullet: " + strip(result));
        assertTrue(result.contains("item one"));
        assertTrue(result.contains("item two"));
    }

    @Test
    void orderedList() {
        String result = render("1. first\n2. second\n");
        assertTrue(strip(result).contains("1."));
        assertTrue(strip(result).contains("2."));
        assertTrue(strip(result).contains("first"));
    }

    @Test
    void codeFence() {
        String result = render("```java\nint x = 1;\n```\n");
        assertTrue(result.contains(AnsiCodes.FG_CYAN));
        assertTrue(result.contains("int x = 1;"));
    }

    @Test
    void blockquote() {
        String result = render("> Some wisdom\n");
        assertTrue(result.contains("│"));
        assertTrue(strip(result).contains("Some wisdom"));
    }

    @Test
    void horizontalRule() {
        String result = render("---\n");
        assertTrue(strip(result).contains("──"));
    }

    @Test
    void emptyLine() {
        String result = render("\n");
        assertEquals("\n", result);
    }

    @Test
    void streamingMatchesWholeInput() {
        String md = "# Title\n\nSome **bold** text with `code`.\n\n- item\n- item2\n";
        assertEquals(render(md), renderStreaming(md));
    }

    @Test
    void plainTextPassthrough() {
        String result = render("Just plain text\n");
        assertEquals("Just plain text\n", strip(result));
    }

    @Test
    void noTrailingEscapeLeakFromCodeFence() {
        String result = render("```\ncode\n```\nafter\n");
        // After the fence closes, "after" should not have code styling
        String afterPart = result.substring(result.lastIndexOf("after"));
        assertFalse(afterPart.startsWith(AnsiCodes.FG_CYAN));
    }
}

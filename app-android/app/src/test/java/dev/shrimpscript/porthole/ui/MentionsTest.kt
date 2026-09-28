package dev.shrimpscript.porthole.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MentionsTest {
    @Test
    fun aMentionIsTheLastWordStartingWithAt() {
        assertEquals("", trailingMention("@"))
        assertEquals("serv", trailingMention("look at @serv"))
        assertEquals("src/ma", trailingMention("two lines\n@src/ma"))
        assertNull(trailingMention("look at @serv now"))    // moved on: the word is done
        assertNull(trailingMention("mail me@example.com"))  // not at the start of a word
        assertNull(trailingMention("@a@b"))
        assertNull(trailingMention(""))
    }

    @Test
    fun pickingReplacesTheMentionAndLeavesASpace() {
        assertEquals("look at @src/server.go ", insertMention("look at @serv", "src/server.go"))
        assertEquals("@README.md ", insertMention("@", "README.md"))
        assertEquals("no mention", insertMention("no mention", "x.go"))
    }
}

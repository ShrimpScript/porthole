package dev.shrimpscript.porthole

import dev.shrimpscript.porthole.net.PairLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairLinkTest {
    @Test fun `a pairing link yields host and code`() {
        assertEquals(PairLink("192.0.2.10:8737", "123456"), PairLink.parse("porthole://pair?host=192.0.2.10:8737&code=123456"))
        assertEquals(PairLink("box.example.ts.net:8737", "000042"), PairLink.parse(" PORTHOLE://pair?code=000042&host=box.example.ts.net%3A8737 "))
    }

    @Test fun `anything else is refused`() {
        assertNull(PairLink.parse("https://example.com/?host=a&code=123456"))
        assertNull(PairLink.parse("porthole://pair?host=192.0.2.10&code=12345"))
        assertNull(PairLink.parse("porthole://pair?host=&code=123456"))
        assertNull(PairLink.parse("porthole://pair?host=a/b&code=123456"))
        assertNull(PairLink.parse("porthole://open?host=a&code=123456"))
    }
}

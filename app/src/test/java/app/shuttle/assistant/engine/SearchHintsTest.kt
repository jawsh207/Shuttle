package app.shuttle.assistant.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchHintsTest {

    @Test fun currentQuestions() {
        assertTrue(SearchHints.isCurrent("who is the president of France"))
        assertTrue(SearchHints.isCurrent("what's the latest GrapheneOS release"))
        assertTrue(SearchHints.isCurrent("who won the game last night"))
        assertTrue(SearchHints.isCurrent("what's the price of bitcoin"))
        assertTrue(SearchHints.isCurrent("when does the new season of Severance come out"))
        assertTrue(SearchHints.isCurrent("best phones of 2026"))
        assertFalse(SearchHints.isCurrent("why is the sky blue"))
        assertFalse(SearchHints.isCurrent("quick pizza dough recipe"))
        assertFalse(SearchHints.isCurrent("who was the first president of the United States"))
    }

    @Test fun deferrals() {
        assertTrue(SearchHints.isDeferral("I don't have real-time information, so I can't tell you today's score."))
        assertTrue(SearchHints.isDeferral("As of my last update, the CEO was Jane Doe. I recommend checking their website."))
        assertTrue(SearchHints.isDeferral("I'm not sure. Would you like me to search the web for that?"))
        assertFalse(SearchHints.isDeferral("The sky looks blue because air scatters short wavelengths of sunlight more than long ones."))
        // A long, real answer with a closing tip isn't a deferral.
        assertFalse(SearchHints.isDeferral("Mix 500 g flour, 325 ml warm water, 7 g yeast and 10 g salt. ".repeat(10) + "For the latest tips, check a baking site."))
    }

    @Test fun searchingBack() {
        assertTrue(SearchHints.refersBack("it"))
        assertTrue(SearchHints.refersBack("that up"))
        assertFalse(SearchHints.refersBack("GrapheneOS"))
        assertEquals("who won the Super Bowl", SearchHints.queryFor("Hey Shuttle, can you tell me who won the Super Bowl?"))
    }
}

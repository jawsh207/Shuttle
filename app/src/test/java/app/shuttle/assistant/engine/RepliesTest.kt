package app.shuttle.assistant.engine

import app.shuttle.assistant.engine.Replies.Answer
import org.junit.Assert.assertEquals
import org.junit.Test

class RepliesTest {

    @Test fun clearAnswers() {
        assertEquals(Answer.YES, Replies.yesNo("Yes."))
        assertEquals(Answer.YES, Replies.yesNo("send it"))
        assertEquals(Answer.YES, Replies.yesNo("Okay!"))
        assertEquals(Answer.NO, Replies.yesNo("No"))
        assertEquals(Answer.NO, Replies.yesNo("cancel"))
        assertEquals(Answer.NO, Replies.yesNo("never mind"))
        assertEquals(Answer.NO, Replies.yesNo("Don’t send it"))
    }

    @Test fun anythingElseIsNotAnAnswer() {
        assertEquals(null, Replies.yesNo("yes but say I'm 10 minutes late"))
        assertEquals(null, Replies.yesNo("sure no"))
        assertEquals(null, Replies.yesNo("text Alex instead"))
        assertEquals(null, Replies.yesNo(""))
    }
}

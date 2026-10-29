package app.champion

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CloraDocumentTest {
    private val installation = UUID.randomUUID().toString()
    private fun event(n: Int, kind: Kind = Kind.OPEN) = Moment(
        id = UUID.nameUUIDFromBytes("test-$n-$kind".toByteArray()).toString(),
        owner = "white", at = 1_700_000_000_000L + n, kind = kind, packageName = "com.tencent.mm", appName = "微信",
    )

    @Test fun retriedUploadDoesNotDuplicateActivity() {
        val incoming = (1..100).map { event(it) }
        val head = CloraHead("white", installation).append(incoming).head
        val retry = head.append(incoming)
        assertEquals(head, retry.head)
        assertNull(retry.archive)
    }

    @Test fun rolloverKeepsEveryActivityAndCanBeRetried() {
        val original = (1..300).map { event(it) }
        val next = (301..400).map { event(it) }
        val head = CloraHead("white", installation, events = original)
        val plan = head.append(next)
        assertEquals(1, plan.head.archiveCount)
        assertEquals(original + next, CloraHead.events(plan.archive!!, "white") + plan.head.events)
        assertEquals(plan.head, head.append(next).head)
        assertEquals(plan.head, plan.head.append(next).head)
    }

    @Test fun heartbeatsStayOutOfHistoryAndNeverMoveBackward() {
        val latest = event(50, Kind.PRESENCE)
        val head = CloraHead("white", installation, events = (1..300).map { event(it) }, presence = latest)
        val plan = head.append(listOf(event(30, Kind.PRESENCE)))
        assertNull(plan.archive)
        assertEquals(latest, plan.head.presence)
        assertEquals(300, plan.head.events.size)
    }

    @Test fun encryptedDocumentRoundTripPreservesLocationAndChineseNames() {
        val location = event(1, Kind.LOCATION).copy(latitude = 31.2, longitude = 121.4, accuracy = 15.0, place = "测试地点")
        val head = CloraHead("white", installation, events = listOf(location), presence = event(2, Kind.PRESENCE))
        val codec = CloraCodec("test-only-token")
        assertEquals(head, CloraHead.parse(codec.decode(codec.encode(head.json())), "white"))
    }

    @Test fun clorastoreUsesFlatPrimitiveLists() {
        val json = CloraHead("white", installation, events = listOf(event(1))).json()
        assertTrue(json.getJSONArray("events").get(0) is String)
        assertTrue(json.get("presence") is String)
        assertEquals("微信", JSONObject(json.getJSONArray("events").getString(0)).getString("app"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun mixedIdentityIsRejectedBeforeUpload() {
        CloraHead("white", installation).append(listOf(event(1).copy(owner = "champion")))
    }
}

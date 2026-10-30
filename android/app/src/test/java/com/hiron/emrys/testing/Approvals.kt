package com.hiron.emrys.testing

import com.hiron.emrys.core.AlertBlock
import com.hiron.emrys.core.ApprovalRequest
import com.hiron.emrys.core.NoAllow
import com.hiron.emrys.core.RuleOffer
import com.hiron.emrys.core.RuleTarget
import com.hiron.emrys.core.Tier

/** An approval as the brain sends one for a text to [to]. */
fun textApproval(
    id: String = "a1",
    lane: String = "default",
    turn: String = "t1",
    to: String = "+18015550100",
    body: String = "Friday at 9 works!",
    because: String? = "You asked: “Text Dana that Friday at 9 works”",
    allow: Boolean = true,
    untrusted: Boolean = false,
): ApprovalRequest = ApprovalRequest(
    id = id,
    lane = lane,
    turnId = turn,
    tool = "sms_send",
    tier = Tier.Send,
    headline = "Text $to: “$body”",
    blocks = listOf(AlertBlock.Field("To", to), AlertBlock.Quote("Message", body)),
    because = because,
    preview = """{"to": "$to", "body": "$body"}""",
    untrustedTurn = untrusted,
    operations = emptyList(),
    offer = RuleOffer(
        RuleTarget("sms_send", mapOf("to" to to)), "Texts to $to", allow = allow && !untrusted, deny = true,
        noAllow = if (untrusted) NoAllow.Untrusted else null,
    ),
    expiresAt = null,
)

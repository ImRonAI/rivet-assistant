package com.hiron.ren.testing

import com.hiron.ren.core.ApprovalRequest
import com.hiron.ren.core.PermAction

/** A `request_approval` call for a text to Dana. */
fun textApproval(
    id: String = "a1",
    details: String = "Text Dana at +1 801-555-0100: “Friday at 9 works!”",
): ApprovalRequest = ApprovalRequest(id, PermAction.SendMessages, details)

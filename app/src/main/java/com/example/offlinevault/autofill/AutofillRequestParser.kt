package com.example.offlinevault.autofill

import android.service.autofill.FillRequest
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.text.InputType

/** Converts framework structures into a small, testable request projection. */
public class AutofillRequestParser {
    public fun parse(request: FillRequest): ParsedAutofillRequest? {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return null
        val nodes = buildList {
            repeat(structure.windowNodeCount) { index ->
                addAll(flatten(structure.getWindowNodeAt(index).rootViewNode))
            }
        }
        val usernameIds = nodes.filter(::isUsernameField).mapNotNull(ViewNodeProjection::id).distinct()
        val passwordIds = nodes.filter(::isPasswordField).mapNotNull(ViewNodeProjection::id).distinct()
        val fields = AutofillFieldIds(username = usernameIds, password = passwordIds)
        if (fields.isEmpty) return null
        return ParsedAutofillRequest(
            fieldIds = fields,
            webDomains = nodes.mapNotNull(ViewNodeProjection::webDomain).toSet(),
            clientPackageName = structure.activityComponent?.packageName,
            clientActivity = structure.activityComponent,
        )
    }

    private fun flatten(root: android.app.assist.AssistStructure.ViewNode): List<ViewNodeProjection> =
        buildList {
            add(root.projection())
            repeat(root.childCount) { index -> addAll(flatten(root.getChildAt(index))) }
        }

    private fun isUsernameField(node: ViewNodeProjection): Boolean {
        if (node.id == null) return false
        if (node.hints.any { it in USERNAME_HINTS }) return true
        return node.hints.isEmpty() && node.autofillType == View.AUTOFILL_TYPE_TEXT &&
            (node.inputType and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
    }

    private fun isPasswordField(node: ViewNodeProjection): Boolean {
        if (node.id == null) return false
        if (node.hints.any { it in PASSWORD_HINTS }) return true
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    }

    private companion object {
        val USERNAME_HINTS = setOf(
            "username",
            "email",
            "emailaddress",
            "newusername",
            "personname",
        )
        val PASSWORD_HINTS = setOf(
            "password",
            "currentpassword",
            "newpassword",
        )
    }
}

private data class ViewNodeProjection(
    val id: AutofillId?,
    val hints: Set<String>,
    val inputType: Int,
    val autofillType: Int,
    val webDomain: String?,
) {
    companion object {
        fun from(node: android.app.assist.AssistStructure.ViewNode): ViewNodeProjection = ViewNodeProjection(
            id = node.autofillId,
            hints = node.autofillHints.orEmpty().map(String::lowercase).toSet(),
            inputType = node.inputType,
            autofillType = node.autofillType,
            webDomain = node.webDomain,
        )
    }
}

private fun android.app.assist.AssistStructure.ViewNode.projection(): ViewNodeProjection =
    ViewNodeProjection.from(this)

private fun ViewNodeProjection.id(): AutofillId? = id
private fun ViewNodeProjection.webDomain(): String? = webDomain

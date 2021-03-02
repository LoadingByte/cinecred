package com.loadingbyte.cinecred.ui.styling

import com.loadingbyte.cinecred.common.Severity
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.ui.helper.Form
import com.loadingbyte.cinecred.ui.helper.TextWidget


fun l10nEnum(enumElem: Enum<*>) =
    l10n("project.${enumElem.javaClass.simpleName}.${enumElem.name}")


class StyleNameWidget : TextWidget() {

    override val verify = {
        val name = text.trim()
        if (name.isEmpty())
            throw Form.VerifyResult(Severity.ERROR, l10n("general.blank"))
        if (otherStyleNames.any { o -> o.equals(name, ignoreCase = true) })
            throw Form.VerifyResult(Severity.ERROR, l10n("ui.styling.duplicateStyleName"))
    }

    var otherStyleNames: List<String> = emptyList()

}

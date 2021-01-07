package com.kotme

import kotlin.script.experimental.api.SourceCode

class SourceCodeImp(override var text: String = "") : SourceCode {
    override val locationId: String? = null
    override val name: String? = null
}
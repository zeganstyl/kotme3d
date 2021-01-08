package com.kotme

class UserDesc constructor(var character: JonesImp, var name: String) {
    var thread: Thread? = null
        set(value) {
            field?.interrupt()
            field = value
        }

    fun reset() {
        thread = null
    }
}

package com.kotme

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.script.experimental.api.*
import kotlin.script.experimental.jvm.JvmDependencyFromClassLoader
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost

class Scripting(val location: LocationImp) {
    val os = ByteArrayOutputStream()
    val ps = PrintStream(os)
    val console = System.out

    val sourceCode = SourceCodeImp()
    val host = BasicJvmScriptingHost()
    val compilConf = ScriptCompilationConfiguration {
        dependencies(JvmDependencyFromClassLoader { LocationImp::class.java.classLoader })

        providedProperties.put(
            mapOf(
                "jones" to KotlinType(Jones::class),
                "location" to KotlinType(Location::class)
            )
        )
    }

    var scriptingInitiated = false

    init {
        Thread {
            sourceCode.text = "fun main(){}"
            val result = host.eval(
                sourceCode,
                ScriptCompilationConfiguration {},
                null
            )

            scriptingInitiated = true
            println("Scripting initiated")
            result.reports.forEach {
                println(it.render())
            }
        }.start()
    }

    fun print(block: () -> Unit) {
        val out = System.out
        System.setOut(console)
        block()
        System.setOut(out)
    }

    fun eval(code: String, user: String, userDesc: UserDesc) {
        if (scriptingInitiated) {
            val thread = Thread {
                try {
                    sourceCode.text = code

                    os.reset()
                    System.setOut(ps)

                    userDesc.character.blockThread = true
                    val result = host.eval(sourceCode, compilConf, ScriptEvaluationConfiguration {
                        providedProperties.put(
                            mapOf(
                                "jones" to userDesc.character,
                                "location" to location
                            )
                        )
                    })
                    userDesc.character.blockThread = false

                    System.setOut(console)

                    var output = os.toString()

                    if (output.trim() == "Привет Котлин!") {
                        userDesc.character.hello()
                        output += "\nКотлин Джонс:\nПривет!\n"
                    }
                    //session.send(output)

                    var errors = ""

                    when (result) {
                        is ResultWithDiagnostics.Failure -> {
                            val str = StringBuilder()
                            str.append("Ошибки компиляции кода\n")
                            result.reports.forEach {
                                str.append(it.render())
                                str.append('\n')
                            }
                            errors = str.toString()
                        }
                        is ResultWithDiagnostics.Success -> {
                            val returnValue = result.value.returnValue
                            if (returnValue is ResultValue.Error) {

                                errors = "Ошибки выполнения кода\n${returnValue.error::class.simpleName}: ${returnValue.error.message}"
                            }
                        }
                    }

                    location.sendToClient(MessageType.ClientGetEvalResult, user) {
                        if (output.isNotEmpty()) set("console", output)
                        if (errors.isNotEmpty()) set("errors", errors)
                    }
                } catch (ex: Exception) {
                    System.setOut(console)
                    ex.printStackTrace()
                }
            }
            userDesc.thread = thread
            thread.start()
        }
    }
}
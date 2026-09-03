package com.msp1974.vacompanion.device

import android.os.Build

enum class FunctionClasses {
    AUDIO_ENHANCEMENTS
}

class IssueDevice(
    var make: String,
    var model: String,
    var issues: List<FunctionClasses>,

)

class UnsupportedFunctionsDevice {

    companion object {
        private val issueDevices = listOf(
            IssueDevice(
                make = "lenovo",
                model = "tb-8505fs",
                issues = listOf(FunctionClasses.AUDIO_ENHANCEMENTS)
            ),
            // Portal+ has own very good audio handling
            IssueDevice(
                make = "facebook",
                model = "portal+",
                issues = listOf(FunctionClasses.AUDIO_ENHANCEMENTS)
            ),
        )


        fun isIssueDevice(functionClass: FunctionClasses): Boolean {
            val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
            val model = Build.MODEL.orEmpty().lowercase()

            for (device in issueDevices) {
                if (functionClass in device.issues) {
                    if (manufacturer == device.make && model == device.model) {
                        return true
                    }
                }
            }
            return false
        }
    }
}
package com.sal7one.transiber.downloads

import java.io.File

internal data class Aria2Runtime(val executable: File, val environment: Map<String, String>)

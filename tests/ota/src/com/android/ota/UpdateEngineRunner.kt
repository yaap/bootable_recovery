/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.ota

import com.android.tradefed.device.ITestDevice
import com.android.tradefed.util.CommandStatus

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue

class UpdateEngineRunner(val device: ITestDevice, val file: File) {
    private lateinit var zip: ZipFile

    init {
        zip = ZipFile(file)
    }

    public fun run(slotSwitch: Boolean, powerWash: Boolean) {
        val otaPath = "/data/ota_package/payload.bin"
        val tempPath = "/data/local/tmp/payload.bin"

        // Push payload.bin and give it the right permissions.
        val payload = extractFile(zip, "payload.bin")
        assertTrue(device.pushFile(payload, tempPath))
        adbShell("su 0 mv $tempPath $otaPath")
        adbShell("su 0 chcon u:object_r:ota_package_file:s0 $otaPath")
        adbShell("su 0 chown system:cache $otaPath")
        adbShell("su 0 chmod 0660 $otaPath")

        val properties = extractFile(zip, "payload_properties.txt")
        var headers = properties.readBytes().toString(Charsets.UTF_8)
        headers += "USER_AGENT=Dalvik (something, something)\n"
        headers += "NETWORK_ID=0\n"

        if (!slotSwitch) {
            headers += "SWITCH_SLOT_ON_REBOOT=0\n"
        }
        if (powerWash) {
            headers += "POWERWASH=1\n"
        }

        val payloadSize = payload.length()
        val args = arrayOf("shell", "su", "0", "update_engine_client", "--update", "--follow",
                           "--payload=file://$otaPath", "--offset=0", "--size=$payloadSize",
                           "--headers=\"$headers\"")
        val cr = device.executeAdbV2Command(*args)
        assertEquals(cr.status, CommandStatus.SUCCESS)
    }

    private fun adbShell(cmd: String) {
        val cr = device.executeShellV2Command(cmd)
        assertEquals("adb shell $cmd", CommandStatus.SUCCESS, cr.status)
    }
}

private fun extractFile(zip: ZipFile, file: String): File {
    val entry = zip.getEntry(file)
    assertNotEquals(entry, null)
    assertFalse(entry.isDirectory())

    val temp = File.createTempFile("payload", ".bin")
    FileOutputStream(temp).use { out_file ->
        zip.getInputStream(entry).use { in_file ->
            // Our test packages are small, this should be ok.
            out_file.write(in_file.readBytes())
        }
    }
    temp.deleteOnExit()
    return temp
}

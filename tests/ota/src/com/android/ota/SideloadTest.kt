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

import android.cts.install.lib.host.InstallUtilsHost

import com.android.ddmlib.TimeoutException
import com.android.tradefed.build.IBuildInfo
import com.android.tradefed.device.DeviceNotAvailableException
import com.android.tradefed.device.NativeDevice
import com.android.tradefed.device.ITestDevice
import com.android.tradefed.device.TestDeviceState
import com.android.tradefed.result.ByteArrayInputStreamSource
import com.android.tradefed.result.FileInputStreamSource
import com.android.tradefed.result.LogDataType
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner.TestLogData
import com.android.tradefed.testtype.IBuildReceiver
import com.android.tradefed.testtype.IDeviceTest
import com.android.tradefed.testtype.IRemoteTest
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test
import com.android.tradefed.util.CommandStatus
import com.android.tradefed.util.RunUtil

import java.io.File

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith


private val SIDELOAD_TIMEOUT: Long = 1000 * 60
private val WAIT_FOR_MERGE_TIMEOUT = 120.seconds
private val PARTITION_NAME = "sideload_test"
private val DONT_COMMIT_CHECKPOINT_PROP = "persist.vold.dont_commit_checkpoint"
private val BLOCK_MERGE_SWITCHOVER_PROP = "persist.virtual_ab.testing.block_merge_switchover"

@RunWith(DeviceJUnit4ClassRunner::class)
class AbSideloadTest :  BaseHostJUnit4Test() {
    private val mInstallUtils = InstallUtilsHost(this)
    private var mRecoveryLog: File? = null
    private var mDmesg: String? = null
    private var mFailed: Boolean = false

    @Before
    public fun setUp() {
        finishOrCancelUpdate()

        // Just in case it already exists, zero it out, to make sure that the
        // OTA writes new data.
        assertTrue(device.enableAdbRoot())
        wipeSideloadPartition()

        // Clear any properties that might break tests.
        resetPersistProps()
    }

    @get:Rule(order=0) val watcher = object : TestWatcher() {
        override fun starting(description: Description) {
            mFailed = false
        }
        override fun failed(e: Throwable, description: Description) {
            mFailed = true
        }
        override fun finished(description: Description) {
            if (mFailed) {
                exportRecoveryLogs(description.methodName)
            }
        }
    }
    @get:Rule(order=1) val metrics = TestLogData()

    @After
    public fun tearDown() {
        if (device.deviceState == TestDeviceState.SIDELOAD || device.deviceState == TestDeviceState.RECOVERY) {
            device.rebootUntilOnline()
        }

        // Clear any properties that might break tests.
        resetPersistProps()
    }

    // End to end sideload test.
    @Test
    public fun sideloadOta() {
        val slot = readSlotSuffix()
        device.rebootIntoSideload()
        runAdbSideload("sideload_test_1.zip")
        device.rebootUntilOnline()

        val new_slot = readSlotSuffix()
        assertEquals(new_slot, getOtherSlot(slot))

        assertTrue(device.enableAdbRoot())
        verifySideloadedTestPartition("sideload_test_1.img", new_slot)
    }

    // Apply an OTA, but immediately reboot into recovery and overwrite it with
    // a different OTA, and verify that the second OTA was written correctly.
    @Test
    public fun sideloadWithUnverifiedOtaNoReboot() {
        runUpdateEngine("sideload_test_2.zip")
        device.rebootIntoSideload()
        runAdbSideload("sideload_test_1.zip")
        device.rebootUntilOnline()
        verifySideloadedTestPartition("sideload_test_1.img")
    }

    // Same as above, but reboot into the new slot and don't get to boot_complete.
    @Test
    public fun sideloadWithUnverifiedOtaPostReboot() {
        device.setProperty(DONT_COMMIT_CHECKPOINT_PROP, "1")
        runUpdateEngine("sideload_test_2.zip")
        device.rebootUntilOnline()
        assertEquals(getUpdateState(), "unverified")
        device.rebootIntoSideload()
        runAdbSideload("sideload_test_1.zip")
        device.rebootUntilOnline()
        verifySideloadedTestPartition("sideload_test_1.img")
    }

    // Apply an OTA, then reboot in the middle of a merge. Sideloading should
    // still work.
    @Test
    public fun sideloadDuringMerge() {
        device.setProperty(BLOCK_MERGE_SWITCHOVER_PROP, "1")
        runUpdateEngine("sideload_test_2.zip")
        device.rebootUntilOnline()
        waitForCondition("merge started", WAIT_FOR_MERGE_TIMEOUT, 1.seconds) {
            getUpdateState() == "merging"
        }
        device.rebootIntoSideload()
        runAdbSideload("sideload_test_1.zip")
        device.rebootUntilOnline()
        verifySideloadedTestPartition("sideload_test_1.img")
    }

    // Apply an OTA, but don't switch slots. Sideload should still work.
    @Test
    public fun sideloadAfterOtaWithoutSlotSwitch() {
        val oldSlot = readSlotSuffix()
        runUpdateEngine("sideload_test_2.zip", slotSwitch = false)
        device.rebootIntoRecovery()
        assertEquals(oldSlot, readSlotSuffix())
        device.rebootIntoSideload()
        runAdbSideload("sideload_test_1.zip")
        device.rebootUntilOnline()
        verifySideloadedTestPartition("sideload_test_1.img")
    }

    // Factory reset during a merge should force a merge in recovery. This is
    // not really a sideload test but it involves much of the recovery
    // machinery.
    @Test
    public fun mergeInRecoveryForDataWipe() {
        device.setProperty(BLOCK_MERGE_SWITCHOVER_PROP, "1")
        runUpdateEngine("sideload_test_2.zip")
        device.rebootUntilOnline()
        waitForCondition("merge started", WAIT_FOR_MERGE_TIMEOUT, 1.seconds) {
            getUpdateState() == "merging"
        }
        performWipe()
        assertEquals("none", getUpdateState())
        verifySideloadedTestPartition("sideload_test_2.img")
    }

    // Test powerwashing via OTA. This is effectively a factory reset test,
    // although the flow is slightly different since rollback is not allowed.
    @Test
    public fun mergeInRecoveryForPowerwash() {
        runUpdateEngine("sideload_test_1.zip", powerWash = true)
        device.rebootUntilOnline()
        assertEquals("none", getUpdateState())
        verifySideloadedTestPartition("sideload_test_1.img")
    }

    private fun verifySideloadedTestPartition(imageFile: String, slot: String? = null) {
        device.enableAdbRoot()
        val slotSuffix = (if (slot != null) slot else readSlotSuffix())
        val partition_path = "/dev/block/mapper/" + PARTITION_NAME + slotSuffix
        assertTrue(deviceFileExists(partition_path))
        val partition_file = device.pullFile(partition_path)
        assertNotEquals("pull $partition_path", null, partition_file)
        val partition_bytes = partition_file.readBytes()
        val canonical_file = getTestFile(imageFile)
        val canonical_bytes = canonical_file.readBytes()
        assertEquals(partition_bytes.size, canonical_bytes.size)
        assertTrue(partition_bytes contentEquals canonical_bytes)
    }

    private fun runUpdateEngine(pkg: String, slotSwitch: Boolean = true, powerWash: Boolean = false) {
        // This is copied from update_device.py.
        val file = mInstallUtils.getTestFile(pkg)
        val runner = UpdateEngineRunner(device, file)
        runner.run(slotSwitch = slotSwitch, powerWash = powerWash)
        if (slotSwitch) {
            assertEquals("unverified", getUpdateState())
        } else {
            assertEquals("initiated", getUpdateState())
        }
    }

    private fun runAdbSideload(pkg: String): String {
        val file = mInstallUtils.getTestFile(pkg)
        val oldTimeout = device.options.adbCommandTimeout
        device.options.setAdbCommandTimeout(SIDELOAD_TIMEOUT)
        try {
            return device.executeAdbCommand("sideload", file.getPath())
        } finally {
            device.options.setAdbCommandTimeout(oldTimeout)
            pullRecoveryLog()
        }
    }

    private fun performWipe() {
        device.setProperty("debug.before_factory_reset", "1")
        val cr = device.executeShellV2Command("am broadcast -a android.intent.action.MASTER_CLEAR -n android/com.android.server.MasterClearReceiver")
        if (cr.status != CommandStatus.SUCCESS) {
            // adb can disconnect quickly and we get a misnomer error code instead.
            if (!(cr.status == CommandStatus.FAILED && cr.exitCode == 255)) {
                throw Exception("Broadcast for factory reset failed")
            }
        }
        waitForCondition("factory reset", 300.seconds, 1.seconds) {
            !inRecovery() && getPropertyUncached("debug.before_factory_reset") != "1"
        }
    }

    private fun resetPersistProps() {
        device.setProperty(DONT_COMMIT_CHECKPOINT_PROP, "")
        device.setProperty(BLOCK_MERGE_SWITCHOVER_PROP, "")
    }

    private fun exportRecoveryLogs(name: String) {
        if (mRecoveryLog != null) {
            metrics.addTestLog("$name-recovery.log", LogDataType.LOGCAT, FileInputStreamSource(mRecoveryLog!!))
        }
        if (mDmesg != null) {
            metrics.addTestLog("$name-dmesg", LogDataType.TEXT, ByteArrayInputStreamSource(mDmesg!!.toByteArray()))
        }
    }

    private fun pullRecoveryLog() {
        try {
            // Transition from adb sideload to recovery.
            reconnectInRecovery()
            // Get root.
            enableAdbRootInRecovery()
            // Pull interesting logs.
            mRecoveryLog = device.pullFile("/tmp/recovery.log")
            mDmesg = device.executeShellCommand("dmesg")
        } finally {
        }
    }

    private fun enableAdbRootInRecovery() {
        // enableAdbRoot() can time out, so roll our own implementation here.
        device.executeAdbCommand("root")
        if (!device.waitForDeviceNotAvailable(device.options.adbRootUnavailableTimeout)) {
            if (device.isAdbRoot()) {
                return
            }
        }
        reconnectInRecovery()
    }

    private fun reconnectInRecovery() {
        device.connection.reconnect(device.serialNumber)
        device.waitForDeviceInRecovery(SIDELOAD_TIMEOUT)
    }

    private fun deviceFileExists(path: String): Boolean {
        val cr = device.executeShellV2Command("ls " + path)
        return cr.status == CommandStatus.SUCCESS
    }

    private fun wipeSideloadPartition() {
        // See if we have a sideload_test partition.
        val partition_name = PARTITION_NAME + readSlotSuffix()
        if (!deviceFileExists("/dev/block/mapper/$partition_name")) {
            return
        }
        // If so, we need to create an rw view to write to.
        val rw_name = partition_name + "-rw"
        val rw_path = "/dev/block/mapper/$rw_name"
        if (!deviceFileExists(rw_path)) {
            adbShell("dmctl create-from-super $partition_name $rw_name")
        }
        val size = device.executeShellCommand("blockdev --getsize64 $rw_path").trim()
        adbShell("dd bs=1 count=$size if=/dev/zero of=$rw_path")
    }

    private fun finishOrCancelUpdate() {
        waitForCondition("finish update", 120.seconds, 1.seconds) {
            val state = getUpdateState()
            if (state == "none") {
                return@waitForCondition true
            }
            if (state == "unverified") {
                if (getOtaBootState() == "source") {
                    device.rebootUntilOnline()
                    return@waitForCondition getUpdateState() == "none"
                }
                // We'll have to wait for the merge to start and then complete.
            } else if (state == "merging") {
                RunUtil.getDefault().sleep(1000L)
            } else if (state == "initiated") {
                adbShell("su 0 snapshotctl cancel")
            } else {
                throw Exception("Unexpected update state: $state")
            }
            return@waitForCondition false
        }
    }

    private fun waitForCondition(message: String, timeout: Duration, sleep: Duration, cond: () -> Boolean) {
        val start = TimeSource.Monotonic.markNow()
        val limit = start + timeout
        while (true) {
            if (cond()) {
                return
            }
            if (TimeSource.Monotonic.markNow() >= limit) {
                throw TimeoutException("timed out: $message")
            }
            if (sleep.isPositive()) {
                RunUtil.getDefault().sleep(sleep.inWholeMilliseconds)
            }
        }
    }

    private fun getUpdateState(): String {
        val regex = "Update state: ([^\\s]+)".toRegex()

        val result = adbShell("su 0 snapshotctl dump")
        val match = regex.find(result)
        if (match == null) {
            return "none"
        }
        return match.groupValues[1]
    }

    private fun getOtaBootState(): String {
        val regex = "Boot indicator: booting from ([^\\s]+) slot".toRegex()

        val result = adbShell("su 0 snapshotctl dump")
        val match = regex.find(result)
        if (match == null) {
            return "none"
        }
        if (match.groupValues[1] == "unknown") {
            return "none"
        }
        return match.groupValues[1]
    }

    // Bypass device.getProperty which does internal caching. We reboot a lot
    // without ITestDevice internally understanding that, so make sure we
    // always get live data.
    private fun getPropertyUncached(key: String): String {
        val nativeDevice = device as NativeDevice?
        if (nativeDevice != null) {
            nativeDevice.invalidatePropertyCache()
        }
        val prop = device.getProperty(key);
        if (prop == null) {
            return ""
        }
        return prop
    }

    private fun adbShell(cmd: String): String {
        val cr = device.executeShellV2Command(cmd)
        assertEquals("adb shell $cmd", CommandStatus.SUCCESS, cr.status)
        return cr.stdout
    }

    private fun getTestFile(name: String): File {
        val file = mInstallUtils.getTestFile(name)
        assertTrue(file.exists());
        return file
    }

    private fun readSlotSuffix(): String {
        val output = device.executeShellCommand("getprop ro.boot.slot_suffix").trim()
        assertTrue("Slot suffix is _a or _b", output == "_a" || output == "_b")
        return output
    }

    protected fun inRecovery(): Boolean {
        return device.getProperty("ro.boot.mode") == "recovery"
    }

    private fun getOtherSlot(suffix: String): String {
        return when (suffix) {
            "_a" -> "_b"
            "_b" -> "_a"
            else -> throw Exception("Invalid suffix: $suffix")
        }
    }
}

/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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

#ifndef _BOOTLOADER_MESSAGE_H
#define _BOOTLOADER_MESSAGE_H

#include "bootloader_message_core.h"

#ifdef __cplusplus

#include <string>
#include <vector>

// Gets the block device name of /misc partition.
std::string get_misc_blk_device(std::string* err);
// Return the block device name for the bootloader message partition and waits
// for the device for up to 10 seconds. In case of error returns the empty
// string.
std::string get_bootloader_message_blk_device(std::string* err);

// Writes |size| bytes of data from buffer |p| to |misc_blk_device| at |offset|. If the write fails,
// sets the error message in |err|.
bool write_misc_partition(const void* p, size_t size, const std::string& misc_blk_device,
                          size_t offset, std::string* err);

// Read bootloader message into boot. Error message will be set in err.
bool read_bootloader_message(bootloader_message* boot, std::string* err);

// Read bootloader message from the specified misc device into boot.
bool read_bootloader_message_from(bootloader_message* boot, const std::string& misc_blk_device,
                                  std::string* err);

// Write bootloader message to BCB.
bool write_bootloader_message(const bootloader_message& boot, std::string* err);

// Write bootloader message to the specified BCB device.
bool write_bootloader_message_to(const bootloader_message& boot,
                                 const std::string& misc_blk_device, std::string* err);

// Write bootloader message (boots into recovery with the options) to BCB. Will
// set the command and recovery fields, and reset the rest.
bool write_bootloader_message(const std::vector<std::string>& options, std::string* err);

// Write bootloader message (boots into recovery with the options) to the specific BCB device. Will
// set the command and recovery fields, and reset the rest.
bool write_bootloader_message_to(const std::vector<std::string>& options,
                                 const std::string& misc_blk_device, std::string* err);

// Update bootloader message (boots into recovery with the options) to BCB. Will
// only update the command and recovery fields.
bool update_bootloader_message(const std::vector<std::string>& options, std::string* err);

// Update bootloader message (boots into recovery with the |options|) in |boot|. Will only update
// the command and recovery fields.
bool update_bootloader_message_in_struct(bootloader_message* boot,
                                         const std::vector<std::string>& options);

// Clear BCB.
bool clear_bootloader_message(std::string* err);

// Writes the reboot-bootloader reboot reason to the bootloader_message.
bool write_reboot_bootloader(std::string* err);

// Read the wipe package from BCB (from offset WIPE_PACKAGE_OFFSET_IN_MISC).
bool read_wipe_package(std::string* package_data, size_t size, std::string* err);

// Write the wipe package into BCB (to offset WIPE_PACKAGE_OFFSET_IN_MISC).
bool write_wipe_package(const std::string& package_data, std::string* err);

// Read or write the Virtual A/B message from system space in /misc.
bool ReadMiscVirtualAbMessage(misc_virtual_ab_message* message, std::string* err);
bool WriteMiscVirtualAbMessage(const misc_virtual_ab_message& message, std::string* err);

// Read or write the memtag message from system space in /misc.
bool ReadMiscMemtagMessage(misc_memtag_message* message, std::string* err);
bool WriteMiscMemtagMessage(const misc_memtag_message& message, std::string* err);

// Read or write the kcmdline message from system space in /misc.
bool ReadMiscKcmdlineMessage(misc_kcmdline_message* message, std::string* err);
bool WriteMiscKcmdlineMessage(const misc_kcmdline_message& message, std::string* err);

// Read or write the kcmdline message from system space in /misc.
bool ReadMiscControlMessage(misc_control_message* message, std::string* err);
bool WriteMiscControlMessage(const misc_control_message& message, std::string* err);

// Check reserved system space.
bool CheckReservedSystemSpaceEmpty(bool* empty, std::string* err);

#else

#include <stdbool.h>

// C Interface.
bool write_bootloader_message(const char* options);
bool write_reboot_bootloader(void);

#endif  // ifdef __cplusplus

#endif  // _BOOTLOADER_MESSAGE_H

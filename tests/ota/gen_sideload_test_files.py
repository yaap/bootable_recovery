#!/usr/bin/env python
#
# Copyright (C) 2025 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""
Create test packages for adb sideload. Each test image should be a 4KB-aligned
image. Each output package will be a partial non-incremental OTA that can be
applied on top of the current build. The partial OTA adds a new "sideload_test"
partition that can be erased, removed, or modified without causing verified
boot issues. The sideload_test partition is automatically removed when the
device is reflashed or takes another OTA.
"""

import argparse
import logging
import os
import subprocess
import sys
import tempfile

import ota_metadata_pb2


def ParseBuildProps(fp):
    props = {}
    for line in fp.readlines():
        if line.startswith('#'):
            continue
        parts = line.split('=', 1)
        if len(parts) == 2:
            props[parts[0]] = parts[1].strip()
    return props


def GetDeviceName(props):
    device_name_props = [
        "ro.build.product",
        "ro.product.device",
        "ro.product.product.device",
        "ro.product.vendor.device",
        "ro.product.system.device",
    ]
    for device_name_prop in device_name_props:
        if device_name_prop in props:
            return props[device_name_prop]
    raise Exception("Could not find device name props in build.prop file")


def GeneratePackages(args):
    package_key = args.package_key
    if package_key.endswith(".pk8"):
        package_key = package_key.removesuffix(".pk8")

    with open(args.build_prop_file, 'r') as fp:
        props = ParseBuildProps(fp)

    for data_file in args.data:
        # device_common_srcs leaks into this list.
        if not data_file.endswith(".img"):
            continue

        image_prefix, _ = os.path.splitext(os.path.basename(data_file))

        if os.path.getsize(data_file) % 4096 != 0:
            raise Exception("{} is not aligned correctly".format(data_file))

        metadata = ota_metadata_pb2.OtaMetadata()
        metadata.type = ota_metadata_pb2.OtaMetadata.AB
        if image_prefix.endswith("_wipe"):
            metadata.wipe = True
        metadata.spl_downgrade = True
        metadata.precondition.device.append(GetDeviceName(props))
        metadata.postcondition.security_patch_level = props["ro.build.version.security_patch"]
        metadata.postcondition.timestamp = int(props["ro.build.date.utc"])

        metadata_fp = tempfile.NamedTemporaryFile("w+b")
        metadata_fp.write(metadata.SerializeToString())
        metadata_fp.flush()

        zip_path = os.path.join(args.out, image_prefix + ".zip")
        cmd = [
            args.ota_from_raw_img_path,
            data_file,
            "--output", zip_path,
            "--partition_names", "sideload_test",
            "--package_key", package_key,
            "--metadata_proto_file", metadata_fp.name,
            "--dynamic_partition_info_file", args.dynamic_partitions_info_file,
            "--delta_generator_path", args.delta_generator_path,
        ]
        logging.info("Running command: {}".format(cmd))
        subprocess.check_output(cmd)


def main(argv):
    parser = argparse.ArgumentParser()
    parser.add_argument("data", type=str, nargs="+", help="List of input files")
    parser.add_argument("--out", type=str, required=True, help="Output directory")
    parser.add_argument("--package_key", type=str, required=True)
    parser.add_argument("--dynamic_partitions_info_file", type=str, required=True)
    parser.add_argument("--ota_from_raw_img_path", type=str, required=True)
    parser.add_argument("--delta_generator_path", type=str, required=True)
    parser.add_argument("--build_prop_file", type=str, required=True)
    args = parser.parse_args()

    GeneratePackages(args)


if __name__ == "__main__":
    logging.basicConfig()
    main(sys.argv)

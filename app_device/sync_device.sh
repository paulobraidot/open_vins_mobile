#!/bin/bash

# Script to sync app_device files to Android device
# Usage: ./sync_device.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DEVICE_DIR="${SCRIPT_DIR}"
DEVICE_PATH="/storage/emulated/0/Documents/openvins"

# Check if we're in the app_device directory (should have config subdirectory)
if [ ! -d "${APP_DEVICE_DIR}/config" ]; then
    echo "Error: config directory not found. This script should be run from app_device directory."
    exit 1
fi

# Find adb in common locations
ADB=""
if command -v adb &> /dev/null; then
    ADB="adb"
elif [ -n "${ANDROID_HOME}" ]; then
    ADB="${ANDROID_HOME}/platform-tools/adb"
elif [ -n "${ANDROID_SDK_ROOT}" ]; then
    ADB="${ANDROID_SDK_ROOT}/platform-tools/adb"
else
    # Check Android SDK location (default installation path)
    sdk_adb="${HOME}/Android/Sdk/platform-tools/adb"
    if [ -f "${sdk_adb}" ] && [ -x "${sdk_adb}" ]; then
        ADB="${sdk_adb}"
    else
        echo "Error: adb not found. Please ensure Android SDK platform-tools is in PATH or set ANDROID_HOME/ANDROID_SDK_ROOT"
        echo "Or ensure ~/Android/Sdk/platform-tools/adb exists"
        exit 1
    fi
fi

# Check if device is connected
echo "Checking for connected devices..."
DEVICES=$(${ADB} devices | grep -v "List" | grep "device$" | wc -l)
if [ "${DEVICES}" -eq 0 ]; then
    echo "Error: No Android device connected. Please connect a device and enable USB debugging."
    exit 1
fi

echo "Found ${DEVICES} device(s)"

# App package name
APP_PACKAGE="com.openvins.android"
# Private external files directory (app has full access)
PRIVATE_CONFIG_PATH="/storage/emulated/0/Android/data/${APP_PACKAGE}/files/config"

echo "Syncing ${APP_DEVICE_DIR} to ${DEVICE_PATH} on device..."
echo "Also syncing config files to private directory: ${PRIVATE_CONFIG_PATH}"

# Create the target directory on device (public)
${ADB} shell mkdir -p "${DEVICE_PATH}"

# Create private config directory (may fail if app not installed, that's ok)
${ADB} shell mkdir -p "${PRIVATE_CONFIG_PATH}" 2>/dev/null || echo "Note: Private directory creation may have failed (app may not be installed yet)"

# Push all files from app_device to the device (public directory)
find "${APP_DEVICE_DIR}" -type f | while read -r file; do
    # Get relative path from app_device directory
    # Remove the app_device directory prefix
    rel_path="${file#${APP_DEVICE_DIR}/}"
    target_dir="${DEVICE_PATH}/$(dirname "${rel_path}")"
    
    # Normalize target_dir (remove double slashes, etc.)
    target_dir=$(echo "${target_dir}" | sed 's|//|/|g')
    
    # Create target directory on device
    ${ADB} shell mkdir -p "${target_dir}"
    
    # Push the file
    ${ADB} push "${file}" "${target_dir}/"
done

# Push config files to private directory (app can access these without permissions)
if [ -d "${APP_DEVICE_DIR}/config" ]; then
    echo "Pushing config files to private directory..."
    ${ADB} shell mkdir -p "${PRIVATE_CONFIG_PATH}" 2>/dev/null || true
    for config_file in "${APP_DEVICE_DIR}"/config/*.yaml; do
        if [ -f "${config_file}" ]; then
            config_name=$(basename "${config_file}")
            echo "  Pushing ${config_name} to private directory..."
            ${ADB} push "${config_file}" "${PRIVATE_CONFIG_PATH}/" 2>/dev/null || \
                echo "    Warning: Failed to push ${config_name} to private directory (app may not be installed)"
        fi
    done
fi

echo "Successfully synced files to device!"
echo "Files are available at: ${DEVICE_PATH}"
echo "Config files are also available at: ${PRIVATE_CONFIG_PATH}"


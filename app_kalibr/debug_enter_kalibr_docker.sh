#!/bin/bash

# Debug script to enter Kalibr Docker container interactively
# Usage: ./app_kalibr/debug_enter_kalibr_docker.sh [directory_to_mount]
#
# This script mounts the current directory (or specified directory) to /data
# and enters the container in interactive mode for debugging.

set -e

# Get script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# Default values
KALIBR_IMAGE="kalibr"
DATA_MOUNT="/data"

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

# Check if Docker image exists
if ! docker images | grep -q "^${KALIBR_IMAGE} "; then
    print_warn "Docker image '${KALIBR_IMAGE}' not found."
    echo "Please run: ./app_kalibr/step00_kalibr_setup_docker.sh"
    exit 1
fi

# Get directory to mount (default: current directory)
MOUNT_DIR="${1:-$(pwd)}"
MOUNT_DIR=$(realpath "${MOUNT_DIR}")

if [ ! -d "${MOUNT_DIR}" ]; then
    print_warn "Directory does not exist: ${MOUNT_DIR}"
    print_info "Using project root instead: ${PROJECT_ROOT}"
    MOUNT_DIR="${PROJECT_ROOT}"
fi

print_info "Entering Kalibr Docker container..."
print_info "  Image: ${KALIBR_IMAGE}"
print_info "  Mounting: ${MOUNT_DIR} -> ${DATA_MOUNT}"
print_info "  Working directory: ${DATA_MOUNT}"
echo ""
print_info "Inside the container, you can:"
print_info "  1. Source the workspace: source /catkin_ws/devel/setup.bash"
print_info "  2. Run Kalibr commands: rosrun kalibr <command>"
print_info "  3. Files in ${MOUNT_DIR} are available at ${DATA_MOUNT}"
echo ""
print_info "Type 'exit' to leave the container"
echo ""

# Enable X11 forwarding for GUI applications
xhost +local:root 2>/dev/null || true

# Enter container interactively
docker run --rm -it \
    -e "DISPLAY" \
    -e "QT_X11_NO_MITSHM=1" \
    -v "/tmp/.X11-unix:/tmp/.X11-unix:rw" \
    -v "${MOUNT_DIR}:${DATA_MOUNT}" \
    -w "${DATA_MOUNT}" \
    "${KALIBR_IMAGE}" \
    bash -c "echo '=== Kalibr Docker Container ==='; \
         echo 'Workspace: /catkin_ws'; \
         echo 'Mounted directory: ${DATA_MOUNT}'; \
         echo 'Current directory: \$(pwd)'; \
         echo ''; \
         echo 'To source workspace, run:'; \
         echo '  source /catkin_ws/devel/setup.bash'; \
         echo ''; \
         echo 'To test kalibr commands:'; \
         echo '  source /catkin_ws/devel/setup.bash'; \
         echo '  rosrun kalibr kalibr_create_target_pdf --help'; \
         echo ''; \
         exec bash"


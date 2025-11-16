#!/bin/bash

# Create Kalibr calibration target PDF and YAML
# Usage: ./step01_kalibr_target_create.sh <date-device-name> [nx] [ny] [tsize] [tspace] [output_name]

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KALIBR_IMAGE="kalibr"
DATA_MOUNT="/data"

# Check Docker image
if ! docker image inspect "${KALIBR_IMAGE}" &> /dev/null; then
    echo "ERROR: Docker image '${KALIBR_IMAGE}' not found. Run step00_kalibr_setup_docker.sh first."
    exit 1
fi

# Parse arguments
device_folder="${1:-}"
if [ -z "${device_folder}" ]; then
    echo "Usage: $0 <date-device-name> [nx] [ny] [tsize] [tspace] [output_name]"
    exit 1
fi

nx="${2:-4}"
ny="${3:-5}"
tsize="${4:-0.044}"
tspace="${5:-0.1}"
output_name="${6:-target}"

# Setup paths
output_dir="${SCRIPT_DIR}/${device_folder}"
output_dir_abs=$(realpath "${output_dir}")
mkdir -p "${output_dir}"

final_pdf_name="${output_name}.pdf"
final_yaml_name="${output_name}.yaml"
script_name="kalibr_target_creation.sh"
log_name="kalibr_target_creation.log"

# Create script file
script_file="${output_dir}/${script_name}"
cat > "${script_file}" << EOF
#!/bin/bash
set -e
cd "${DATA_MOUNT}"

echo "=== Kalibr Target Creation ==="
echo "Date: \$(date)"
echo "Parameters: nx=${nx}, ny=${ny}, tsize=${tsize}, tspace=${tspace}"
echo ""

source /catkin_ws/devel/setup.bash

echo "Creating PDF..."
echo "Command: rosrun kalibr kalibr_create_target_pdf --type apriltag --nx ${nx} --ny ${ny} --tsize ${tsize} --tspace ${tspace}"
rosrun kalibr kalibr_create_target_pdf --type apriltag --nx ${nx} --ny ${ny} --tsize ${tsize} --tspace ${tspace} 2>&1
echo "PDF creation completed (exit code: \$?)"
echo ""

if [ "target.pdf" != "${final_pdf_name}" ]; then
    echo "Renaming target.pdf to ${final_pdf_name}..."
    mv target.pdf ${final_pdf_name}
fi

echo "Creating YAML..."
printf "target_type: 'aprilgrid'\ntagCols: %s\ntagRows: %s\ntagSize: %s\ntagSpacing: %s\n" ${nx} ${ny} ${tsize} ${tspace} > ${final_yaml_name}
echo "YAML file created: ${final_yaml_name}"
echo ""

echo "Verifying files..."
if [ -f ${final_pdf_name} ] && [ -f ${final_yaml_name} ]; then
    ls -lh ${final_pdf_name} ${final_yaml_name}
    echo "SUCCESS: Both files created"
else
    echo "ERROR: Files missing!"
    ls -la
    exit 1
fi
EOF

chmod +x "${script_file}"

# Run Docker
xhost +local:root 2>/dev/null || true
docker run --rm \
    --entrypoint="" \
    -e "DISPLAY" \
    -e "QT_X11_NO_MITSHM=1" \
    -v "/tmp/.X11-unix:/tmp/.X11-unix:rw" \
    -v "${output_dir_abs}:${DATA_MOUNT}" \
    -w "${DATA_MOUNT}" \
    "${KALIBR_IMAGE}" \
    bash "${DATA_MOUNT}/${script_name}" 2>&1 | tee "${output_dir}/${log_name}"

# Verify files were created
if [ ! -f "${output_dir}/${final_pdf_name}" ] || [ ! -f "${output_dir}/${final_yaml_name}" ]; then
    echo "ERROR: Files were not created"
    exit 1
fi

echo "SUCCESS: Created ${final_pdf_name} and ${final_yaml_name} in ${output_dir}"

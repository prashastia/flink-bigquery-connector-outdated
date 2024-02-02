#!/bin/bash

# Copyright 2022 Google Inc. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the 'License');
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#            http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an 'AS IS' BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -euxo pipefail

if [ -z "${CODECOV_TOKEN}" ]; then
  echo "missing environment variable CODECOV_TOKEN"
  exit 1
fi

readonly MVN="./mvnw -B -e -s /workspace/cloudbuild/presubmit/gcp-settings.xml -Dmaven.repo.local=/workspace/.repository"
readonly STEP=$1

cd /workspace

case $STEP in
  # Download maven and all the dependencies
  init)
    $MVN clean install -DskipTests -Pflink_1.17.1
    exit
    ;;

  # Run unit & integration tests
  tests)
    $MVN clean clover:setup verify clover:aggregate clover:clover clover:check -Pclover_flink_1.17.1
    ;;

  *)
    echo "Unknown step $STEP"
    exit 1
    ;;
esac

pushd flink-connector-bigquery

# Upload test coverage report to Codecov
bash <(curl -s https://codecov.io/bash) -K -F "${STEP}"

popd

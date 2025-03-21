#!/usr/bin/env bash

set -e -u -o pipefail

test_name="$(basename "${0}" .sh)"
project='metrics'
repository='https://github.com/dropwizard/metrics.git'
revision='v5.0.0-rc22'
additional_build_flags=''
additional_source_directories=''
# XXX: Minimize the diff by including
# `-XepOpt:Slf4jLoggerDeclaration:CanonicalStaticLoggerName=LOGGER` once such
# flags are supported in patch mode. See
# https://github.com/google/error-prone/pull/4699.
shared_error_prone_flags='-XepExcludedPaths:.*/target/generated-sources/.*'
patch_error_prone_flags=''
validation_error_prone_flags=''
validation_build_flags=''

if [ "${#}" -gt 2 ] || ([ "${#}" = 2 ] && [ "${1:---sync}" != '--sync' ]); then
  >&2 echo "Usage: ${0} [--sync] [<report_directory>]"
  exit 1
fi

"$(dirname "${0}")/run-integration-test.sh" \
  "${test_name}" \
  "${project}" \
  "${repository}" \
  "${revision}" \
  "${additional_build_flags}" \
  "${additional_source_directories}" \
  "${shared_error_prone_flags}" \
  "${patch_error_prone_flags}" \
  "${validation_error_prone_flags}" \
  "${validation_build_flags}" \
  $@

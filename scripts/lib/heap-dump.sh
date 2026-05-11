#!/usr/bin/env bash
# Heap-dump helpers. Captures a live heap dump from the running app container
# and copies it to the host output directory.

# Args: <output_path_on_host>
# Example: heap_dump::capture /path/to/heap-dump-start.hprof
heap_dump::capture() {
  local out_path="${1}"
  local container=drools-microservice-app-1

  echo "  Capturing heap dump → ${out_path}"

  # Find the JVM PID inside the container.
  local pid
  pid=$(docker exec "${container}" jps -q 2>/dev/null | head -1)
  if [[ -z "${pid}" ]]; then
    # Fallback: scan /proc for the java process
    pid=$(docker exec "${container}" sh -c \
      "for d in /proc/[0-9]*; do
         [ -r \"\$d/comm\" ] && [ \"\$(cat \$d/comm 2>/dev/null)\" = java ] && \
           basename \$d && exit 0;
       done")
  fi
  if [[ -z "${pid}" ]]; then
    echo "  [FAIL] could not find java pid inside ${container}"
    return 1
  fi

  # jmap with live=true triggers a full GC first, so the dump captures retained set only.
  local container_path=/tmp/heap-$(date +%s).hprof
  if ! docker exec "${container}" jmap -dump:live,format=b,file="${container_path}" "${pid}" \
       > /dev/null 2>&1; then
    echo "  [FAIL] jmap failed inside container"
    return 1
  fi

  if ! docker cp "${container}:${container_path}" "${out_path}" > /dev/null 2>&1; then
    echo "  [FAIL] docker cp failed"
    return 1
  fi

  docker exec "${container}" rm -f "${container_path}" > /dev/null 2>&1 || true

  local size
  size=$(du -h "${out_path}" 2>/dev/null | cut -f1)
  echo "  [ok]   heap dump captured (${size})"
  return 0
}

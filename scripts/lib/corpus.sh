#!/usr/bin/env bash
# Corpus generator + uploader.
# Templates from the curated sample-rules patterns. Each call to corpus::generate emits
# <count> unique synthetic DRL files distributed equally across the registered templates,
# plus a rule-ids.csv mapping rule_id → expected JSON input (for JMeter).
#
# Phase 0 ships with 5 representative templates covering the existing 10 cookbook patterns.
# Phase 1 (after the 7 new sample-rules land) will extend this to all 17 patterns.
# Generator interface stays stable; only the template count grows.

# All templates produce a $data : Map fact LHS and write a unique fired_<key>: true to the map
# in the RHS, so JMeter can verify the rule actually matched on its specific input.

# ---------------------------------------------------------------------------
# Template emitters. Each emit_<style> takes:
#   $1 = numeric N (1..count) for unique identifier
#   $2 = output directory (writes <ruleId>.drl)
#   $3 = name of an associative array variable to populate with rule_id → json_input
# ---------------------------------------------------------------------------

# Style 1: simple-discount (numeric threshold). Matches the LHS shape of pricing.discount.simple.
corpus::_emit_simple() {
  local n="${1}" out_dir="${2}" csv="${3}"
  local rid="synth.simple.$(printf '%04d' "${n}")"
  local key="k_simple_${n}"
  local fpath="${out_dir}/synthetic/simple/${rid}.drl"
  mkdir -p "$(dirname "${fpath}")"
  cat > "${fpath}" <<EOF
package com.company.rules.synthetic.simple

import java.util.Map

rule "synthetic-simple-${n}"
when
    \$data : Map(this["${key}"] != null,
                ((Number)this["${key}"]).doubleValue() > 50.0)
then
    double v = ((Number) \$data.get("${key}")).doubleValue();
    \$data.put("fired_${key}", true);
    \$data.put("result_${key}", v * 0.9);
end
EOF
  printf '%s,"{""%s"": 100.0}"\n' "${rid}" "${key}" >> "${csv}"
}

# Style 2: string-equality (categorical). Matches vip / shipping-type pattern.
corpus::_emit_string() {
  local n="${1}" out_dir="${2}" csv="${3}"
  local rid="synth.string.$(printf '%04d' "${n}")"
  local key="k_str_${n}"
  local val="S${n}"
  local fpath="${out_dir}/synthetic/string/${rid}.drl"
  mkdir -p "$(dirname "${fpath}")"
  cat > "${fpath}" <<EOF
package com.company.rules.synthetic.string

import java.util.Map

rule "synthetic-string-${n}"
when
    \$data : Map(this["${key}"] == "${val}")
then
    \$data.put("fired_${key}", true);
    \$data.put("matched_${key}", "${val}");
end
EOF
  printf '%s,"{""%s"": ""%s""}"\n' "${rid}" "${key}" "${val}" >> "${csv}"
}

# Style 3: integer threshold. Matches bulk-discount pattern.
corpus::_emit_int() {
  local n="${1}" out_dir="${2}" csv="${3}"
  local rid="synth.int.$(printf '%04d' "${n}")"
  local key="k_int_${n}"
  local fpath="${out_dir}/synthetic/int/${rid}.drl"
  mkdir -p "$(dirname "${fpath}")"
  cat > "${fpath}" <<EOF
package com.company.rules.synthetic.intt

import java.util.Map

rule "synthetic-int-${n}"
when
    \$data : Map(this["${key}"] != null,
                ((Number)this["${key}"]).intValue() >= 10)
then
    int q = ((Number) \$data.get("${key}")).intValue();
    \$data.put("fired_${key}", true);
    \$data.put("qty_${key}", q);
end
EOF
  printf '%s,"{""%s"": 15}"\n' "${rid}" "${key}" >> "${csv}"
}

# Style 4: boolean flag. Matches first-time / holiday-season pattern.
corpus::_emit_bool() {
  local n="${1}" out_dir="${2}" csv="${3}"
  local rid="synth.bool.$(printf '%04d' "${n}")"
  local key="k_bool_${n}"
  local fpath="${out_dir}/synthetic/bool/${rid}.drl"
  mkdir -p "$(dirname "${fpath}")"
  cat > "${fpath}" <<EOF
package com.company.rules.synthetic.bool

import java.util.Map

rule "synthetic-bool-${n}"
when
    \$data : Map(this["${key}"] == true)
then
    \$data.put("fired_${key}", true);
end
EOF
  printf '%s,"{""%s"": true}"\n' "${rid}" "${key}" >> "${csv}"
}

# Style 5: multi-condition. Two non-null guards plus a numeric comparison.
# Matches the validation.customer.credit + multi-condition shipping shapes.
corpus::_emit_multi() {
  local n="${1}" out_dir="${2}" csv="${3}"
  local rid="synth.multi.$(printf '%04d' "${n}")"
  local k1="k_m1_${n}"
  local k2="k_m2_${n}"
  local fpath="${out_dir}/synthetic/multi/${rid}.drl"
  mkdir -p "$(dirname "${fpath}")"
  cat > "${fpath}" <<EOF
package com.company.rules.synthetic.multi

import java.util.Map

rule "synthetic-multi-${n}"
when
    \$data : Map(this["${k1}"] != null,
                this["${k2}"] != null,
                ((Number)this["${k1}"]).doubleValue() > 0.0)
then
    double a = ((Number) \$data.get("${k1}")).doubleValue();
    double b = ((Number) \$data.get("${k2}")).doubleValue();
    \$data.put("fired_${k1}", true);
    \$data.put("sum_${n}", a + b);
end
EOF
  printf '%s,"{""%s"": 50.0, ""%s"": 75.0}"\n' "${rid}" "${k1}" "${k2}" >> "${csv}"
}

# Registry: list of emitter function names. Phase 1 will append 12 more entries here.
_corpus_templates=(
  "corpus::_emit_simple"
  "corpus::_emit_string"
  "corpus::_emit_int"
  "corpus::_emit_bool"
  "corpus::_emit_multi"
)

# ---------------------------------------------------------------------------
# Public interface
# ---------------------------------------------------------------------------

# Generates <count> synthetic DRL files into <output_dir>/synthetic/ + emits
# <output_dir>/rule-ids.csv (header: rule_id,input_json).
# Distribution: equal split across all registered templates, round-robin remainder.
corpus::generate() {
  local count="${1}"
  local out_dir="${2}"
  local csv="${out_dir}/rule-ids.csv"

  echo "==> Generating ${count} synthetic rules across ${#_corpus_templates[@]} templates"
  mkdir -p "${out_dir}"
  echo "rule_id,input_json" > "${csv}"

  local templates=("${_corpus_templates[@]}")
  local n_templates=${#templates[@]}
  local i
  for (( i=1; i<=count; i++ )); do
    local idx=$(( (i - 1) % n_templates ))
    "${templates[idx]}" "${i}" "${out_dir}" "${csv}"
    if (( i % 100 == 0 )); then
      echo "  generated ${i}/${count}"
    fi
  done
  echo "  [ok]   ${count} rules generated → ${out_dir}/synthetic/"
  echo "  [ok]   rule-ids.csv → ${csv}"
}

# Clears every object in the LocalStack rules bucket. Use before re-populating to
# guarantee an exact rule count.
corpus::clear_s3() {
  echo "==> Clearing s3://local-rules/"
  docker exec drools-microservice-localstack-1 \
    awslocal s3 rm s3://local-rules/ --recursive > /dev/null 2>&1 || true
}

# Uploads the generated corpus to LocalStack via `awslocal s3 sync`.
# Args: <output_dir>
corpus::upload() {
  local out_dir="${1}"
  echo "==> Uploading corpus to s3://local-rules/"

  # Copy the corpus into the localstack container, then sync from there. This avoids
  # streaming each file individually over docker exec and is much faster for 1k+ rules.
  docker cp "${out_dir}/synthetic/" drools-microservice-localstack-1:/tmp/synthetic > /dev/null
  docker exec drools-microservice-localstack-1 \
    awslocal s3 sync /tmp/synthetic/ s3://local-rules/ \
      --no-progress > /dev/null 2>&1
  docker exec drools-microservice-localstack-1 rm -rf /tmp/synthetic > /dev/null 2>&1 || true

  local count
  count=$(docker exec drools-microservice-localstack-1 \
    awslocal s3 ls s3://local-rules/ --recursive 2>/dev/null | wc -l | tr -d ' ')
  echo "  [ok]   s3://local-rules/ now contains ${count} object(s)"
}

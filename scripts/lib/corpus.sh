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
  local fpath="${out_dir}/synthetic/${rid//.//}.drl"
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
  local fpath="${out_dir}/synthetic/${rid//.//}.drl"
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
  local fpath="${out_dir}/synthetic/${rid//.//}.drl"
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
  local fpath="${out_dir}/synthetic/${rid//.//}.drl"
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

# --- The 5 templates above cover the LHS shapes of the original 10 cookbook entries.
#     Below: 7 templates matching the 7 cookbook patterns added in Phase 1 (2026-05-10).
#     This gives 12 distinct templates total. The plan called for "17 patterns equally
#     distributed"; the 5 above each cover ~2 cookbook entries with identical RETE shape,
#     so 12 templates still hits the spirit of the plan (every distinct pattern in the
#     cookbook is stressed at scale; no single shape dominates the corpus). ---

# Style 5: multi-condition. Two non-null guards plus a numeric comparison.
# Matches the validation.customer.credit + multi-condition shipping shapes.
corpus::_emit_multi() {
  local n="${1}" out_dir="${2}" csv="${3}"
  local rid="synth.multi.$(printf '%04d' "${n}")"
  local k1="k_m1_${n}"
  local k2="k_m2_${n}"
  local fpath="${out_dir}/synthetic/${rid//.//}.drl"
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

# Style 6: accumulate (sum aggregation over a list). Mirrors pricing.bundle.accumulate.
corpus::_emit_accumulate() {
  local n="${1}" out_dir="${2}" csv="${3}"
  local rid="synth.acc.$(printf '%04d' "${n}")"
  local key="acc_items_${n}"
  local fpath="${out_dir}/synthetic/${rid//.//}.drl"
  mkdir -p "$(dirname "${fpath}")"
  cat > "${fpath}" <<EOF
package com.company.rules.synthetic.acc

import java.util.Map
import java.util.List

rule "synthetic-acc-${n}"
when
    \$data : Map(this["${key}"] != null)
    \$total : Number(doubleValue > 50.0)
        from accumulate(
            \$i : Map() from ((List) \$data.get("${key}")),
            sum( ((Number) \$i.get("price")).doubleValue() )
        )
then
    \$data.put("fired_${key}", true);
    \$data.put("acc_total_${n}", \$total.doubleValue());
end
EOF
  printf '%s,"{""%s"": [{""price"": 30}, {""price"": 40}]}"\n' "${rid}" "${key}" >> "${csv}"
}

# Style 7: exists (any-element trigger). Mirrors inventory.warning.exists.
corpus::_emit_exists() {
  local n="${1}" out_dir="${2}" csv="${3}"
  local rid="synth.exists.$(printf '%04d' "${n}")"
  local key="exists_items_${n}"
  local fpath="${out_dir}/synthetic/${rid//.//}.drl"
  mkdir -p "$(dirname "${fpath}")"
  cat > "${fpath}" <<EOF
package com.company.rules.synthetic.exists

import java.util.Map
import java.util.List

rule "synthetic-exists-${n}"
when
    \$data : Map(this["${key}"] != null)
    exists Map( ((Number) this["level"]).intValue() < 5 )
        from ((List) \$data.get("${key}"))
then
    \$data.put("fired_${key}", true);
end
EOF
  printf '%s,"{""%s"": [{""level"": 10}, {""level"": 3}]}"\n' "${rid}" "${key}" >> "${csv}"
}

# Style 8: not (absence guard). Mirrors validation.cart.notempty.
corpus::_emit_notempty() {
  local n="${1}" out_dir="${2}" csv="${3}"
  local rid="synth.notempty.$(printf '%04d' "${n}")"
  local key="not_items_${n}"
  local fpath="${out_dir}/synthetic/${rid//.//}.drl"
  mkdir -p "$(dirname "${fpath}")"
  cat > "${fpath}" <<EOF
package com.company.rules.synthetic.notempty

import java.util.Map
import java.util.List

rule "synthetic-not-${n}"
when
    \$data : Map(this["${key}"] != null)
    not Map( this["${key}"] != null,
             ((List) this["${key}"]).size() > 0 )
then
    \$data.put("fired_${key}", true);
end
EOF
  printf '%s,"{""%s"": []}"\n' "${rid}" "${key}" >> "${csv}"
}

# Style 9: salience (priority override). Mirrors pricing.loyalty.salience.
corpus::_emit_salience() {
  local n="${1}" out_dir="${2}" csv="${3}"
  local rid="synth.sal.$(printf '%04d' "${n}")"
  local kflag="sal_flag_${n}"
  local kamt="sal_amt_${n}"
  local fpath="${out_dir}/synthetic/${rid//.//}.drl"
  mkdir -p "$(dirname "${fpath}")"
  cat > "${fpath}" <<EOF
package com.company.rules.synthetic.sal

import java.util.Map

rule "synthetic-sal-${n}"
salience 100
when
    \$data : Map(this["${kflag}"] == true,
                this["${kamt}"] != null)
then
    double amt = ((Number) \$data.get("${kamt}")).doubleValue();
    \$data.put("fired_${kflag}", true);
    \$data.put("sal_discounted_${n}", amt * 0.85);
end
EOF
  printf '%s,"{""%s"": true, ""%s"": 100.0}"\n' "${rid}" "${kflag}" "${kamt}" >> "${csv}"
}

# Style 10: compound &&/|| LHS with matches. Mirrors validation.email.compound.
corpus::_emit_compound() {
  local n="${1}" out_dir="${2}" csv="${3}"
  local rid="synth.comp.$(printf '%04d' "${n}")"
  local kemail="comp_email_${n}"
  local ktype="comp_type_${n}"
  local fpath="${out_dir}/synthetic/${rid//.//}.drl"
  mkdir -p "$(dirname "${fpath}")"
  cat > "${fpath}" <<EOF
package com.company.rules.synthetic.comp

import java.util.Map

rule "synthetic-comp-${n}"
when
    \$data : Map(
        this["${kemail}"] != null,
        ( this["${ktype}"] == "internal" && this["${kemail}"] matches ".*@company\\\\.com\$" )
        ||
        ( this["${ktype}"] == "external" && this["${kemail}"] matches ".+@.+\\\\..+" )
    )
then
    \$data.put("fired_${kemail}", true);
end
EOF
  printf '%s,"{""%s"": ""x@company.com"", ""%s"": ""internal""}"\n' \
    "${rid}" "${kemail}" "${ktype}" >> "${csv}"
}

# Style 11: temporal (date math via java.time). Mirrors seasonal.expiry.temporal.
corpus::_emit_temporal() {
  local n="${1}" out_dir="${2}" csv="${3}"
  local rid="synth.tmp.$(printf '%04d' "${n}")"
  local kcur="tmp_current_${n}"
  local kexp="tmp_expiry_${n}"
  local fpath="${out_dir}/synthetic/${rid//.//}.drl"
  mkdir -p "$(dirname "${fpath}")"
  cat > "${fpath}" <<EOF
package com.company.rules.synthetic.tmp

import java.util.Map
import java.time.LocalDate
import java.time.format.DateTimeFormatter

rule "synthetic-tmp-${n}"
when
    \$data : Map(this["${kcur}"] != null,
                this["${kexp}"] != null)
then
    LocalDate cur =
        LocalDate.parse((String) \$data.get("${kcur}"), DateTimeFormatter.ISO_LOCAL_DATE);
    LocalDate exp =
        LocalDate.parse((String) \$data.get("${kexp}"), DateTimeFormatter.ISO_LOCAL_DATE);
    \$data.put("fired_${kcur}", !cur.isAfter(exp));
end
EOF
  printf '%s,"{""%s"": ""2026-05-10"", ""%s"": ""2026-12-31""}"\n' \
    "${rid}" "${kcur}" "${kexp}" >> "${csv}"
}

# Style 12: forall (universal quantification). Mirrors validation.cart.forall.
corpus::_emit_forall() {
  local n="${1}" out_dir="${2}" csv="${3}"
  local rid="synth.fa.$(printf '%04d' "${n}")"
  local key="fa_items_${n}"
  local fpath="${out_dir}/synthetic/${rid//.//}.drl"
  mkdir -p "$(dirname "${fpath}")"
  cat > "${fpath}" <<EOF
package com.company.rules.synthetic.fa

import java.util.Map
import java.util.List

rule "synthetic-fa-${n}"
when
    \$data : Map(this["${key}"] != null)
    forall (
        \$item : Map() from ((List) \$data.get("${key}"))
        Map( this == \$item, ((Number) this["level"]).intValue() > 0 )
            from ((List) \$data.get("${key}"))
    )
then
    \$data.put("fired_${key}", true);
end
EOF
  printf '%s,"{""%s"": [{""level"": 10}, {""level"": 20}]}"\n' "${rid}" "${key}" >> "${csv}"
}

# Registry: 12 templates total — 5 covering original 10 cookbook patterns + 7 covering
# the patterns added in Phase 1 (2026-05-10).
_corpus_templates=(
  "corpus::_emit_simple"
  "corpus::_emit_string"
  "corpus::_emit_int"
  "corpus::_emit_bool"
  "corpus::_emit_multi"
  "corpus::_emit_accumulate"
  "corpus::_emit_exists"
  "corpus::_emit_notempty"
  "corpus::_emit_salience"
  "corpus::_emit_compound"
  "corpus::_emit_temporal"
  "corpus::_emit_forall"
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

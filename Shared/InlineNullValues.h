/*
 * Copyright 2019 OmniSci, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef INLINENULLVALUES_H
#define INLINENULLVALUES_H

#ifndef __CUDACC__
#include <glog/logging.h>
#else
#ifndef CHECK
#include "../Shared/always_assert.h"
#endif
#endif  // __CUDACC__

template <typename T>
T inline_fp_null_value();

template <>
constexpr inline float inline_fp_null_value<float>() {
  return NULL_FLOAT;
}

template <>
constexpr inline double inline_fp_null_value<double>() {
  return NULL_DOUBLE;
}

template <typename SQL_TYPE_INFO>
inline int64_t inline_int_null_val(const SQL_TYPE_INFO& ti) {
  auto type = ti.get_type();
  if (ti.is_string()) {
    CHECK_EQ(kENCODING_DICT, ti.get_compression());
    CHECK_EQ(4, ti.get_logical_size());
    type = kINT;
  } else {
    CHECK_EQ(kENCODING_NONE, ti.get_compression());
  }
  switch (type) {
    case kBOOLEAN:
      return inline_int_null_value<int8_t>();
    case kTINYINT:
      return inline_int_null_value<int8_t>();
    case kSMALLINT:
      return inline_int_null_value<int16_t>();
    case kINT:
      return inline_int_null_value<int32_t>();
    case kBIGINT:
      return inline_int_null_value<int64_t>();
    case kTIMESTAMP:
    case kTIME:
    case kDATE:
    case kINTERVAL_DAY_TIME:
    case kINTERVAL_YEAR_MONTH:
      return inline_int_null_value<int64_t>();
    case kDECIMAL:
    case kNUMERIC:
      return inline_int_null_value<int64_t>();
    default:
      abort();
  }
}

template <typename SQL_TYPE_INFO>
inline int64_t inline_fixed_encoding_null_val(const SQL_TYPE_INFO& ti) {
  if (ti.get_compression() == kENCODING_NONE) {
    return inline_int_null_val(ti);
  }
  if (ti.get_compression() == kENCODING_DATE_IN_DAYS) {
    switch (ti.get_comp_param()) {
      case 0:
      case 32:
        return inline_int_null_value<int32_t>();
      case 16:
        return inline_int_null_value<int16_t>();
      default:
#ifndef __CUDACC__
        CHECK(false) << "Unknown encoding width for date in days: "
                     << ti.get_comp_param();
#else
        CHECK(false);
#endif
    }
  }
  if (ti.get_compression() == kENCODING_DICT) {
    CHECK(ti.is_string());
    switch (ti.get_size()) {
      case 1:
        return inline_int_null_value<uint8_t>();
      case 2:
        return inline_int_null_value<uint16_t>();
      case 4:
        return inline_int_null_value<int32_t>();
      default:
#ifndef __CUDACC__
        CHECK(false) << "Unknown size for dictionary encoded type: " << ti.get_size();
#else
        CHECK(false);
#endif
    }
  }
  CHECK_EQ(kENCODING_FIXED, ti.get_compression());
  CHECK(ti.is_integer() || ti.is_time() || ti.is_decimal());
  CHECK_EQ(0, ti.get_comp_param() % 8);
  return -(1L << (ti.get_comp_param() - 1));
}

template <typename SQL_TYPE_INFO>
inline double inline_fp_null_val(const SQL_TYPE_INFO& ti) {
  CHECK(ti.is_fp());
  const auto type = ti.get_type();
  switch (type) {
    case kFLOAT:
      return inline_fp_null_value<float>();
    case kDOUBLE:
      return inline_fp_null_value<double>();
    default:
      abort();
  }
}

#endif
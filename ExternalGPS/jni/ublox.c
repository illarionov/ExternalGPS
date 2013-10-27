/* vim: set tabstop=2 shiftwidth=2 expandtab: */

#include <assert.h>

#include <jni.h>
#include <android/log.h>

#include "usbconverter.h"

#define TAG "nativeUblox"
#ifdef ENABLE_LOG
#define LOGV(x...) __android_log_print(ANDROID_LOG_INFO,TAG,x)
#else
#define LOGV(...)  do {} while (0)
#endif

inline int looks_like_ublox(const uint8_t *msg, size_t max_len) {
  unsigned payload_length;
  uint8_t ck_a, ck_b;
  unsigned i;

  assert(max_len > 0);

  if (msg[0] != 0xb5)
    return LOOKS_NOT_LIKE_GPS_MSG;

  if (max_len < 8)
    return LOOKS_LIKE_TRUNCATED_MSG;

  if (msg[1] != 0x62)
    return LOOKS_NOT_LIKE_GPS_MSG;

  payload_length = (msg[4] & 0xff) | (msg[5] << 8 & 0xff00);

  if (payload_length > UBLOX_MAX)
    return LOOKS_NOT_LIKE_GPS_MSG;

  if (max_len < payload_length + 8)
    return LOOKS_LIKE_TRUNCATED_MSG;

  ck_a = ck_b = 0;
  for (i=2; i < payload_length + 4 + 2; ++i) {
    ck_a = (ck_a + msg[i]) & 0xff;
    ck_b = (ck_b + ck_a) & 0xff;
  }

  if ((ck_a != msg[payload_length + 6]) ||
      (ck_b != msg[payload_length + 7])) {
    LOGV("u-blox checksum mismatch. 0x%hhx%hhx != 0x%hhx%hhx",
        msg[payload_length + 6], msg[payload_length + 7],
        ck_a, ck_b);
    return LOOKS_NOT_LIKE_GPS_MSG;
  }

  return payload_length + 8;
}



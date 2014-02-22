/* vim: set tabstop=2 shiftwidth=2 expandtab: */

#include <arpa/inet.h>
#include <assert.h>
#include <stdio.h>
#include <string.h>

#include <jni.h>
#include <android/log.h>

#include "usbconverter.h"

#define TAG "nativeSirf"
#ifdef ENABLE_LOG
#define LOGV(x...) __android_log_print(ANDROID_LOG_INFO,TAG,x)
#else
#define LOGV(...)  do {} while (0)
#endif

static void parse_tracker_data(const uint8_t *msg, size_t msg_size,
    struct gps_msg_status_t *status);
static unsigned sirf_csum(const uint8_t *payload, unsigned payload_length);

static inline uint16_t get2u(const uint8_t *buf);
static inline uint32_t get4u(const uint8_t *buf);
static inline int32_t get4s(const uint8_t *buf);

inline int looks_like_sirf(const uint8_t *msg, size_t max_len)
{
  unsigned payload_length;
  unsigned computed_csum, msg_csum;

  assert(max_len > 0);

  if (msg[0] != 0xa0)
    return LOOKS_NOT_LIKE_GPS_MSG;

  if (max_len < 8)
    return LOOKS_LIKE_TRUNCATED_MSG;

  if (msg[1] != 0xa2)
    return LOOKS_NOT_LIKE_GPS_MSG;

  payload_length = get2u(&msg[2]);

  if (payload_length > SIRF_MAX)
    return LOOKS_NOT_LIKE_GPS_MSG;

  if (max_len < payload_length + 8)
    return LOOKS_LIKE_TRUNCATED_MSG;

  if (msg[4 + payload_length + 2] != 0xb0
      || (msg[4 + payload_length + 3] != 0xb3))
    return LOOKS_NOT_LIKE_GPS_MSG;

  msg_csum = get2u(&msg[4+payload_length]);
  computed_csum = sirf_csum(&msg[4], payload_length);

  if (msg_csum != computed_csum) {
    LOGV("SiRF checksum mismatch. 0x%04x != 0x%04x",
        msg_csum, computed_csum);
    return LOOKS_NOT_LIKE_GPS_MSG;
  }

  return payload_length + 8;
}

void reset_sirf_parser(struct sirf_parser_t *ctx)
{
}

bool put_sirf_msg(struct sirf_parser_t *ctx, const uint8_t *msg,
    size_t msg_size, struct gps_msg_status_t *status)
{
  unsigned mid;

  assert(msg_size > 1 &&  msg_size <= SIRF_MAX);
  assert((size_t)looks_like_sirf(msg, msg_size) == msg_size);

  ctx->stats->rcvd.sirf.total += 1;
  ctx->stats->rcvd.sirf.last_msg_ts = ctx->stats->rcvd.last_byte_ts;

  mid = msg[4];
  switch (mid) {
    case 41:
      parse_tracker_data(msg, msg_size, status);
      ctx->stats->rcvd.sirf.mid41 += 1;
      break;
    default:
      status->is_valid = true;
      status->location_changed = false;
      status->err[0] = '\0';
      break;
  }

  return status->is_valid;
}

static void parse_tracker_data(const uint8_t *msg, size_t msg_size,
    struct gps_msg_status_t *status)
{
  unsigned payload_size;
  unsigned nav_type;
  unsigned mss;
  time_t cur_time;
  struct tm loc_tm;
  struct location_t l;

  if (msg_size) {}

  payload_size = get2u(&msg[2]);
  if (payload_size != 91) {
    snprintf(status->err, sizeof(status->err), "Wrong SiRF MID41 payload size %u", payload_size);
    status->is_valid = false;
    return;
  }

  nav_type = get2u(&msg[7]) & 0x07;
  l.is_valid = nav_type != 0;

  /* Time */
  cur_time = time(NULL);
  gmtime_r(&cur_time, &loc_tm);
  loc_tm.tm_year = get2u(&msg[15]) - 1900;
  loc_tm.tm_mon = msg[17] - 1;
  loc_tm.tm_mday = msg[18];
  loc_tm.tm_hour = msg[19];
  loc_tm.tm_min = msg[20];
  mss = get2u(&msg[21]);
  loc_tm.tm_sec = mss / 1000;

  l.time = 1000ll * (long long)timegm64(&loc_tm) + (mss % 1000);

  /* latitude, longitude */
  l.latitude = 1.0e-7 * get4s(&msg[27]);
  l.longitude = 1.0e-7 * get4s(&msg[31]);

  /* Altitude MSL */
  l.has_altitude = true;
  l.altitude = 1.0e-2 * get4s(&msg[39]);

  /* Speed */
  l.has_speed = true;
  l.speed = 1.0e-2 * get2u(&msg[44]);

  /* Bearing */
  l.has_bearing = true;
  l.bearing = 1.0e-2 * get2u(&msg[46]);

  /* Accuracy (Estimated horizontal position error) */
  l.has_accuracy = true;
  l.accuracy = 1.0e-2 * get4u(&msg[54]);

  /* Number of satellites used to derive the fix */
  l.satellites = msg[92];

  status->location = l;
  status->is_valid = true;
  status->location_changed = true;
  status->err[0] = '\0';
}

static unsigned sirf_csum(const uint8_t *payload, unsigned payload_length)
{
  unsigned i;
  unsigned csum;

  csum = 0;
  for (i=0; i<payload_length; ++i) {
    csum = 0x7fff & (csum + (payload[i] & 0xff));
  }

  return csum;
}

static inline uint16_t get2u(const uint8_t *buf)
{
  return (buf[0] << 8) | buf[1];
}

static inline uint32_t get4u(const uint8_t *buf)
{
  return (buf[0] << 24) | (buf[1] << 16) | (buf[2] << 8) | buf[3];
}

static inline int32_t get4s(const uint8_t *buf)
{
  return (int32_t)get4u(buf);
}

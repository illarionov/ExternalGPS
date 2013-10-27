/* vim: set tabstop=2 shiftwidth=2 expandtab: */

#include <sys/ioctl.h>
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <unistd.h>

#include <jni.h>
#include <android/log.h>

#include "usbconverter.h"
#include "datalogger.h"
#include "usbreader.h"

#define TAG "NativeUsbConverter"
#ifdef ENABLE_LOG
#define LOGV(x...) __android_log_print(ANDROID_LOG_VERBOSE,TAG,x)
#else
#define LOGV(...)  do {} while (0)
#endif

#define EXCEPTION_ILLEGAL_ARGUMENT "java/lang/IllegalArgumentException"
#define EXCEPTION_ILLEGAL_STATE "java/lang/IllegalStateException"
#define EXCEPTION_NULL_POINTER  "java/lang/NullPointerException"

static const struct timespec READ_TIMEOUT = {
  1, 500l*1e6
};

static jfieldID m_object_field;
static jmethodID method_report_location;
static jmethodID method_on_gps_message_received;

struct usb_read_stream_t {
  pthread_t read_thread;
  struct usb_reader_thread_ctx_t read_thread_ctx;

  struct timespec last_event_ts;

  int rxbuf_pos;
  jobject rx_buf_direct;
  uint8_t rx_buf[USB_READER_BUF_SIZE];
};

struct native_ctx_t {
  bool msg_rcvd_cb_active;

  struct nmea_parser_t nmea;
  struct sirf_parser_t sirf;
  struct stats_t       stats;
  struct usb_read_stream_t stream;
  struct datalogger_t datalogger;
};

static void read_loop(JNIEnv *env, jobject this, struct native_ctx_t *stream);
static void handle_rcvd(JNIEnv *env, jobject this,
    struct native_ctx_t *reader, unsigned rcvd_last);
static void handle_timedout(JNIEnv *env, jobject this, struct native_ctx_t *reader);
static int find_msg(uint8_t *buf, int start_pos, int buf_size, struct gps_msg_metadata_t *res);
static bool handle_msg(JNIEnv *env, jobject this, struct native_ctx_t *reader, uint8_t *msg, struct gps_msg_metadata_t *metadata);
static void report_msg_rcvd(JNIEnv *env, jobject this, struct native_ctx_t *reader, uint8_t *msg, struct gps_msg_metadata_t *metadata);
static void report_location(JNIEnv *env, jobject this, struct location_t *location);

static inline struct native_ctx_t *get_ctx(JNIEnv* env, jobject thiz);
static inline void throw_exception(JNIEnv *env, const char *clazzName, const char *message);


static void native_create(JNIEnv* env, jobject thiz)
{
  struct native_ctx_t *nctx;

  LOGV("native_create()");

  nctx = (struct native_ctx_t *)calloc(1, sizeof(struct native_ctx_t));

  if (nctx == NULL) {
    LOGV("calloc() error");
    return;
  }

  stats_init(&nctx->stats);
  nctx->nmea.stats = &nctx->stats;
  nctx->sirf.stats = &nctx->stats;

  nctx->msg_rcvd_cb_active = true;
  datalogger_init(&nctx->datalogger);

  (*env)->SetLongField(env, thiz, m_object_field, (long)nctx);
}

static void native_destroy(JNIEnv *env, jobject thiz)
{
  struct native_ctx_t *nctx;

  LOGV("native_destroy()");

  nctx = get_ctx(env, thiz);
  if (nctx == NULL) {
    LOGV("nctx is null");
    return;
  }

  stats_destroy(&nctx->stats);
  datalogger_init(&nctx->datalogger);

  free(nctx);
  (*env)->SetLongField(env, thiz, m_object_field, 0L);
}

static void native_read_loop(JNIEnv *env, jobject this,
    jobject j_input_stream, jobject j_output_stream)
{
  static jmethodID method_get_istream_fd;
  static jmethodID method_get_ostream_fd;
  static jmethodID method_get_istream_max_pkt_size;
  static jmethodID method_get_istream_ep_addr;
  jobject direct_buf;
  JavaVM *jvm;
  struct native_ctx_t *reader;
  int fd, max_pkt_size, endpoint;

  reader = get_ctx(env, this);
  if (reader == NULL)
    return throw_exception(env, EXCEPTION_NULL_POINTER, "mObject is null");

  if (j_input_stream == NULL)
    return throw_exception(env, EXCEPTION_NULL_POINTER, "inputStream is null");

  if (j_output_stream == NULL)
    return throw_exception(env, EXCEPTION_NULL_POINTER, "outputStream is null");

  if (method_get_istream_fd == NULL) {
    jclass class_usb_input_stream;

    class_usb_input_stream = (*env)->GetObjectClass(env, j_input_stream);

    method_get_istream_fd = (*env)->GetMethodID(env,
        class_usb_input_stream, "getFileDescriptor", "()I");
    if (method_get_istream_fd == NULL)
      return;

    method_get_istream_max_pkt_size = (*env)->GetMethodID(env,
        class_usb_input_stream, "getMaxPacketSize", "()I");
    if (method_get_istream_max_pkt_size == NULL)
      return;

    method_get_istream_ep_addr = (*env)->GetMethodID(env,
        class_usb_input_stream, "getEndpointAddress", "()I");
    if (method_get_istream_ep_addr == NULL)
      return;

    method_get_ostream_fd = (*env)->GetMethodID(env,
        (*env)->GetObjectClass(env, j_output_stream),
        "getFileDescriptor", "()I"
        );
    if (method_get_ostream_fd == NULL)
      return;
  }

  direct_buf = (*env)->NewDirectByteBuffer(env, reader->stream.rx_buf,
      sizeof(reader->stream.rx_buf));
  if (direct_buf == NULL)
    return;
 reader->stream.rx_buf_direct = (*env)->NewGlobalRef(env, direct_buf);

 if ( (*env)->GetJavaVM(env, &jvm) < 0) {
   LOGV("GetJavaVM() failure");
   return;
 }
 if (jvm == NULL) {
   LOGV("GetJavaVM(): JavaVM is NULL");
   return;
 }

 fd = (*env)->CallIntMethod(env, j_input_stream, method_get_istream_fd);
 if (fd < 0)
   return;

 max_pkt_size = (*env)->CallIntMethod(env, j_input_stream, method_get_istream_max_pkt_size);
 if (max_pkt_size <= 0)
   return;

 endpoint = (*env)->CallIntMethod(env, j_input_stream, method_get_istream_ep_addr);

 usb_reader_init(&reader->stream.read_thread_ctx,
     jvm, fd, endpoint, max_pkt_size);

 read_loop(env, this, reader);

 (*env)->DeleteGlobalRef(env, reader->stream.rx_buf_direct);
}

static void native_get_stats(JNIEnv *env, jobject this, jobject dst)
{
  struct native_ctx_t *reader;

  if (dst == NULL)
    return throw_exception(env, EXCEPTION_NULL_POINTER, "dst is null");

  reader = get_ctx(env, this);
  if (reader == NULL)
    return throw_exception(env, EXCEPTION_NULL_POINTER, "mObject is null");

  stats_export_to_java(env, &reader->stats, dst);
}

static void native_msg_rcvd_cb(JNIEnv *env, jobject this, jboolean enable)
{
  struct native_ctx_t *reader;
  reader = get_ctx(env, this);
  if (reader == NULL)
    return throw_exception(env, EXCEPTION_NULL_POINTER, "mObject is null");

  reader->msg_rcvd_cb_active = enable;
}

static void native_datalogger_configure(JNIEnv *env, jobject this,
    jboolean enabled, jint format, jstring j_tracks_dir, jstring j_file_prefix)
{
  const char *tracks_dir, *file_prefix;
  struct native_ctx_t *ctx;
  bool valid;

  ctx = get_ctx(env, this);
  if (ctx == NULL)
    return throw_exception(env, EXCEPTION_NULL_POINTER, "mObject is null");

  tracks_dir = (*env)->GetStringUTFChars(env, j_tracks_dir, NULL);
  if (tracks_dir == NULL)
    return;

  file_prefix = (*env)->GetStringUTFChars(env, j_file_prefix, NULL);
  if (file_prefix == NULL) {
    (*env)->ReleaseStringUTFChars(env, j_tracks_dir, tracks_dir);
    return;
  }

  valid = datalogger_configure(&ctx->datalogger, enabled, format, tracks_dir, file_prefix);

  (*env)->ReleaseStringUTFChars(env, j_tracks_dir, tracks_dir);
  (*env)->ReleaseStringUTFChars(env, j_file_prefix, file_prefix);

  if (!valid)
    return throw_exception(env, EXCEPTION_ILLEGAL_ARGUMENT, "invalid configuration");
}

static void native_datalogger_start(JNIEnv *env, jobject this)
{
  struct native_ctx_t *ctx;
  ctx = get_ctx(env, this);
  if (ctx == NULL)
    return throw_exception(env, EXCEPTION_NULL_POINTER, "mObject is null");

  datalogger_start(&ctx->datalogger);
}

static void native_datalogger_stop(JNIEnv *env, jobject this)
{
  struct native_ctx_t *ctx;
  ctx = get_ctx(env, this);
  if (ctx == NULL)
    return throw_exception(env, EXCEPTION_NULL_POINTER, "mObject is null");

  datalogger_stop(&ctx->datalogger);
}

static void read_loop(JNIEnv *env, jobject this, struct native_ctx_t *reader)
{
  int rcvd;
  int last_errno;
  struct usb_read_stream_t *stream;

  reset_nmea_parser(&reader->nmea);
  reset_sirf_parser(&reader->sirf);

  stats_lock(&reader->stats);
  stats_start_unlocked(&reader->stats);
  stats_unlock(&reader->stats);

  stream = &reader->stream;
  stream->rxbuf_pos = 0;
  stream->last_event_ts.tv_sec = 0;
  stream->last_event_ts.tv_nsec = 0;

  if (pthread_create(&stream->read_thread, NULL, usb_reader_thread,
        &stream->read_thread_ctx) != 0) {
    // XXX
    return;
  }

  for (;;) {
    rcvd = usb_read(&stream->read_thread_ctx,
        &stream->rx_buf[stream->rxbuf_pos],
        sizeof(stream->rx_buf)-stream->rxbuf_pos,
        &READ_TIMEOUT);
    last_errno = errno;
    clock_gettime(CLOCK_MONOTONIC, &stream->last_event_ts);
    if (rcvd < 0) {
      if (last_errno == ETIMEDOUT) {
        LOGV("usb read timeout");
        handle_timedout(env, this, reader);
        continue;
      }else {
        break;
      }
    }else if (rcvd == 0) {
      LOGV("usb_read() rcvd 0");
      continue;
    }else {
      datalogger_log_raw_data(&reader->datalogger, &stream->rx_buf[stream->rxbuf_pos], rcvd);
      stream->rxbuf_pos += rcvd;
      handle_rcvd(env, this, reader, (unsigned)rcvd);
    }
  }

  pthread_join(stream->read_thread, NULL);

  datalogger_stop(&reader->datalogger);
}

static void handle_timedout(JNIEnv *env, jobject this, struct native_ctx_t *reader)
{
  struct gps_msg_status_t status;
  put_nmea_timedout(&reader->nmea, &status);
  if (status.location_changed)
    report_location(env, this, &status.location);
  datalogger_flush(&reader->datalogger);
}

static void handle_rcvd(JNIEnv *env, jobject this,
    struct native_ctx_t *reader, unsigned rcvd_last) {
  int pred_msg_pos, msg_pos;
  int pred_msg_len;
  struct gps_msg_metadata_t msg;
  struct usb_read_stream_t *stream;

  stream = &reader->stream;

  if (stream->rxbuf_pos == 0)
    return;

  stats_lock(&reader->stats);
  reader->stats.rcvd.bytes += rcvd_last;
  reader->stats.rcvd.last_byte_ts = reader->stream.last_event_ts;

  pred_msg_pos = 0;
  pred_msg_len = 0;
  msg_pos = find_msg(stream->rx_buf, 0, stream->rxbuf_pos, &msg);
  for (;;) {

    // No nessages found in buffer
    if (msg_pos < 0) {
      // LOGV("junk %u", stream->rxbuf_pos);
      reader->stats.rcvd.junk += stream->rxbuf_pos;
      stream->rxbuf_pos = 0;
      break;
    }
    // Junk between messages
    if (pred_msg_pos + pred_msg_len != msg_pos) {
      reader->stats.rcvd.junk += msg_pos - pred_msg_pos - pred_msg_len;
      //LOGV("inter msg junk %u", msg_pos - pred_msg_pos - pred_msg_len);
    }

    if (!msg.is_truncated) {
      handle_msg(env, this, reader, &stream->rx_buf[msg_pos], &msg);
      pred_msg_pos = msg_pos;
      pred_msg_len = msg.size;
    }else {
      // Truncated message
      if (msg_pos == 0) {
        if (stream->rxbuf_pos == sizeof(stream->rx_buf)) {
          pred_msg_pos = msg_pos+1;
          pred_msg_len = 0;
          reader->stats.rcvd.junk += 1;
          // FALLTHROUGH
        }else {
          break;
        }
      }else {
        memmove(&stream->rx_buf[0], &stream->rx_buf[msg_pos], msg.size);
        stream->rxbuf_pos = msg.size;
        break;
      }
    }

    if (pred_msg_pos+pred_msg_len == stream->rxbuf_pos) {
      stream->rxbuf_pos = 0;
      break;
    }

    assert(pred_msg_pos+pred_msg_len < stream->rxbuf_pos);
    msg_pos = find_msg(stream->rx_buf, pred_msg_pos+pred_msg_len, stream->rxbuf_pos, &msg);
  }
  stats_unlock(&reader->stats);
}

static int find_msg(uint8_t *buf, int start_pos, int buf_size, struct gps_msg_metadata_t *res)
{
  int msg_pos;
  int msg_size;
  int msg_type;

  msg_pos = start_pos;
  msg_type = -1;
  while (msg_pos < buf_size) {

    /* Check for NMEA message */
    msg_size = looks_like_nmea(&buf[msg_pos], buf_size - msg_pos);
    if (msg_size != LOOKS_NOT_LIKE_GPS_MSG) {
      msg_type = MSG_TYPE_NMEA;
      break;
    }

    /* Check for SiRF message */
    msg_size = looks_like_sirf(&buf[msg_pos], buf_size - msg_pos);
    if (msg_size != LOOKS_NOT_LIKE_GPS_MSG) {
      msg_type = MSG_TYPE_SIRF;
      break;
    }

    /* Check for u-blox message */
    msg_size = looks_like_ublox(&buf[msg_pos], buf_size - msg_pos);
    if (msg_size != LOOKS_NOT_LIKE_GPS_MSG) {
      msg_type = MSG_TYPE_UBLOX;
      break;
    }

    msg_pos += 1;
  }

  if (msg_type >= 0) {
    res->type = msg_type;
    if (msg_size == LOOKS_LIKE_TRUNCATED_MSG) {
      res->size = buf_size - msg_pos;
      res->is_truncated = true;
    }else {
      res->size = msg_size;
      res->is_truncated = false;
    }
    return msg_pos;
  }

  return -1;
}

static bool handle_msg(JNIEnv *env,
    jobject this,
    struct native_ctx_t *reader,
    uint8_t *msg,
    struct gps_msg_metadata_t *metadata) {

  struct gps_msg_status_t result;

  assert(env);
  assert(this);
  assert(msg);
  assert(metadata);

  datalogger_log_msg(&reader->datalogger, msg, metadata);

  if (reader->msg_rcvd_cb_active) {
    stats_unlock(&reader->stats);
    report_msg_rcvd(env, this, reader, msg, metadata);
    stats_lock(&reader->stats);
  }

  switch (metadata->type) {
    case MSG_TYPE_NMEA:
      put_nmea_msg(&reader->nmea, msg, metadata->size, &result);
      break;
    case MSG_TYPE_SIRF:
      put_sirf_msg(&reader->sirf, msg,  metadata->size, &result);
      break;
    case MSG_TYPE_UBLOX:
      {
        assert(metadata->size > 8);
        assert(msg[0] == 0xb5);
        LOGV("U-BLOX: 0x%02hhx:%02hhx", msg[2], msg[3]);
        result.is_valid = true;
        result.location_changed = false;
        result.err[0] = '\0';

        reader->stats.rcvd.ublox.total += 1;
        reader->stats.rcvd.ublox.last_msg_ts = reader->stats.rcvd.last_byte_ts;
      }
      break;
    default:
      result.is_valid = true;
      result.location_changed = false;
      result.err[0] = '\0';
      break;
  }

  if (result.err[0] != '\0') {
    if (result.is_valid)
      LOGV("WARN: %s", result.err);
    else
      LOGV("%s", result.err);
  }

  if (result.location_changed) {
    stats_unlock(&reader->stats);
    report_location(env, this, &result.location);
    stats_lock(&reader->stats);
  }

  return result.is_valid;
}

static void report_location(JNIEnv *env, jobject this, struct location_t *location)
{
  (*env)->CallVoidMethod(env, this, method_report_location,
      (jlong)location->time,
      (jdouble)location->latitude,
      (jdouble)location->longitude,
      (jdouble)location->altitude,
      (jfloat)location->accuracy,
      (jfloat)location->bearing,
      (jfloat)location->speed,
      (jint)location->satellites,
      (jboolean)location->is_valid,
      (jboolean)location->has_accuracy,
      (jboolean)location->has_altitude,
      (jboolean)location->has_bearing,
      (jboolean)location->has_speed
      );
  (*env)->ExceptionClear(env);
}

static void report_msg_rcvd(JNIEnv *env, jobject this, struct native_ctx_t *reader, uint8_t *msg, struct gps_msg_metadata_t *metadata)
{
  (*env)->CallVoidMethod(env, this, method_on_gps_message_received,
      reader->stream.rx_buf_direct,
      (jint)(msg - reader->stream.rx_buf),
      (jint)metadata->size,
      (jint)metadata->type
      );
  (*env)->ExceptionClear(env);
}

static inline void throw_exception(JNIEnv *env, const char *clazzName, const char *message) {
  (*env)->ThrowNew(env,
      (*env)->FindClass(env, clazzName),
      message);
}

static inline struct native_ctx_t *get_ctx(JNIEnv* env, jobject thiz)
{
  return (struct native_ctx_t *)(uintptr_t)(*env)->GetLongField(env, thiz, m_object_field);
}

static JNINativeMethod native_methods[] = {
  {"native_create", "()V", (void*)native_create},
  {"native_destroy", "()V", (void*)native_destroy},
  {"native_read_loop", "("
    "Lru0xdc/externalgps/usb/UsbSerialController$UsbSerialInputStream;"
      "Lru0xdc/externalgps/usb/UsbSerialController$UsbSerialOutputStream;"
      ")V", (void*)native_read_loop},
  { "native_get_stats",
    "(Lru0xdc/externalgps/StatsNative;)V",
    (void*)native_get_stats},
  { "native_msg_rcvd_cb", "(Z)V", (void*)native_msg_rcvd_cb },
  { "native_datalogger_configure", "(ZILjava/lang/String;Ljava/lang/String;)V", (void*)native_datalogger_configure },
  { "native_datalogger_start", "()V", (void*)native_datalogger_start },
  { "native_datalogger_stop", "()V", (void*)native_datalogger_stop },
};

int register_usb_converter_natives(JNIEnv* env) {
  jclass clazz = (*env)->FindClass(env, "Lru0xdc/externalgps/UsbGpsConverter$UsbReceiver$UsbServiceThread");

  if (clazz == NULL)
    return JNI_FALSE;

  if ((*env)->RegisterNatives(env, clazz, native_methods, sizeof(native_methods)
        / sizeof(native_methods[0])) != JNI_OK)
    return JNI_FALSE;

  m_object_field = (*env)->GetFieldID(env, clazz, "mObject", "J");
  if (m_object_field == NULL)
    return JNI_FALSE;

  method_report_location = (*env)->GetMethodID(env,
      clazz, "reportLocation", "(JDDDFFFIZZZZZ)V");
  if (method_report_location == NULL)
    return JNI_FALSE;

  method_on_gps_message_received = (*env)->GetMethodID(env,
      clazz, "onGpsMessageReceived", "(Ljava/nio/ByteBuffer;III)V");
  if (method_on_gps_message_received == NULL)
    return JNI_FALSE;

  return JNI_TRUE;
}

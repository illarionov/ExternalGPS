/* vim: set tabstop=2 shiftwidth=2 expandtab: */

#include <sys/types.h>
#include <sys/stat.h>
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <string.h>
#include <unistd.h>

#include <jni.h>
#include <android/log.h>

#include "usbconverter.h"
#include "datalogger.h"

#define TAG "nativeDataLogger"
#ifdef ENABLE_LOG
#define LOGV(x...) __android_log_print(ANDROID_LOG_VERBOSE,TAG,x)
#else
#define LOGV(...)  do {} while (0)
#endif
#define LOGI(x...) __android_log_print(ANDROID_LOG_INFO,TAG,x)

static void logfile_write_unlocked(struct datalogger_t * __restrict logger, const uint8_t * __restrict data, size_t size);
static bool logfile_flush_unlocked(struct datalogger_t *logger);
static void logfile_purge_unlocked(struct datalogger_t *logger);
static void datalogger_stop_unlocked(struct datalogger_t *logger);

void datalogger_init(struct datalogger_t *datalogger)
{
  pthread_mutex_init(&datalogger->mtx, NULL);
  datalogger->enabled = true;
  datalogger->buffer_pos = 0;
  datalogger->format = DATALOGGER_FORMAT_RAW;
  datalogger->logs_dir[0] = '\0';
  datalogger->log_prefix[0] = '\0';
  datalogger->cur_file_name[0] = '\0';
  clock_gettime(CLOCK_MONOTONIC_COARSE, &datalogger->last_flush_ts);
}

void datalogger_destroy(struct datalogger_t *logger)
{
  datalogger_stop(logger);
  pthread_mutex_destroy(&logger->mtx);
}

bool datalogger_configure(struct datalogger_t * __restrict logger,
    bool enabled,
    int format,
    const char * __restrict tracks_dir,
    const char * __restrict file_prefix)
{
  if ((format != DATALOGGER_FORMAT_RAW)
      && (format != DATALOGGER_FORMAT_NMEA))
    return false;

  pthread_mutex_lock(&logger->mtx);

  datalogger_stop_unlocked(logger);

  logger->enabled = enabled;
  logger->format = format;
  strncpy(logger->logs_dir, tracks_dir, sizeof(logger->logs_dir)-1);
  logger->logs_dir[sizeof(logger->logs_dir)-1]='\0';

  strncpy(logger->log_prefix, file_prefix, sizeof(logger->log_prefix)-1);
  logger->log_prefix[sizeof(logger->log_prefix)-1]='\0';

  LOGV("datalogger_configure() enabled: %c, format: %s, logs_dir: %s, log_prefix: %s",
      (logger->enabled ? 'Y' : 'N'),
      (logger->format == DATALOGGER_FORMAT_NMEA ? "nmea" : "raw"),
      logger->logs_dir,
      logger->log_prefix
      );

  pthread_mutex_unlock(&logger->mtx);

  return true;
}

void datalogger_log_raw_data(struct datalogger_t * __restrict logger, const uint8_t * __restrict buf, size_t size)
{
  pthread_mutex_lock(&logger->mtx);
  if (logger->enabled && (logger->format == DATALOGGER_FORMAT_RAW)) {
    logfile_write_unlocked(logger, buf, size);
  }
  pthread_mutex_unlock(&logger->mtx);
}

void datalogger_log_msg(struct datalogger_t * __restrict logger,
    const uint8_t * __restrict msg,
    const struct gps_msg_metadata_t * __restrict metadata)
{
  pthread_mutex_lock(&logger->mtx);
  if (logger->enabled
      && (logger->format == DATALOGGER_FORMAT_NMEA)
      && (metadata->type == MSG_TYPE_NMEA)) {
    logfile_write_unlocked(logger, msg, metadata->size);
  }
  pthread_mutex_unlock(&logger->mtx);
}

void datalogger_start(struct datalogger_t *logger)
{
  const char *ext;
  time_t tt;
  char timestamp[80];

  pthread_mutex_lock(&logger->mtx);

  if (logger->cur_file_name[0] != '\0') {
    datalogger_stop_unlocked(logger);
  }

  if (!logger->enabled) {
    pthread_mutex_unlock(&logger->mtx);
    return;
  }

  if (logger->format == DATALOGGER_FORMAT_NMEA)
    ext = "nmea";
  else
    ext = "raw";

  clock_gettime(CLOCK_MONOTONIC_COARSE, &logger->last_flush_ts);

  tt = time(NULL);
  if (strftime(timestamp, sizeof(timestamp), "%Y%b%d_%H-%M", localtime(&tt)) == 0) {
    snprintf(timestamp, sizeof(timestamp), "%ld", tt);
  }

  snprintf(logger->cur_file_name, sizeof(logger->cur_file_name),
      "%s/%s_%s.%s", logger->logs_dir, logger->log_prefix, timestamp, ext);

  LOGV("datalogger_start() file: %s", logger->cur_file_name);

  pthread_mutex_unlock(&logger->mtx);
}

void datalogger_flush(struct datalogger_t *logger)
{
  LOGV("datalogger_stop()");
  pthread_mutex_lock(&logger->mtx);
  logfile_flush_unlocked(logger);
  pthread_mutex_unlock(&logger->mtx);
}

void datalogger_stop(struct datalogger_t *logger)
{
  LOGV("datalogger_stop()");
  pthread_mutex_lock(&logger->mtx);
  datalogger_stop_unlocked(logger);
  pthread_mutex_unlock(&logger->mtx);
}

static void datalogger_stop_unlocked(struct datalogger_t *logger)
{
  if (!logfile_flush_unlocked(logger))
    logfile_purge_unlocked(logger);
  assert(logger->buffer_pos == 0);
  logger->cur_file_name[0] = '\0';
}

static bool logfile_flush_unlocked(struct datalogger_t *logger)
{
  int fd;
  unsigned retry;
  size_t written_total;

  if (logger->buffer_pos == 0)
    return true;

  if (logger->cur_file_name[0] == '\0')
    return true;

  clock_gettime(CLOCK_MONOTONIC_COARSE, &logger->last_flush_ts);

  fd = open(logger->cur_file_name, O_WRONLY | O_APPEND | O_CREAT, 00644);
  if (fd < 0) {
    LOGV("open() error %s", strerror(errno));
    return false;
  }
  written_total = 0;

  for (retry=0; retry<10; ++retry) {
    ssize_t written;
    written = write(fd, &logger->buffer[written_total], logger->buffer_pos - written_total);
    if (written < 0) {
      LOGV("write() error %s", strerror(errno));
      break;
    }
    written_total += written;
    if (written_total == logger->buffer_pos) {
      break;
    }
    if (written == 0)
      usleep(200000);
  }

  if (close(fd) < 0) {
    LOGI("close() error %s", strerror(errno));
    written_total = 0;
  }

  LOGV("flushed %lu bytes", (unsigned long)written_total);

  if (written_total == 0) {
    LOGV("written_total=0");
  }else if (written_total == logger->buffer_pos) {
    logger->buffer_pos = 0;
  }else {
    LOGV("written %lu of %lu", (unsigned long)written_total, (unsigned long)logger->buffer_pos);
    memmove(logger->buffer, &logger->buffer[written_total], logger->buffer_pos - written_total);
    logger->buffer_pos -= written_total;
  }

  return (logger->buffer_pos == 0);
}

static void logfile_purge_unlocked(struct datalogger_t *logger)
{
  if (logger->buffer_pos != 0) {
    LOGV("purged %u bytes", logger->buffer_pos);
    logger->buffer_pos = 0;
  }
}

static void logfile_write_unlocked(struct datalogger_t * __restrict logger, const uint8_t * __restrict data, size_t size)
{
  /* LOGV("logfile_write_unlocked size: %u, file: %s", size, logger->cur_file_name); */

  if (size == 0)
    return;

  if (logger->cur_file_name[0] == '\0')
    return;

  if (sizeof(logger->buffer) < size) {
    /* XXX */
    return;
  }

  if (logger->buffer_pos + size >= sizeof(logger->buffer)) {
    if (!logfile_flush_unlocked(logger))
      logfile_purge_unlocked(logger);
  }

  memcpy(&logger->buffer[logger->buffer_pos], data, size);
  logger->buffer_pos += size;

  assert(logger->buffer_pos < sizeof(logger->buffer));

  if ((logger->buffer_pos >= DATA_LOGGER_WATERMARK)) {
    logfile_flush_unlocked(logger);
  }else {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC_COARSE, &ts);
    if ((ts.tv_sec < logger->last_flush_ts.tv_sec)
        || (ts.tv_sec - logger->last_flush_ts.tv_sec) >= DATA_LOGGER_FLUSH_INTERVAL_SEC) {
      logfile_flush_unlocked(logger);
    }
  }
}

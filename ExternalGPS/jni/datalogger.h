/* vim: set tabstop=2 shiftwidth=2 expandtab: */

#ifndef _DATALOGGER_H
#define _DATALOGGER_H

#define DATA_LOGGER_BUFFER_SIZE (512*1024)
#define DATA_LOGGER_WATERMARK (DATA_LOGGER_BUFFER_SIZE-8*1024)
#define DATA_LOGGER_FLUSH_INTERVAL_SEC 3*60

struct datalogger_t {
  pthread_mutex_t mtx;
  size_t buffer_pos;
  bool enabled;
  struct timespec last_flush_ts;
  enum {
    DATALOGGER_FORMAT_RAW = 1,
    DATALOGGER_FORMAT_NMEA = 2
  } format;

  char logs_dir[PATH_MAX];
  char log_prefix[80];

  char cur_file_name[NAME_MAX+PATH_MAX];

  char buffer[DATA_LOGGER_BUFFER_SIZE];
};

void datalogger_init(struct datalogger_t *datalogger);
bool datalogger_configure(struct datalogger_t * __restrict logger,
    bool enabled,
    int format,
    const char * __restrict tracks_dir,
    const char * __restrict file_prefix);
void datalogger_start(struct datalogger_t *logger);
void datalogger_log_raw_data(struct datalogger_t * __restrict logger,
    const uint8_t * __restrict buf,
    size_t size);
void datalogger_log_msg(struct datalogger_t * __restrict logger,
    const uint8_t * __restrict msg,
    const struct gps_msg_metadata_t * __restrict metadata);
void datalogger_flush(struct datalogger_t *logger);
void datalogger_stop(struct datalogger_t *logger);
void datalogger_destroy(struct datalogger_t *logger);

#endif /* _DATALOGGER_H */

#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <poll.h>
#include <stdarg.h>
#include <sys/ioctl.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/*
 * 普通 App（无 root）能否用 USB 蓝牙模块做 HCI 通信，这一层给出可判读的证据。
 *
 * Java 的 UsbDeviceConnection.bulkTransfer 只回一个 -1，分不清"设备没数据"和"内核拒绝"。
 * 这里借用同一个已授权 fd，直接走 usbfs 异步 URB 队列：
 *   - OUT URB 完成 status=0 是不依赖模块应答的正对照，证明 App 的 URB 通路可用；
 *   - 先排好 IN URB 再发命令，才能把"模块沉默"与"读通道被拦"分开判读。
 * 全程有界（约 3 秒），只记录返回值与 errno，不猜测原因。
 */

#define REPORT_SIZE 2048
#define IN_ARMS 3
#define IN_BYTES 64
#define MAX_PENDING 8

static unsigned char out_buffer[8];
static unsigned char in_buffer[IN_ARMS][IN_BYTES];
static struct usbdevfs_urb *pending[MAX_PENDING];
static int pending_count;

typedef struct {
    char text[REPORT_SIZE];
    size_t offset;
    int truncated;
} Report;

static void append(Report *report, const char *format, ...) {
    if (report->truncated || report->offset >= REPORT_SIZE - 1) {
        report->truncated = 1;
        return;
    }
    va_list arguments;
    va_start(arguments, format);
    int written = vsnprintf(report->text + report->offset, REPORT_SIZE - report->offset, format, arguments);
    va_end(arguments);
    if (written < 0) {
        report->truncated = 1;
        return;
    }
    if ((size_t) written >= REPORT_SIZE - report->offset) {
        report->offset = REPORT_SIZE - 1;
        report->truncated = 1;
        return;
    }
    report->offset += (size_t) written;
}

static void track(struct usbdevfs_urb *urb) {
    if (pending_count < MAX_PENDING) pending[pending_count++] = urb;
}

static void untrack(struct usbdevfs_urb *urb) {
    for (int index = 0; index < pending_count; index++) {
        if (pending[index] != urb) continue;
        pending[index] = pending[pending_count - 1];
        pending[--pending_count] = NULL;
        return;
    }
}

/* 丢弃所有仍未完成的 URB，并回收它们的 -ENOENT 完成事件，避免污染后续会话。 */
static void discard_all(int fd) {
    int count = pending_count;
    for (int index = 0; index < count; index++) ioctl(fd, USBDEVFS_DISCARDURB, pending[index]);
    for (int index = 0; index < count; index++) {
        struct pollfd descriptor = { .fd = fd, .events = POLLIN };
        if (poll(&descriptor, 1, 300) <= 0) continue;
        struct usbdevfs_urb *urb = NULL;
        if (ioctl(fd, USBDEVFS_REAPURB, &urb) == 0 && urb != NULL) free(urb);
    }
    for (int index = 0; index < MAX_PENDING; index++) pending[index] = NULL;
    pending_count = 0;
}

/* 收割一个已完成的 URB；超时返回 NULL。用 poll + REAPURB，便于有界退出。 */
static struct usbdevfs_urb *reap(int fd, int timeout_ms, int *reap_errno) {
    struct pollfd descriptor = { .fd = fd, .events = POLLIN };
    int ready = poll(&descriptor, 1, timeout_ms);
    if (reap_errno != NULL) *reap_errno = ready < 0 ? errno : 0;
    if (ready <= 0) return NULL;
    struct usbdevfs_urb *urb = NULL;
    if (ioctl(fd, USBDEVFS_REAPURB, &urb) < 0) {
        if (reap_errno != NULL) *reap_errno = errno;
        return NULL;
    }
    untrack(urb);
    return urb;
}

static struct usbdevfs_urb *allocate(unsigned char type, unsigned char endpoint,
                                     unsigned char *buffer, int length) {
    struct usbdevfs_urb *urb = calloc(1, sizeof(*urb));
    if (urb == NULL) return NULL;
    urb->type = type;
    urb->endpoint = endpoint;
    urb->buffer = buffer;
    urb->buffer_length = length;
    return urb;
}

static void submit_reset(Report *report, int fd, unsigned char endpoint_out, const char *label) {
    static const unsigned char hci_reset[] = { 0x01, 0x03, 0x0C, 0x00 };
    memcpy(out_buffer, hci_reset, sizeof(hci_reset));
    struct usbdevfs_urb *out = allocate(USBDEVFS_URB_TYPE_BULK, endpoint_out, out_buffer,
                                        (int) sizeof(hci_reset));
    if (out == NULL) {
        append(report, " | %s alloc failed", label);
        return;
    }
    if (ioctl(fd, USBDEVFS_SUBMITURB, out) < 0) {
        append(report, " | %s submit errno=%d", label, errno);
        free(out);
        return;
    }
    track(out);
}

static void dump_hex(Report *report, const unsigned char *data, int length) {
    int shown = length < 16 ? length : 16;
    append(report, " IN_HEX=");
    for (int index = 0; index < shown; index++) append(report, "%02x ", data[index]);
}

/* 正对照：同一个 fd 上的同步 USBDEVFS_BULK。它能完成，就说明 URB 不完成不是通路问题。 */
static void synchronous_bulk_out(Report *report, int fd, unsigned char endpoint_out) {
    static const unsigned char hci_reset[] = { 0x01, 0x03, 0x0C, 0x00 };
    memcpy(out_buffer, hci_reset, sizeof(hci_reset));
    struct usbdevfs_bulktransfer transfer = {
        .ep = endpoint_out,
        .len = (unsigned int) sizeof(hci_reset),
        .timeout = 1200,
        .data = out_buffer,
    };
    /* USBDEVFS_BULK 的返回值即实际传输字节数，失败时为负 errno。 */
    int ret = ioctl(fd, USBDEVFS_BULK, &transfer);
    append(report, " | SYNC_BULK ret=%d errno=%d", ret, ret < 0 ? errno : 0);
}

/* 同步读一个 IN 端点：与 Java 的 bulkTransfer 同一条内核路径，用来核对"读不到"是否一致。 */
static void synchronous_bulk_in(Report *report, int fd, unsigned char endpoint_in) {
    struct usbdevfs_bulktransfer transfer = {
        .ep = endpoint_in,
        .len = IN_BYTES,
        .timeout = 1200,
        .data = in_buffer[IN_ARMS - 1],
    };
    int ret = ioctl(fd, USBDEVFS_BULK, &transfer);
    append(report, " | SYNC_IN ret=%d errno=%d", ret, ret < 0 ? errno : 0);
    if (ret > 0) dump_hex(report, (const unsigned char *) transfer.data, ret);
}

JNIEXPORT jstring JNICALL
Java_com_shilapi_xcertplay_btprobe_UsbBtNative_hciProbe(JNIEnv *env, jobject self, jint fd,
                                                       jint interface_id, jint endpoint_out,
                                                       jint endpoint_in) {
    (void) self;
    Report report;
    memset(&report, 0, sizeof(report));
    pending_count = 0;

    struct usbdevfs_getdriver driver;
    memset(&driver, 0, sizeof(driver));
    driver.interface = (unsigned int) interface_id;
    int get = ioctl(fd, USBDEVFS_GETDRIVER, &driver);
    append(&report, "GETDRIVER if=%d ret=%d errno=%d driver=%s",
           interface_id, get, get < 0 ? errno : 0, get == 0 ? driver.driver : "-");

    /* 1) 先把 IN URB 排好：之后任何一次写出触发的事件都能被收到。 */
    int armed = 0;
    for (int index = 0; index < IN_ARMS; index++) {
        struct usbdevfs_urb *in = allocate(USBDEVFS_URB_TYPE_INTERRUPT, (unsigned char) endpoint_in,
                                           in_buffer[index], IN_BYTES);
        if (in == NULL) break;
        if (ioctl(fd, USBDEVFS_SUBMITURB, in) < 0) {
            append(&report, " | IN submit errno=%d", errno);
            free(in);
            break;
        }
        track(in);
        armed++;
    }
    append(&report, " | IN_ARMED=%d", armed);

    /* 2) 正对照：同步 bulk OUT。它若完成，说明 URB 不完成不是通路/权限问题。 */
    synchronous_bulk_out(&report, fd, (unsigned char) endpoint_out);

    /* 3) 收割：写出之后模块有反应吗？ */
    int events = 0;
    for (int index = 0; index < armed; index++) {
        int error_number = 0;
        struct usbdevfs_urb *completed = reap(fd, 900, &error_number);
        if (completed == NULL) {
            append(&report, " | IN_STATUS=none reap_errno=%d", error_number);
            break;
        }
        if (completed->endpoint == (unsigned char) endpoint_in) {
            append(&report, " | IN_STATUS=%d IN_BYTES=%d", completed->status, completed->actual_length);
            if (completed->status == 0 && completed->actual_length > 0) {
                dump_hex(&report, completed->buffer, completed->actual_length);
                events++;
                free(completed);
                break;
            }
        } else {
            append(&report, " | OTHER_STATUS=%d ep=0x%02x", completed->status, completed->endpoint);
        }
        free(completed);
    }
    append(&report, " | IN_EVENTS=%d", events);

    /* 4) 异步 OUT URB 是否真的能完成（上一轮它没有完成，这轮要给出可读结果）。 */
    submit_reset(&report, fd, (unsigned char) endpoint_out, "OUT_ASYNC");
    int error_number = 0;
    struct usbdevfs_urb *out = reap(fd, 1500, &error_number);
    if (out == NULL) {
        append(&report, " | OUT_STATUS=none reap_errno=%d", error_number);
    } else {
        append(&report, " | OUT_STATUS=%d OUT_ACTUAL=%d", out->status, out->actual_length);
        free(out);
    }

    /* 5) 先清理所有排队 URB，再同步读 IN：避免与已排队的 URB 抢同一块缓冲，也便于归因。 */
    discard_all(fd);
    synchronous_bulk_in(&report, fd, (unsigned char) endpoint_in);
    return (*env)->NewStringUTF(env, report.text);
}

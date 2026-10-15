#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <errno.h>
#include <string.h>
#include <stdio.h>

/* Borrow the already-authorized Android FD. Never open a device path or close this FD. */
JNIEXPORT jstring JNICALL
Java_com_shilapi_xcertplay_usbcheck_NativeUsbProbe_claim(JNIEnv *env, jobject self,
                                                       jint fd, jint interface_id) {
    (void)self;
    char report[1024];
    struct usbdevfs_getdriver driver;
    memset(&driver, 0, sizeof(driver));
    driver.interface = interface_id;
    int get = ioctl(fd, USBDEVFS_GETDRIVER, &driver);
    int get_errno = get < 0 ? errno : 0;
    driver.driver[sizeof(driver.driver) - 1] = 0;
    unsigned int number = (unsigned int) interface_id;
    int result = ioctl(fd, USBDEVFS_CLAIMINTERFACE, &number);
    int claim_errno = result < 0 ? errno : 0;
    int atomic = -1, atomic_errno = 0;
    /* Only EBUSY with a known kernel driver permits an atomic disconnect experiment.
       Missing driver evidence, usbfs, and permission errors are deliberately not guessed. */
    if (result < 0 && claim_errno == EBUSY && get == 0 && strcmp(driver.driver, "usbfs") != 0) {
        struct usbdevfs_disconnect_claim request;
        memset(&request, 0, sizeof(request));
        request.interface = number;
        request.flags = USBDEVFS_DISCONNECT_CLAIM_EXCEPT_DRIVER;
        strcpy(request.driver, "usbfs");
        atomic = ioctl(fd, USBDEVFS_DISCONNECT_CLAIM, &request);
        atomic_errno = atomic < 0 ? errno : 0;
    }
    int ok = result == 0 || atomic == 0;
    snprintf(report, sizeof(report),
             "{\"ok\":%s,\"iface\":%d,\"driver\":\"%s\",\"getDriver\":%d,\"getDriverErrno\":%d,"
             "\"claim\":%d,\"claimErrno\":%d,\"atomic\":%d,\"atomicErrno\":%d,\"atomicAttempted\":%s}",
             ok ? "true" : "false", interface_id, get == 0 ? driver.driver : "unknown",
             get, get_errno, result, claim_errno, atomic, atomic_errno,
             result < 0 && claim_errno == EBUSY && get == 0 && strcmp(driver.driver, "usbfs") != 0 ? "true" : "false");
    return (*env)->NewStringUTF(env, report);
}

JNIEXPORT jstring JNICALL
Java_com_shilapi_xcertplay_usbcheck_NativeUsbProbe_selectConfiguration(JNIEnv *env, jobject self,
                                                                    jint fd, jint configuration) {
    (void)self;
    unsigned int value = (unsigned int) configuration;
    int result = ioctl(fd, USBDEVFS_SETCONFIGURATION, &value);
    int saved_errno = result < 0 ? errno : 0;
    char report[160];
    snprintf(report, sizeof(report), "{\"ok\":%s,\"targetCfg\":%d,\"setConfiguration\":%d,\"errno\":%d}",
             result == 0 ? "true" : "false", configuration, result, saved_errno);
    return (*env)->NewStringUTF(env, report);
}

/* Configuration experiment: detach known active interfaces without claiming/releasing them.
   All IDs come from the authorized Apple's active configuration; never touch usbfs holders. */
JNIEXPORT jstring JNICALL
Java_com_shilapi_xcertplay_usbcheck_NativeUsbProbe_disconnectAndSelect(JNIEnv *env, jobject self,
                                                                     jint fd, jint configuration,
                                                                     jintArray interfaces) {
    (void)self;
    unsigned int value = (unsigned int)configuration;
    char report[4096], steps[3200] = "";
    size_t used = 0;
    jsize count = (*env)->GetArrayLength(env, interfaces);
    if (count > 32) count = 32;
    jint ids[32];
    (*env)->GetIntArrayRegion(env, interfaces, 0, count, ids);
    int refused = 0;
    for (int i = 0; i < count; i++) {
        struct usbdevfs_getdriver driver;
        memset(&driver, 0, sizeof(driver)); driver.interface = ids[i];
        int get = ioctl(fd, USBDEVFS_GETDRIVER, &driver);
        int ge = get < 0 ? errno : 0;
        driver.driver[sizeof(driver.driver)-1] = 0;
        int result = -1, de = 0;
        if (get == 0 && strcmp(driver.driver, "usbfs") == 0) {
            refused = 1; de = EBUSY;
        } else if (get == 0) {
            struct usbdevfs_ioctl request = { .ifno = ids[i], .ioctl_code = USBDEVFS_DISCONNECT, .data = NULL };
            result = ioctl(fd, USBDEVFS_IOCTL, &request);
            de = result < 0 ? errno : 0;
            if (result < 0 && de != ENODATA) refused = 1;
        } else if (ge != ENODATA) {
            refused = 1;
        }
        int n = snprintf(steps+used, sizeof(steps)-used,
                         "%s{\"iface\":%d,\"driver\":\"%s\",\"getErrno\":%d,\"disconnect\":%d,\"errno\":%d}",
                         i ? "," : "", ids[i], get == 0 ? driver.driver : "none", ge, result, de);
        if (n < 0 || (size_t)n >= sizeof(steps)-used) { refused = 1; break; }
        used += n;
        if (refused) break;
    }
    int result = refused ? -1 : ioctl(fd, USBDEVFS_SETCONFIGURATION, &value);
    int saved_errno = refused ? EBUSY : (result < 0 ? errno : 0);
    snprintf(report, sizeof(report), "{\"ok\":%s,\"targetCfg\":%d,\"directDetach\":[%s],\"setConfiguration\":%d,\"errno\":%d}",
             result == 0 ? "true" : "false", configuration, steps, result, saved_errno);
    return (*env)->NewStringUTF(env, report);
}

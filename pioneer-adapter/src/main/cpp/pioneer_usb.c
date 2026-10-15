#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <errno.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <time.h>

/* Copy before kernel wait; never pin ART's heap in a JNI critical section. */
struct write_result {int transferred;uint64_t copy_ns,io_ns;};
static uint64_t usb_ns(void){struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);return (uint64_t)t.tv_sec*1000000000+t.tv_nsec;}
static struct write_result write_usb(JNIEnv *env,int fd,int endpoint,jbyteArray packet,int timeout_ms,int measured){
    struct write_result r={.transferred=-EINVAL,.copy_ns=0,.io_ns=0};
    int n=(*env)->GetArrayLength(env,packet);
    if(fd<0||(endpoint&0x80)||n<=0||n>1048576||timeout_ms<=0)return r;
    uint64_t before=measured?usb_ns():0;
    unsigned char *bytes=malloc(n);if(!bytes){r.transferred=-ENOMEM;return r;}
    (*env)->GetByteArrayRegion(env,packet,0,n,(jbyte*)bytes);
    if((*env)->ExceptionCheck(env)){free(bytes);return r;}
    struct usbdevfs_bulktransfer request;
    memset(&request,0,sizeof(request));request.ep=endpoint;request.len=n;request.timeout=timeout_ms;request.data=bytes;
    uint64_t writing=measured?usb_ns():0;if(measured)r.copy_ns=writing-before;
    int result=ioctl(fd,USBDEVFS_BULK,&request);int error=result<0?errno:0;
    if(measured)r.io_ns=usb_ns()-writing;
    free(bytes);r.transferred=result<0?-error:result;return r;
}
JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_pioneer_PioneerUsbNative_bulkWrite(
    JNIEnv *env,jobject self,jint fd,jint endpoint,jbyteArray packet,jint timeout_ms){
    (void)self;return write_usb(env,fd,endpoint,packet,timeout_ms,0).transferred;
}
JNIEXPORT jlongArray JNICALL Java_com_shilapi_xcertplay_pioneer_PioneerUsbNative_bulkWriteMeasured(
    JNIEnv *env,jobject self,jint fd,jint endpoint,jbyteArray packet,jint timeout_ms){
    (void)self;struct write_result r=write_usb(env,fd,endpoint,packet,timeout_ms,1);
    jlong fields[]={r.transferred,(jlong)r.copy_ns,(jlong)r.io_ns};
    jlongArray result=(*env)->NewLongArray(env,3);if(result)(*env)->SetLongArrayRegion(env,result,0,3,fields);return result;
}

/* Borrow the already-authorized Android FD. Never open a device path or close this FD. */
JNIEXPORT jstring JNICALL
Java_com_shilapi_xcertplay_pioneer_PioneerUsbNative_claim(JNIEnv *env, jobject self,
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
Java_com_shilapi_xcertplay_pioneer_PioneerUsbNative_selectConfiguration(JNIEnv *env, jobject self,
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
Java_com_shilapi_xcertplay_pioneer_PioneerUsbNative_disconnectAndSelect(JNIEnv *env, jobject self,
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

JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_pioneer_PioneerUsbNative_clearHalt(
    JNIEnv *env,jobject self,jint fd,jint endpoint) {
    (void)env;(void)self;unsigned int ep=endpoint;
    int r=ioctl(fd,USBDEVFS_CLEAR_HALT,&ep);return r<0?-errno:r;
}
JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_pioneer_PioneerUsbNative_resetDevice(
    JNIEnv *env,jobject self,jint fd) {
    (void)env;(void)self;if(fd<0)return -EINVAL;
    int r=ioctl(fd,USBDEVFS_RESET,0);return r<0?-errno:r;
}
JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_pioneer_PioneerUsbNative_readNotification(
    JNIEnv *env,jobject self,jint fd,jint endpoint,jbyteArray buffer,jint timeout_ms) {
    (void)self;int n=(*env)->GetArrayLength(env,buffer);
    if(fd<0||!(endpoint&0x80)||n<=0||n>1024||timeout_ms<=0)return -EINVAL;
    unsigned char bytes[1024];
    struct usbdevfs_bulktransfer request={.ep=(unsigned int)endpoint,.len=(unsigned int)n,
        .timeout=(unsigned int)timeout_ms,.data=bytes};
    int r=ioctl(fd,USBDEVFS_BULK,&request);int error=r<0?errno:0;
    if(r>0)(*env)->SetByteArrayRegion(env,buffer,0,r,(jbyte*)bytes);
    return r<0?-error:r;
}
JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_pioneer_PioneerUsbNative_setAlternate(
    JNIEnv *env,jobject self,jint fd,jint iface,jint alternate) {
    (void)env;(void)self;struct usbdevfs_setinterface s={.interface=iface,.altsetting=alternate};
    int r=ioctl(fd,USBDEVFS_SETINTERFACE,&s);return r<0?-errno:r;
}

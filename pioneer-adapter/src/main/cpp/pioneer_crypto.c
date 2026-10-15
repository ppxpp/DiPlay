#include <jni.h>
#include <stdlib.h>
#include <stdint.h>
#include "monocypher.h"

JNIEXPORT jbyteArray JNICALL Java_com_shilapi_xcertplay_pioneer_PioneerNativeCrypto_transform(JNIEnv *env,jobject self,jboolean seal,jbyteArray key,jbyteArray nonce,jbyteArray input,jbyteArray aad) {
 (void)self;
 jsize n=(*env)->GetArrayLength(env,input),an=(*env)->GetArrayLength(env,aad);
 if((*env)->GetArrayLength(env,key)!=32||(*env)->GetArrayLength(env,nonce)!=12||(!seal&&n<16)||n>32*1024*1024) {
  (*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/IllegalArgumentException"),"Invalid ChaCha20-Poly1305 input size");return NULL;
 }
 uint8_t k[32],iv[12];(*env)->GetByteArrayRegion(env,key,0,32,(jbyte*)k);(*env)->GetByteArrayRegion(env,nonce,0,12,(jbyte*)iv);
 jsize text=seal?n:n-16,outn=seal?n+16:n-16;
 uint8_t *src=malloc(n?n:1),*ad=malloc(an?an:1),*out=malloc(outn?outn:1);
 if(!src||!ad||!out){free(src);free(ad);free(out);crypto_wipe(k,32);(*env)->ThrowNew(env,(*env)->FindClass(env,"java/lang/OutOfMemoryError"),"native AEAD allocation");return NULL;}
 (*env)->GetByteArrayRegion(env,input,0,n,(jbyte*)src);(*env)->GetByteArrayRegion(env,aad,0,an,(jbyte*)ad);
 crypto_aead_ctx ctx;crypto_aead_init_ietf(&ctx,k,iv);
 int result=0;
 // A fresh context for every AirPlay nonce preserves RFC8439 semantics (no streaming ratchet).
 if(seal)crypto_aead_write(&ctx,out,out+text,ad,an,src,text);
 else result=crypto_aead_read(&ctx,out,src+text,ad,an,src,text);
 crypto_wipe(&ctx,sizeof(ctx));crypto_wipe(k,32);crypto_wipe(src,n);free(src);free(ad);
 if(result){crypto_wipe(out,outn);free(out);(*env)->ThrowNew(env,(*env)->FindClass(env,"javax/crypto/AEADBadTagException"),"ChaCha20-Poly1305 authentication failed");return NULL;}
 jbyteArray output=(*env)->NewByteArray(env,outn);if(output)(*env)->SetByteArrayRegion(env,output,0,outn,(jbyte*)out);
 crypto_wipe(out,outn);free(out);return output;
}

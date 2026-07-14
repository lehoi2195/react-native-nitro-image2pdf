#include <jni.h>
#include <fbjni/fbjni.h>
#include "NitroImage2pdfOnLoad.hpp"

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
  return facebook::jni::initialize(vm, []() {
    margelo::nitro::nitroimage2pdf::registerAllNatives();
  });
}
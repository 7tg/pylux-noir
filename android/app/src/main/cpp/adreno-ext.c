// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
//
// JNI shim for the Qualcomm Adreno Frame Motion Engine, exposed to OpenGL ES as
// GL_QCOM_frame_extrapolation (Khronos OpenGL ES extension #333). Both functions must be
// called on a thread with an EGL/GLES 3.x context current (the GpuVideoRenderer thread).

#include <jni.h>
#include <string.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/log.h>

#define LOG_TAG "AdrenoExt"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

#define ADRENO_JNI(name) Java_com_metallic_chiaki_stream_gpu_AdrenoExt_##name

typedef void (GL_APIENTRYP PFNGLEXTRAPOLATETEX2DQCOMPROC)(GLuint src1, GLuint src2, GLuint output, GLfloat scaleFactor);

static PFNGLEXTRAPOLATETEX2DQCOMPROC s_extrapolate = NULL;

static int gl_has_extension(const char *name)
{
	GLint count = 0;
	glGetIntegerv(GL_NUM_EXTENSIONS, &count);
	for(GLint i = 0; i < count; i++)
	{
		const char *ext = (const char *)glGetStringi(GL_EXTENSIONS, (GLuint)i);
		if(ext && strcmp(ext, name) == 0)
			return 1;
	}
	return 0;
}

JNIEXPORT jboolean JNICALL ADRENO_JNI(nativeProbeFrameExtrapolation)(JNIEnv *env, jclass cls)
{
	(void)env; (void)cls;
	if(eglGetCurrentContext() == EGL_NO_CONTEXT)
	{
		LOGW("probe called without a current EGL context");
		return JNI_FALSE;
	}
	if(!gl_has_extension("GL_QCOM_frame_extrapolation"))
	{
		LOGI("GL_QCOM_frame_extrapolation not advertised by driver (renderer: %s)", (const char *)glGetString(GL_RENDERER));
		return JNI_FALSE;
	}
	s_extrapolate = (PFNGLEXTRAPOLATETEX2DQCOMPROC)eglGetProcAddress("glExtrapolateTex2DQCOM");
	LOGI("GL_QCOM_frame_extrapolation supported=%d (renderer: %s)", s_extrapolate != NULL, (const char *)glGetString(GL_RENDERER));
	return s_extrapolate != NULL ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL ADRENO_JNI(nativeExtrapolateTex2D)(JNIEnv *env, jclass cls, jint src1, jint src2, jint output, jfloat scale)
{
	(void)env; (void)cls;
	if(!s_extrapolate)
		return;
	s_extrapolate((GLuint)src1, (GLuint)src2, (GLuint)output, scale);
}

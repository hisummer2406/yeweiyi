/*
 * Copyright 2009-2011 Cedric Priscal
 *
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copywriteBytes of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <termios.h>
#include <unistd.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <jni.h>
#include <strings.h>


#include "android/log.h"
static const char *TAG = "JNI C file ATG";

#define FALSE	-1
#define TRUE	0

#define PULL_UP_GPIO_PE2 1
#define PULL_DOWN_GPIO_PE2 0

#define CFG_FLAG 1

#define LOGI(fmt, args...) __android_log_print(ANDROID_LOG_INFO,  TAG, fmt, ##args)
#define LOGD(fmt, args...) __android_log_print(ANDROID_LOG_DEBUG, TAG, fmt, ##args)
#define LOGE(fmt, args...) __android_log_print(ANDROID_LOG_ERROR, TAG, fmt, ##args)


jfloat Java_atg_jni_ATGJNI_getLitre(JNIEnv* env,jobject obj, jfloat oilHieght, jintArray hightArray,
																 jdoubleArray litreArray) {
	float FfTab_Height[30];
	float FfTab_Volume[30];

	int *arr1; //定义一个整形指针
	arr1 = (*env)->GetIntArrayElements(env, hightArray, NULL); //得到一个指向原始数据类型内容的指针
	jint length = (*env)->GetArrayLength(env, hightArray); //得到数组的长度
	int u = 0;
	for (u = 0; u < length; u++) {
		FfTab_Height[u] = arr1[u];
	}
	(*env)->ReleaseIntArrayElements(env, hightArray, arr1, 0);

	double * arr2; //定义一个整形指针
	arr2 = (*env)->GetDoubleArrayElements(env, litreArray, NULL); //得到一个指向原始数据类型内容的指针
	length = (*env)->GetArrayLength(env, litreArray); //得到数组的长度

	for (u = 0; u < length; u++) {
		FfTab_Volume[u] = arr2[u];
	}
	(*env)->ReleaseDoubleArrayElements(env, litreArray, arr2, 0);

	float fHei = oilHieght;

	float mpx = 0, returnvalue;
	unsigned short i, j, k;
	//char s[10];
	float height1[7], volume1[7];
	float mhv3[7], mhv4[7];
	unsigned int FfMax_Volume;

	//如果油高低于最低点的高度，就返回最低点的体积
	if (fHei <= FfTab_Height[0])
		return FfTab_Volume[0];
	// 如果油高的高度高于最高点，则返回最高点的体积
	if (fHei >= FfTab_Height[29])
		return FfTab_Volume[29];
	for (i = 0; i < 29; i++) {
		if ((fHei >= FfTab_Height[i]) && (fHei <= FfTab_Height[i + 1]))
			break;
	}
	if ((i >= 3) && (i <= 26)) {
		k = i;
		for (i = k - 3; i < k + 4; i++) {
			height1[i - k + 3] = FfTab_Height[i];
			volume1[i - k + 3] = FfTab_Volume[i];
		}
	}
	if (i < 3) {
		for (i = 0; i < 7; i++) {
			height1[i] = FfTab_Height[i];
			volume1[i] = FfTab_Volume[i];
		}
	}
	if (i > 26) {
		for (i = 23; i < 30; i++) {
			height1[i - 23] = FfTab_Height[i];
			volume1[i - 23] = FfTab_Volume[i];
		}
	}
	//根据拉格朗日多项式插值计算任意点的体积根据拉格朗日插值公式计算

	for (i = 0; i < 7; i++) {
		mhv3[i] = 1.0;
		mhv4[i] = 1.0;
	}
	for (j = 0; j < 7; j++) {
		for (i = 0; i < 7; i++) {
			if (i != j) {
				mhv3[j] = mhv3[j] * (fHei - height1[i]);
				mhv4[j] = mhv4[j] * (height1[j] - height1[i]);
			}
		}
		mpx = mpx + mhv3[j] * volume1[j] / mhv4[j];
	}

	//由于现场存在油罐实际直径比罐表中最大尺寸大得多可能而造成有负值出现
	//此时返回为零
	if (mpx < 0)
		return 0;
	//如果计算的容积值大于最大容积，就返回最大容积
	FfMax_Volume = FfTab_Volume[29]; //得到最大体积
	if (mpx >= FfMax_Volume)
		return FfMax_Volume;
	//sprintf(s,"%7.2f",mpx);
	//returnvalue = StrToFloat(s);
	returnvalue = mpx;
	if (returnvalue < 1 || returnvalue > 5000000)
		return 0;

	return (jfloat) returnvalue;
}
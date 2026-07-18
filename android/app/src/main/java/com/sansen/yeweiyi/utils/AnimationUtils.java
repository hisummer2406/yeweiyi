package com.sansen.yeweiyi.utils;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.view.View;

public class AnimationUtils {

	/**
	 * 抛物线
	 * 
	 * @param view
	 */
	public static void AlarmAnimtion(final View view) {

		float cx = view.getY();

		ObjectAnimator anim = ObjectAnimator//
				.ofFloat(view, "y", 0.0F, 1.0F)//
				.setDuration(10000);//
		anim.start();
		anim.addUpdateListener(new AnimatorUpdateListener() {
			@Override
			public void onAnimationUpdate(ValueAnimator animation) {
				float cVal = (Float) animation.getAnimatedValue();
				view.setAlpha(cVal);
			}
		});

	}
}

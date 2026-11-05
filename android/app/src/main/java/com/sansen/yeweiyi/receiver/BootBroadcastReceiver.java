package com.sansen.yeweiyi.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootBroadcastReceiver extends BroadcastReceiver {
	static final String action_boot = "android.intent.action.BOOT_COMPLETED";

	@Override
	public void onReceive(final Context context, Intent intent) {

//		if (intent.getAction().equals(action_boot)) {
//			System.out.println("接受了几次广播？");
//			Intent ootStartIntent = new Intent(context, SplashActivity.class);
//
//			ootStartIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
//
//			context.startActivity(ootStartIntent);
//		}
	}
}
